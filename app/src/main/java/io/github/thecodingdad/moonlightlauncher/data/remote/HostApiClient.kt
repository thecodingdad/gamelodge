package io.github.thecodingdad.moonlightlauncher.data.remote

import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigDao
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Host software behind the web API; decides how to log in. */
enum class HostApiType { VIBEPOLLO, APOLLO, SUNSHINE }

/**
 * Optional web API of the streaming host (config UI, port 47990). Reads the app list including
 * the "x-companion" data the Moonlight Launcher Companion (Windows) writes into each entry:
 * game or app, platform, store, groups and whether the game is installed. Logins: Vibepollo API
 * token, Sunshine user/password (Basic), Apollo user/password (session cookie; Apollo keeps a
 * single session, so this is read sparingly – cached for 10 minutes).
 */
class HostApiClient(
    http: OkHttpClient,
    private val configs: HostConfigDao,
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, Result>>()

    // The config UI uses a self-signed certificate (issued for the PC name, not the address the
    // user typed). Trust is relaxed only on this client, which only talks to the URL the user
    // configured explicitly for this host.
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val cookies = ConcurrentHashMap<String, List<Cookie>>()
    private val client = http.newBuilder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { this@HostApiClient.cookies[url.host] = cookies }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host].orEmpty()
        })
        .build()

    /** What the companion wrote into one host entry. */
    data class CompanionInfo(
        val id: String,
        val kind: String,
        val source: String?,
        val platform: String?,
        val installed: Boolean,
        val emulator: String?,
        val collections: List<String>,
    ) {
        val isGame: Boolean get() = kind != "app"

        /** Display name of the store for the library filter ("Steam", "Epic Games" …). */
        val sourceLabel: String?
            get() = when (source) {
                null -> null
                "steam" -> "Steam"
                "epic" -> "Epic Games"
                "gog" -> "GOG"
                "xbox" -> "Xbox"
                "ea" -> "EA app"
                "ubisoft" -> "Ubisoft Connect"
                "battlenet" -> "Battle.net"
                "amazon" -> "Amazon Games"
                "emulator" -> emulator ?: "Emulator"
                "playnite" -> "Playnite"
                else -> source
            }
    }

    data class Result(
        val apps: Int,
        /** App UUID (upper case) → companion data, for entries the companion manages. */
        val companion: Map<String, CompanionInfo>,
    ) {
        fun infoForApp(appUuid: String?): CompanionInfo? = appUuid?.uppercase()?.let { companion[it] }
    }

    /** Cached (10 min) result, or null when the host API isn't configured/reachable. */
    suspend fun cached(hostUuid: String): Result? {
        cache[hostUuid]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        return fetch(hostUuid)?.also { cache[hostUuid] = System.currentTimeMillis() to it }
    }

    suspend fun fetch(hostUuid: String): Result? = withContext(Dispatchers.IO) {
        val config = configs.get(hostUuid) ?: return@withContext null
        val base = config.vibepolloUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val type = runCatching { HostApiType.valueOf(config.apiType ?: "VIBEPOLLO") }.getOrDefault(HostApiType.VIBEPOLLO)
        val secret = secrets.get(SecretStore.Keys.vibepolloToken(hostUuid)) ?: return@withContext null
        val auth: String? = when (type) {
            HostApiType.VIBEPOLLO -> "Bearer $secret"
            HostApiType.SUNSHINE -> config.apiUser?.let { Credentials.basic(it, secret) }
            HostApiType.APOLLO -> null
        }
        if (type == HostApiType.APOLLO && !apolloLogin(base, config.apiUser ?: return@withContext null, secret)) return@withContext null
        val (code, body) = get(base, "/api/apps", auth) ?: return@withContext null
        if (code != 200) {
            Log.w("Host API /api/apps failed: $code")
            return@withContext null
        }
        parse(body)
    }

    private fun apolloLogin(base: String, user: String, password: String): Boolean {
        val url = "$base/api/login".toHttpUrlOrNull() ?: return false
        val payload = buildJsonObject { put("username", user); put("password", password) }.toString()
        return runCatching {
            client.newCall(Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
        }.onFailure { Log.w("Apollo login failed", it) }.getOrDefault(false)
    }

    private fun get(base: String, path: String, auth: String?): Pair<Int, String>? {
        val url = "$base$path".toHttpUrlOrNull() ?: return null
        return runCatching {
            val request = Request.Builder().url(url).apply { if (auth != null) header("Authorization", auth) }.build()
            client.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }
        }.onFailure { Log.w("Host API $path not reachable", it) }.getOrNull()
    }

    fun invalidate(hostUuid: String) {
        cache.remove(hostUuid)
    }

    /** Accepts `{ "apps": [...] }` as well as a bare array; companion data lives in "x-companion". */
    internal fun parse(body: String): Result {
        val root = json.parseToJsonElement(body)
        val apps: JsonArray = (root as? JsonArray) ?: root.jsonObject["apps"]?.jsonArray ?: JsonArray(emptyList())
        val companion = apps.mapNotNull { it as? JsonObject }.mapNotNull { app ->
            val uuid = app["uuid"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return@mapNotNull null
            val c = app["x-companion"] as? JsonObject ?: return@mapNotNull null
            fun s(key: String) = (c[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            uuid to CompanionInfo(
                id = s("id") ?: return@mapNotNull null,
                kind = s("kind") ?: "game",
                source = s("source"),
                platform = s("platform"),
                installed = (c["installed"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: true,
                emulator = s("emulator"),
                collections = (c["collections"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull },
            )
        }.toMap()
        return Result(apps.size, companion)
    }
}
