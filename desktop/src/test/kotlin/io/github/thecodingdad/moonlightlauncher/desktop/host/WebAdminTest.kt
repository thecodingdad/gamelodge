package io.github.thecodingdad.moonlightlauncher.desktop.host

import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.platform.SecretBackend
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class WebAdminTest {
    private val dir = Files.createTempDirectory("webadmin").toFile()
    private val secrets = SecretStore(object : SecretBackend {
        private val values = HashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { if (value.isNullOrEmpty()) values -= key else values[key] = value }
        override fun keys() = values.keys.toSet()
    })
    private var now = 1_000_000_000_000L
    private val identity = ServerIdentity.create("Test PC")
    private val auth = AdminAuth(dir.resolve("admin.json"), secrets) { now }
    private val devices = DeviceRegistry(dir.resolve("devices.json"))
    private val pairing = PairingManager(devices, { identity.fingerprint }, Permissions.all)
    private val web = WebAdmin(identity, auth, devices, pairing, clock = { now })
    private lateinit var base: String

    // The test trusts the self-signed certificate (browsers ask the user once).
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val http = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(false)
        .build()

    @Before
    fun start() {
        web.start(0)
        base = "https://127.0.0.1:${web.port}"
    }

    @After
    fun stop() {
        web.stop()
        dir.deleteRecursively()
    }

    private fun get(path: String, cookie: String? = null): Response =
        http.newCall(Request.Builder().url(base + path).apply { cookie?.let { header("Cookie", it) } }.build()).execute()

    private fun post(path: String, form: Map<String, String>, cookie: String? = null, origin: String? = base): Response {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(
            Request.Builder().url(base + path).post(body)
                .apply { cookie?.let { header("Cookie", it) }; origin?.let { header("Origin", it) } }.build(),
        ).execute()
    }

    private fun Response.cookie() = headers("Set-Cookie").first().substringBefore(';')

    private fun Response.text() = use { it.body!!.string() }

    private fun csrf(html: String) = Regex("name=\"csrf\" value=\"([0-9a-f]+)\"").find(html)!!.groupValues[1]

    /** Sets the account up and returns the session cookie. */
    private fun setUp(): String {
        val code = auth.issueSetupCode()
        post("/setup", mapOf("code" to code, "password" to PASSWORD, "confirm" to PASSWORD)).use { r ->
            assertEquals(303, r.code)
            assertEquals("/pairing", r.header("Location"))
            return r.cookie()
        }
    }

    @Test
    fun setupNeedsTheCodeFromTheLauncher() {
        assertEquals("/setup", get("/pairing").use { it.header("Location") })
        auth.issueSetupCode()
        val wrong = post("/setup", mapOf("code" to "AAAA-BBBB-CCCC", "password" to PASSWORD, "confirm" to PASSWORD))
        assertEquals(200, wrong.code)
        assertTrue(wrong.text().contains("role=\"alert\""))
        assertTrue(auth.needsSetup)

        val cookie = setUp()
        assertFalse(auth.needsSetup)
        val page = get("/pairing", cookie)
        assertEquals(200, page.code)
        assertTrue(page.header("Content-Security-Policy")!!.contains("script-src 'self'"))
        assertTrue(page.text().contains(io.github.thecodingdad.moonlightlauncher.data.companion.Fingerprint.pretty(identity.fingerprint)))
        // Set up once only.
        assertEquals("/login", get("/setup").use { it.header("Location") })
    }

    @Test
    fun pagesLetBrowsersSendTheirOrigin() {
        // "no-referrer" makes browsers post with "Origin: null" (refused by the origin check).
        val policy = get("/setup").use { it.header("Referrer-Policy") }
        assertEquals("same-origin", policy)
        auth.issueSetupCode()
        assertEquals(403, post("/setup", mapOf("code" to "x"), origin = "null").use { it.code })
    }

    @Test
    fun localLinkSignsInOnce() {
        val url = web.localLoginUrl()!!
        val path = url.substringAfter(web.port.toString())
        val r = get(path)
        assertEquals("/pairing", r.header("Location"))
        val cookie = r.use { it.cookie() }
        assertEquals(200, get("/pairing", cookie).use { it.code })
        // Used up; forged or expired tokens don't work either.
        assertEquals("/login", get(path).use { it.header("Location") })
        assertEquals("/login", get("/auth/local?token=forged").use { it.header("Location") })
        val late = web.localLoginUrl()!!.substringAfter(web.port.toString())
        now += WebAdmin.LOCAL_LOGIN_MS + 1
        assertEquals("/login", get(late).use { it.header("Location") })
    }

    @Test
    fun expiredSetupCodeIsRefused() {
        val code = auth.issueSetupCode()
        now += AdminAuth.SETUP_CODE_LIFETIME_MS + 1
        post("/setup", mapOf("code" to code, "password" to PASSWORD, "confirm" to PASSWORD)).close()
        assertTrue(auth.needsSetup)
    }

    @Test
    fun formsNeedTheOriginAndTheSessionToken() {
        val cookie = setUp()
        val token = csrf(get("/pairing", cookie).text())
        assertEquals(403, post("/pairing/open", mapOf("csrf" to token), cookie, origin = null).use { it.code })
        assertEquals(403, post("/pairing/open", mapOf("csrf" to token), cookie, origin = "https://evil.example").use { it.code })
        assertEquals(403, post("/pairing/open", mapOf("csrf" to "00"), cookie).use { it.code })
        assertFalse(pairing.isOpen)
        assertEquals(303, post("/pairing/open", mapOf("csrf" to token), cookie).use { it.code })
        assertTrue(pairing.isOpen)
    }

    @Test
    fun withoutSessionEverythingRedirectsToLogin() {
        setUp()
        for (path in listOf("/pairing", "/devices", "/security", "/pairing/status")) {
            assertEquals(path, "/login", get(path).use { it.header("Location") })
        }
        assertEquals("/login", post("/pairing/open", emptyMap()).use { it.header("Location") })
        assertFalse(pairing.isOpen)
        assertEquals("/login", get("/pairing", "__Host-session=forged").use { it.header("Location") })
    }

    @Test
    fun loginLogoutAndPasswordChangeEndSessions() {
        val first = setUp()
        assertTrue(post("/login", mapOf("password" to "wrong password!")).text().contains("role=\"alert\""))
        val second = post("/login", mapOf("password" to PASSWORD)).use { assertEquals(303, it.code); it.cookie() }

        val token = csrf(get("/security", second).text())
        val newPassword = "another long password"
        val r = post("/security/password", mapOf("csrf" to token, "current" to PASSWORD, "password" to newPassword, "confirm" to newPassword), second)
        assertEquals("/login?changed=1", r.use { it.header("Location") })
        assertEquals("/login", get("/pairing", first).use { it.header("Location") })
        assertEquals("/login", get("/pairing", second).use { it.header("Location") })

        val third = post("/login", mapOf("password" to newPassword)).use { it.cookie() }
        val t3 = csrf(get("/pairing", third).text())
        post("/logout", mapOf("csrf" to t3), third).close()
        assertEquals("/login", get("/pairing", third).use { it.header("Location") })
    }

    @Test
    fun sessionsEndWhenIdle() {
        val cookie = setUp()
        now += WebAdmin.IDLE_TIMEOUT_MS + 1
        assertEquals("/login", get("/pairing", cookie).use { it.header("Location") })
    }

    @Test
    fun pinEntryAndDeviceNamesAreEscaped() {
        val cookie = setUp()
        devices.add("<script>alert(1)</script>", "AB".repeat(32), setOf(Permissions.METADATA), "192.168.1.2")
        val html = get("/devices", cookie).text()
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;"))

        val token = csrf(html)
        val id = devices.devices.value.single().id
        post("/devices/permissions", mapOf("csrf" to token, "id" to id, "perm_hostgames" to "on"), cookie).close()
        assertEquals(setOf(Permissions.HOST_GAMES), devices.devices.value.single().permissions)
        post("/devices/remove", mapOf("csrf" to token, "id" to id), cookie).close()
        assertTrue(devices.devices.value.isEmpty())
    }

    @Test
    fun totpProtectsTheLogin() {
        val cookie = setUp()
        var token = csrf(get("/security", cookie).text())
        post("/security/totp/begin", mapOf("csrf" to token), cookie).close()
        val page = get("/security", cookie).text()
        assertTrue(page.contains("<svg"))
        token = csrf(page)
        val secret = base32Decode(Regex("<code>([A-Z2-7]{32})</code>").find(page)!!.groupValues[1])
        val code = Totp.code(secret, now / 30_000)
        post("/security/totp/enable", mapOf("csrf" to token, "code" to code, "password" to PASSWORD), cookie).close()
        assertTrue(auth.totpEnabled)
        val recovery = Regex("<li><code>([A-Z0-9-]+)</code></li>").findAll(get("/security", cookie).text()).map { it.groupValues[1] }.toList()
        assertEquals(10, recovery.size)

        assertTrue(post("/login", mapOf("password" to PASSWORD)).text().contains("role=\"alert\""))
        // The same code works only once.
        assertEquals(200, post("/login", mapOf("password" to PASSWORD, "code" to code)).use { it.code })
        now += 30_000
        assertEquals(303, post("/login", mapOf("password" to PASSWORD, "code" to Totp.code(secret, now / 30_000))).use { it.code })
        // A recovery code works once as well.
        assertEquals(303, post("/login", mapOf("password" to PASSWORD, "code" to recovery[0])).use { it.code })
        assertEquals(200, post("/login", mapOf("password" to PASSWORD, "code" to recovery[0])).use { it.code })
        assertEquals(9, auth.recoveryCodesLeft)
    }

    @Test
    fun failedLoginsAreThrottled() {
        setUp()
        repeat(5) { post("/login", mapOf("password" to "wrong password!")).close() }
        assertTrue(post("/login", mapOf("password" to PASSWORD)).text().contains("role=\"alert\""))
        now += 3_000
        assertEquals(303, post("/login", mapOf("password" to PASSWORD)).use { it.code })
    }

    @Test
    fun onlyPrivateAddresses() {
        for (a in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.168.68", "100.64.0.1", "169.254.1.1", "::1", "fe80::1", "fd00::1", "::ffff:192.168.1.1")) {
            assertTrue(a, WebAdmin.isPrivate(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860::8888", "::ffff:8.8.8.8")) {
            assertFalse(a, WebAdmin.isPrivate(InetAddress.getByName(a)))
        }
    }

    @Test
    fun passwordHashesUseTheCompanionsFormat() {
        val hash = PasswordHash.create(PASSWORD)
        assertTrue(hash.startsWith("argon2id$65536$3$1$"))
        assertTrue(PasswordHash.verify(PASSWORD, hash))
        assertFalse(PasswordHash.verify("wrong", hash))
        assertNull(Totp.match(ByteArray(20), "12345", now))
        assertNotNull(Totp.match(ByteArray(20), Totp.code(ByteArray(20), now / 30_000), now))
    }

    @Test
    fun germanAndEnglishTextsMatch() {
        fun keys(name: String) = java.util.Properties().apply {
            WebAdmin::class.java.getResourceAsStream("/web/$name")!!.reader(Charsets.UTF_8).use { load(it) }
        }.keys.map { it.toString() }.toSet()
        assertEquals(keys("strings.properties"), keys("strings_de.properties"))
    }

    private fun base32Decode(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in s) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
