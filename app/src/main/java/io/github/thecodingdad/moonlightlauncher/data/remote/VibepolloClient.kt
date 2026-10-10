package io.github.thecodingdad.moonlightlauncher.data.remote

import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigDao
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Optional Vibepollo web API (config UI, port 47990). Used only for hosts where the user
 * entered URL and access token; tells which apps were synced from Playnite (= games).
 */
class VibepolloClient(
    http: OkHttpClient,
    private val configs: HostConfigDao,
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    // The config UI uses a self-signed certificate. Trust is relaxed only for the host the user
    // configured explicitly (hostname verification against that exact host).
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val client = http.newBuilder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory, trustAll)
        .build()

    data class Result(val apps: Int, val playniteUuids: Set<String>)

    /** App UUIDs synced from Playnite, or null when Vibepollo isn't configured/reachable. */
    suspend fun playniteUuids(hostUuid: String): Set<String>? {
        cache[hostUuid]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        return fetch(hostUuid)?.playniteUuids?.also { cache[hostUuid] = System.currentTimeMillis() to it }
    }

    suspend fun fetch(hostUuid: String): Result? = withContext(Dispatchers.IO) {
        val config = configs.get(hostUuid) ?: return@withContext null
        val base = config.vibepolloUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val token = secrets.get(SecretStore.Keys.vibepolloToken(hostUuid)) ?: return@withContext null
        val url = "$base/api/apps".toHttpUrlOrNull() ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w("Vibepollo /api/apps failed: ${r.code}")
                    return@use null
                }
                parse(r.body?.string().orEmpty())
            }
        }.onFailure { Log.w("Vibepollo not reachable", it) }.getOrNull()
    }

    fun invalidate(hostUuid: String) {
        cache.remove(hostUuid)
    }

    /** Accepts `{ "apps": [...] }` as well as a bare array; entries with `playnite-id` are games. */
    internal fun parse(body: String): Result {
        val root = json.parseToJsonElement(body)
        val apps: JsonArray = (root as? JsonArray) ?: root.jsonObject["apps"]?.jsonArray ?: JsonArray(emptyList())
        val playnite = apps.mapNotNull { it as? JsonObject }
            .filter { !it["playnite-id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
            .mapNotNull { it["uuid"]?.jsonPrimitive?.contentOrNull?.uppercase() }
            .toSet()
        return Result(apps.size, playnite)
    }
}
