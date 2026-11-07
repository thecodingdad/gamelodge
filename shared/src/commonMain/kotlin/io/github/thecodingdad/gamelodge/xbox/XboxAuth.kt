package io.github.thecodingdad.gamelodge.xbox

import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

class XboxAuthException(message: String, val signedOut: Boolean = false) : Exception(message)

/** A pending sign-in: the user opens [verificationUri] on any device and enters [userCode]. */
data class XboxDeviceCode(
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    internal val deviceCode: String,
    internal val intervalSeconds: Int,
) {
    /** The page with the code already filled in (for a QR code). */
    val directUri: String get() = "$verificationUri?otc=$userCode"
}

/** An Xbox Live authorization header ("XBL3.0 x=…;…") with the account's ids. */
data class XblToken(val header: String, val xuid: String?, val gamertag: String?, val expiresAt: Long)

/** Streaming login for Xbox home streaming: token and the region host its sessions run on. */
data class XhomeToken(val gsToken: String, val baseUri: String, val expiresAt: Long)

/**
 * Microsoft account sign-in for Xbox (device code flow, works without keyboard: the user signs in
 * on a phone) and the token chain behind it: Microsoft token → Xbox user token → XSTS tokens per
 * service, and the streaming token for home streaming.
 *
 * Only the refresh token is stored ([SecretStore], key with "_identity": never part of a backup,
 * like pairings). All other tokens live in memory. No token ever goes to the log.
 */
class XboxAuth(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val endpoints: XboxEndpoints = XboxEndpoints(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val mutex = Mutex()

    private var msAccess: Pair<String, Long>? = null
    private var userToken: Pair<String, Long>? = null
    private val xsts = HashMap<String, XblToken>()
    private var xhome: XhomeToken? = null

    @Serializable
    private data class Stored(val refreshToken: String, val gamertag: String? = null, val xuid: String? = null)

    private fun stored(): Stored? = secrets.get(KEY)?.let { runCatching { json.decodeFromString<Stored>(it) }.getOrNull() }

    val isSignedIn: Boolean get() = stored() != null

    /** Gamertag of the signed-in account (known after the first Xbox Live call). */
    val gamertag: String? get() = stored()?.gamertag

    fun signOut() {
        secrets.put(KEY, null)
        clearCache()
    }

    private fun clearCache() {
        msAccess = null
        userToken = null
        xsts.clear()
        xhome = null
    }

    /** Starts a sign-in; show the code to the user and call [awaitSignIn]. */
    suspend fun startSignIn(): XboxDeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("client_id", CLIENT_ID).add("scope", SCOPE).build()
        val o = postForm(endpoints.deviceCode, body)
        XboxDeviceCode(
            userCode = o.str("user_code") ?: throw XboxAuthException("No user code"),
            verificationUri = o.str("verification_uri") ?: "https://www.microsoft.com/link",
            expiresInSeconds = o.str("expires_in")?.toIntOrNull() ?: 900,
            deviceCode = o.str("device_code") ?: throw XboxAuthException("No device code"),
            intervalSeconds = o.str("interval")?.toIntOrNull() ?: 5,
        )
    }

    /** Waits until the user signed in with [code] (true) or the code expired / was declined (false). */
    suspend fun awaitSignIn(code: XboxDeviceCode): Boolean = withContext(Dispatchers.IO) {
        val until = clock() + code.expiresInSeconds * 1000L
        var interval = code.intervalSeconds.coerceAtLeast(1)
        while (clock() < until) {
            delay(interval * 1000L)
            val body = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", CLIENT_ID)
                .add("device_code", code.deviceCode)
                .build()
            val response = http.newCall(Request.Builder().url(endpoints.token).post(body).build()).execute()
            val text = response.use { it.body?.string().orEmpty() }
            val o = parse(text)
            if (response.isSuccessful) {
                mutex.withLock {
                    clearCache()
                    store(o)
                }
                // Learn the gamertag right away (shown on the host card).
                runCatching { web() }
                return@withContext true
            }
            when (o.str("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> return@withContext false
            }
        }
        false
    }

    private fun store(o: JsonObject) {
        val refresh = o.str("refresh_token") ?: throw XboxAuthException("No refresh token")
        val access = o.str("access_token") ?: throw XboxAuthException("No access token")
        val expires = o.str("expires_in")?.toLongOrNull() ?: 3600
        val old = stored()
        secrets.put(KEY, json.encodeToString(Stored(refresh, old?.gamertag, old?.xuid)))
        msAccess = access to clock() + expires * 1000
    }

    private fun valid(expiresAt: Long) = expiresAt - clock() > 60_000

    private fun msAccessToken(): String {
        msAccess?.let { (t, exp) -> if (valid(exp)) return t }
        val refresh = stored()?.refreshToken ?: throw XboxAuthException("Not signed in", signedOut = true)
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("scope", SCOPE)
            .build()
        val response = http.newCall(Request.Builder().url(endpoints.token).post(body).build()).execute()
        val text = response.use { it.body?.string().orEmpty() }
        val o = parse(text)
        if (!response.isSuccessful) {
            val error = o.str("error")
            // The account withdrew the access or the sign-in expired: the user signs in again.
            if (error == "invalid_grant" || error == "interactive_required") {
                signOut()
                throw XboxAuthException("Signed out ($error)", signedOut = true)
            }
            throw XboxAuthException("Token refresh failed: HTTP ${response.code} $error")
        }
        store(o)
        return msAccess!!.first
    }

    private fun xblUserToken(): String {
        userToken?.let { (t, exp) -> if (valid(exp)) return t }
        val body = buildJsonObject {
            put("Properties", buildJsonObject {
                put("AuthMethod", "RPS")
                put("RpsTicket", "d=" + msAccessToken())
                put("SiteName", "user.auth.xboxlive.com")
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }
        val o = postJson(endpoints.userAuth, body.toString(), XBL_HEADERS)
        val token = o.str("Token") ?: throw XboxAuthException("No user token")
        userToken = token to (o.str("NotAfter")?.let(::instant) ?: (clock() + 3_600_000))
        return token
    }

    private fun xstsFor(relyingParty: String): XblToken {
        xsts[relyingParty]?.let { if (valid(it.expiresAt)) return it }
        val body = buildJsonObject {
            put("Properties", buildJsonObject {
                put("SandboxId", "RETAIL")
                put("UserTokens", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(xblUserToken())) })
            })
            put("RelyingParty", relyingParty)
            put("TokenType", "JWT")
        }
        val o = postJson(endpoints.xsts, body.toString(), XBL_HEADERS)
        val token = o.str("Token") ?: throw XboxAuthException("No XSTS token")
        val claims = runCatching { o["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject }.getOrNull()
        val uhs = claims?.str("uhs") ?: throw XboxAuthException("No user hash")
        val result = XblToken(
            header = "XBL3.0 x=$uhs;$token",
            xuid = claims.str("xid"),
            gamertag = claims.str("gtg"),
            expiresAt = o.str("NotAfter")?.let(::instant) ?: (clock() + 3_600_000),
        )
        xsts[relyingParty] = result
        if (result.gamertag != null) {
            stored()?.let { if (it.gamertag != result.gamertag) secrets.put(KEY, json.encodeToString(it.copy(gamertag = result.gamertag, xuid = result.xuid))) }
        }
        return result
    }

    /** Authorization for Xbox Live services (consoles, presence). */
    suspend fun web(): XblToken = withContext(Dispatchers.IO) { mutex.withLock { xstsFor("http://xboxlive.com") } }

    /** Authorization for the Microsoft Store collections (game licenses). */
    suspend fun licensing(): XblToken = withContext(Dispatchers.IO) { mutex.withLock { xstsFor("http://licensing.xboxlive.com") } }

    /** The streaming login for home streaming. */
    suspend fun xhome(): XhomeToken = withContext(Dispatchers.IO) {
        mutex.withLock {
            xhome?.let { if (valid(it.expiresAt)) return@withLock it }
            val gssv = xstsFor("http://gssv.xboxlive.com/")
            val token = gssv.header.substringAfter(';')
            val body = buildJsonObject { put("token", token); put("offeringId", "xhome") }
            val o = postJson(endpoints.xhomeLogin, body.toString(), mapOf("x-gssv-client" to "XboxComBrowser", "Cache-Control" to "no-store"))
            val gsToken = o.str("gsToken") ?: throw XboxAuthException("No streaming token")
            val regions = runCatching { o["offeringSettings"]!!.jsonObject["regions"]!!.jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
            val region = regions.firstOrNull { it.str("isDefault") == "true" } ?: regions.firstOrNull()
            val base = region?.str("baseUri") ?: throw XboxAuthException("No streaming region")
            val seconds = o.str("durationInSeconds")?.toLongOrNull() ?: 3600
            XhomeToken(gsToken, base.trimEnd('/'), clock() + seconds * 1000).also { xhome = it }
        }
    }

    private fun postForm(url: String, body: FormBody): JsonObject {
        val response = http.newCall(Request.Builder().url(url).post(body).build()).execute()
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) throw XboxAuthException("HTTP ${response.code} ${parse(text).str("error")}")
        return parse(text)
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): JsonObject {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON))
        headers.forEach { (k, v) -> request.header(k, v) }
        val response = http.newCall(request.build()).execute()
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) {
            // XSTS explains refusals with an XErr code (e.g. no Xbox profile, child account): logged, no token in it.
            val xerr = runCatching { parse(text).str("XErr") }.getOrNull()
            Log.w("Xbox auth ${url.substringAfter("://").substringBefore('/')}: HTTP ${response.code}${xerr?.let { " XErr $it" } ?: ""}")
            throw XboxAuthException("HTTP ${response.code}${xerr?.let { " (XErr $it)" } ?: ""}")
        }
        return parse(text)
    }

    private fun parse(text: String): JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun instant(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()

    companion object {
        /** Public client id of the Xbox apps' device code sign-in (used by the open source Xbox streaming clients). */
        const val CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
        const val SCOPE = "xboxlive.signin openid profile offline_access"
        /** "_identity": kept out of backups (BackupManager), the account is signed in again after a restore. */
        const val KEY = "xbox_account_identity"
        private val JSON = "application/json".toMediaType()
        private val XBL_HEADERS = mapOf(
            "x-xbl-contract-version" to "1",
            "Origin" to "https://www.xbox.com",
            "Referer" to "https://www.xbox.com/",
            "Cache-Control" to "no-cache",
        )
    }
}

/** Service addresses (tests point them at a mock server). */
data class XboxEndpoints(
    val deviceCode: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
    val token: String = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
    val userAuth: String = "https://user.auth.xboxlive.com/user/authenticate",
    val xsts: String = "https://xsts.auth.xboxlive.com/xsts/authorize",
    val xhomeLogin: String = "https://xhome.gssv-play-prod.xboxlive.com/v2/login/user",
    val xccs: String = "https://xccs.xboxlive.com",
    val presence: String = "https://userpresence.xboxlive.com",
    val collections: String = "https://collections.mp.microsoft.com",
    val catalog: String = "https://displaycatalog.mp.microsoft.com",
)

/** A JSON value as string (strings, numbers and booleans alike); null when missing or JSON null. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
