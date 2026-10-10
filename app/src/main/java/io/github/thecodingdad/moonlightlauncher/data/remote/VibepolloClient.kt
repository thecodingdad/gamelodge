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
    private val cache = ConcurrentHashMap<String, Pair<Long, Result>>()

    // The config UI uses a self-signed certificate (issued for the PC name, not the address the
    // user typed). Trust is relaxed only on this client, which only talks to the URL the user
    // configured explicitly for this host.
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val client = http.newBuilder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .build()

    /** Playnite facts about one game: library plugin (Steam, Epic …) and the user's categories. */
    data class PlayniteInfo(val plugin: String?, val categories: List<String>)

    data class Result(
        val apps: Int,
        /** App UUID (upper case) → Playnite id, for apps synced from Playnite. */
        val playniteIds: Map<String, String>,
        /** Playnite id → info; empty when the token lacks the Playnite read permission. */
        val games: Map<String, PlayniteInfo> = emptyMap(),
        val gamesError: Int? = null,
    ) {
        val playniteUuids: Set<String> get() = playniteIds.keys
        fun infoForApp(appUuid: String?): PlayniteInfo? = appUuid?.uppercase()?.let { playniteIds[it] }?.let { games[it] }
    }

    /** Cached (10 min) result, or null when Vibepollo isn't configured/reachable. */
    suspend fun cached(hostUuid: String): Result? {
        cache[hostUuid]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        return fetch(hostUuid)?.also { cache[hostUuid] = System.currentTimeMillis() to it }
    }

    suspend fun playniteUuids(hostUuid: String): Set<String>? = cached(hostUuid)?.playniteUuids

    suspend fun fetch(hostUuid: String): Result? = withContext(Dispatchers.IO) {
        val config = configs.get(hostUuid) ?: return@withContext null
        val base = config.vibepolloUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val token = secrets.get(SecretStore.Keys.vibepolloToken(hostUuid)) ?: return@withContext null
        val apps = get(base, "/api/apps", token)?.let { (code, body) ->
            if (code != 200) {
                Log.w("Vibepollo /api/apps failed: $code")
                null
            } else parse(body)
        } ?: return@withContext null
        // Playnite details need their own permission; Vibepollo answers with an empty list while
        // its Playnite connection warms up, so ask once more after a short pause.
        var games = get(base, "/api/playnite/games", token)
        if (games?.first == 200 && parseGames(games.second).isEmpty()) {
            kotlinx.coroutines.delay(1_500)
            games = get(base, "/api/playnite/games", token)
        }
        when (games?.first) {
            200 -> apps.copy(games = parseGames(games.second))
            null -> apps
            else -> apps.copy(gamesError = games.first)
        }
    }

    private fun get(base: String, path: String, token: String): Pair<Int, String>? {
        val url = "$base$path".toHttpUrlOrNull() ?: return null
        return runCatching {
            client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                r.code to r.body?.string().orEmpty()
            }
        }.onFailure { Log.w("Vibepollo $path not reachable", it) }.getOrNull()
    }

    fun invalidate(hostUuid: String) {
        cache.remove(hostUuid)
    }

    /** Accepts `{ "apps": [...] }` as well as a bare array; entries with `playnite-id` are games. */
    internal fun parse(body: String): Result {
        val root = json.parseToJsonElement(body)
        val apps: JsonArray = (root as? JsonArray) ?: root.jsonObject["apps"]?.jsonArray ?: JsonArray(emptyList())
        val playnite = apps.mapNotNull { it as? JsonObject }.mapNotNull { app ->
            val id = app["playnite-id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val uuid = app["uuid"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return@mapNotNull null
            uuid to id.lowercase()
        }.toMap()
        return Result(apps.size, playnite)
    }

    /** `/api/playnite/games`: `[{ id, name, pluginName, categories: [..] }]` (or wrapped in `games`). */
    internal fun parseGames(body: String): Map<String, PlayniteInfo> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyMap()
        val games: JsonArray = (root as? JsonArray) ?: (root as? JsonObject)?.get("games") as? JsonArray ?: return emptyMap()
        return games.mapNotNull { it as? JsonObject }.mapNotNull { game ->
            val id = game["id"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val categories = (game["categories"] as? JsonArray).orEmpty().mapNotNull { c ->
                (c as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: runCatching { c.jsonPrimitive.contentOrNull }.getOrNull()
            }.filter { it.isNotBlank() }
            id to PlayniteInfo(game["pluginName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }, categories)
        }.toMap()
    }
}
