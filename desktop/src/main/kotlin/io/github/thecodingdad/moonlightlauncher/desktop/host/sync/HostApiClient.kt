package io.github.thecodingdad.moonlightlauncher.desktop.host.sync

import io.github.thecodingdad.moonlightlauncher.host.*

import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

enum class HostType { VIBEPOLLO, APOLLO, SUNSHINE }

/**
 * Web API of the streaming host on this PC. Logins: Vibepollo API token (Bearer), Sunshine user
 * + password (Basic), Apollo user + password (session cookie from /api/login; Apollo keeps one
 * session, so this signs the browser out). Requests carry no Origin/Referer and pass the hosts'
 * CSRF checks like other API clients. The host's self-signed certificate is accepted for the
 * configured host name only.
 */
class HostApiClient(
    baseUrl: String,
    private val type: HostType,
    private val secret: String?,
    private val user: String?,
    http: OkHttpClient,
) {
    data class Response(val status: Int, val body: String) {
        val ok get() = status in 200..299
    }

    private val base = baseUrl.trimEnd('/') + "/"
    private val host = runCatching { URI(base).host }.getOrNull()
    private val cookies = mutableMapOf<String, String>()
    private var loggedIn = false
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = run {
        // Self-signed: accepted for the configured host only (the PC itself in practice).
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        http.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { name, _ -> name.equals(host, ignoreCase = true) }
            .followRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** All app entries ("apps" of GET /api/apps), or null with the failed response. */
    fun getApps(): Pair<JsonArray?, Response> {
        val res = send("GET", "api/apps", null)
        if (!res.ok) return null to res
        val root = runCatching { json.parseToJsonElement(res.body) }.getOrNull()
        val apps = root as? JsonArray ?: (root as? JsonObject)?.get("apps") as? JsonArray
        return apps to res
    }

    /** Creates (no uuid) or replaces (existing uuid) one app entry. */
    fun saveApp(app: JsonObject) = send("POST", "api/apps", app.toString())

    /** Vibepollo/Apollo delete by uuid; Sunshine by list index. */
    fun deleteApp(uuid: String): Response {
        if (type != HostType.SUNSHINE) return send("POST", "api/apps/delete", buildJsonObject { put("uuid", uuid) }.toString())
        val (apps, res) = getApps()
        val index = apps?.indexOfFirst { (it as? JsonObject)?.str("uuid") == uuid } ?: -1
        if (apps == null || index < 0) return res.copy(status = 404)
        return send("DELETE", "api/apps/$index", null)
    }

    private fun login() {
        if (type != HostType.APOLLO || loggedIn) return
        val res = raw("POST", "api/login", buildJsonObject { put("username", user); put("password", secret) }.toString())
        Log.d("Host: Apollo login → ${res.status}")
        loggedIn = res.ok
    }

    private fun send(method: String, path: String, body: String?): Response {
        login()
        val res = raw(method, path, body)
        Log.d("Host: $method /$path → ${res.status}, ${res.body.length} bytes")
        return res
    }

    private fun raw(method: String, path: String, body: String?): Response {
        val request = Request.Builder().url(base + path)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .apply {
                when (type) {
                    HostType.VIBEPOLLO -> secret?.let { header("Authorization", "Bearer $it") }
                    HostType.SUNSHINE -> if (user != null) header("Authorization", Credentials.basic(user, secret.orEmpty()))
                    HostType.APOLLO -> if (cookies.isNotEmpty()) header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
            .build()
        return try {
            client.newCall(request).execute().use { r ->
                r.headers("Set-Cookie").forEach { c ->
                    val pair = c.substringBefore(';')
                    if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
                }
                Response(r.code, r.body?.string().orEmpty())
            }
        } catch (e: java.io.IOException) {
            Log.w("Host: $method /$path failed: ${e.message}")
            Response(0, "")
        }
    }
}
