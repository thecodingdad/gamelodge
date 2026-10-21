package io.github.thecodingdad.gamelodge.ha

import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
private data class HaTokenResponse(
    val access_token: String,
    val expires_in: Long,
    val refresh_token: String? = null,
    val token_type: String = "Bearer",
)

class HaAuthException(message: String) : Exception(message)

/**
 * Home Assistant login (OAuth2 / IndieAuth) for a native app, following RFC 8252: the
 * redirect goes to a short-lived loopback HTTP listener. HA accepts loopback client ids,
 * so no web page has to be hosted for the client id.
 */
class HaAuth(
    private val browser: io.github.thecodingdad.gamelodge.platform.UrlOpener,
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isLoggedIn: Boolean get() = secrets.get(SecretStore.Keys.HA_REFRESH_TOKEN) != null

    /** Base URL of the instance we are logged in to. */
    val baseUrl: String? get() = secrets.get(KEY_BASE_URL)

    /**
     * Opens the HA login page and waits (up to 5 minutes) for the redirect. With [onPhoneUrl] the
     * login happens on another device (a phone scanning a QR code): the listener then waits on this
     * device's LAN address instead of loopback, and the login page address goes to [onPhoneUrl]
     * instead of the browser here. HA accepts client ids on private network addresses.
     */
    suspend fun login(baseUrl: String, onPhoneUrl: ((String) -> Unit)? = null): Unit = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseUrl)
        val address = if (onPhoneUrl == null) InetAddress.getByName("127.0.0.1") else lanAddress(base)
        ServerSocket(0, 4, address).use { server ->
            val host = address.hostAddress.let { if (':' in it) "[$it]" else it }
            val clientId = "http://$host:${server.localPort}/"
            val redirect = "${clientId}auth"
            val state = randomState()
            val authorize = "$base/auth/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirect)
                .addQueryParameter("state", state)
                .build()

            if (onPhoneUrl != null) onPhoneUrl(authorize.toString())
            else withContext(Dispatchers.Main) { browser.open(authorize.toString()) }

            server.soTimeout = 1000
            val code = withTimeoutOrNull(5 * 60_000L) { awaitRedirect(server, state) }
                ?: throw HaAuthException("Login timed out")
            exchange(base, clientId, FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", clientId).build())
            secrets.put(KEY_CLIENT_ID, clientId)
            secrets.put(KEY_BASE_URL, base)
        }
    }

    /**
     * The private address of this device that a phone in the same network reaches: the one the
     * route to HA leaves from, else the first private address of an interface that is up.
     */
    private fun lanAddress(base: String): InetAddress {
        val url = base.toHttpUrl()
        val viaRoute = runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(url.host), url.port)
                socket.localAddress
            }
        }.getOrNull()
        if (viaRoute != null && viaRoute.isSiteLocalAddress) return viaRoute
        return java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?: throw HaAuthException("No local network address")
    }

    /** Valid access token, refreshed when it expires within a minute. */
    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val token = secrets.get(SecretStore.Keys.HA_ACCESS_TOKEN)
        val expiry = secrets.get(SecretStore.Keys.HA_TOKEN_EXPIRY)?.toLongOrNull() ?: 0
        if (token != null && clock() < expiry - 60_000) return@withContext token
        val refresh = secrets.get(SecretStore.Keys.HA_REFRESH_TOKEN) ?: throw HaAuthException("Not logged in")
        val clientId = secrets.get(KEY_CLIENT_ID) ?: throw HaAuthException("Not logged in")
        val base = secrets.get(KEY_BASE_URL) ?: throw HaAuthException("Not logged in")
        exchange(base, clientId, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", clientId).build())
        secrets.get(SecretStore.Keys.HA_ACCESS_TOKEN)!!
    }

    fun logout() {
        listOf(SecretStore.Keys.HA_ACCESS_TOKEN, SecretStore.Keys.HA_REFRESH_TOKEN, SecretStore.Keys.HA_TOKEN_EXPIRY, KEY_CLIENT_ID, KEY_BASE_URL)
            .forEach { secrets.put(it, null) }
    }

    private suspend fun awaitRedirect(server: ServerSocket, expectedState: String): String {
        while (true) {
            kotlinx.coroutines.yield()
            val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            socket.use { s ->
                val requestLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                val uri = runCatching { "http://127.0.0.1$path".toHttpUrl() }.getOrNull()
                val code = uri?.queryParameter("code")
                val ok = code != null && uri.queryParameter("state") == expectedState
                val body = if (ok) SUCCESS_PAGE else FAILURE_PAGE
                s.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                )
                if (ok) return code!!
            }
        }
    }

    private fun exchange(base: String, clientId: String, form: FormBody) {
        val request = Request.Builder().url("$base/auth/token").post(form).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("HA token request failed: ${response.code}")
                if (response.code in 400..403) {
                    // Refresh token revoked or invalid: forget the login.
                    logout()
                    throw HaAuthException("Home Assistant rejected the login")
                }
                throw HaAuthException("Home Assistant not reachable (${response.code})")
            }
            val token = json.decodeFromString<HaTokenResponse>(response.body?.string().orEmpty())
            secrets.put(SecretStore.Keys.HA_ACCESS_TOKEN, token.access_token)
            secrets.put(SecretStore.Keys.HA_TOKEN_EXPIRY, (clock() + token.expires_in * 1000).toString())
            token.refresh_token?.let { secrets.put(SecretStore.Keys.HA_REFRESH_TOKEN, it) }
            secrets.put(KEY_CLIENT_ID, clientId)
        }
    }

    private fun randomState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val KEY_CLIENT_ID = "ha_client_id"
        private const val KEY_BASE_URL = "ha_base_url"
        private const val SUCCESS_PAGE =
            "<html><body style='font-family:sans-serif;background:#0e1015;color:#fff;text-align:center;padding-top:20vh'>" +
                "<h1>✓</h1><p>GameLodge is connected to Home Assistant.<br>You can close this tab.</p></body></html>"
        private const val FAILURE_PAGE =
            "<html><body style='font-family:sans-serif;background:#0e1015;color:#fff;text-align:center;padding-top:20vh'>" +
                "<p>Login failed. Please try again from the launcher.</p></body></html>"

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            return url
        }
    }
}
