package io.github.thecodingdad.moonlightlauncher.desktop.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** The provider keys the proxy uses (never handed out to devices). */
interface MetadataKeys {
    val steamGridDbKey: String?
    val igdbClientId: String?
    val igdbClientSecret: String?
}

/**
 * Forwards SteamGridDB and IGDB requests of paired devices with this PC's keys (the companion's
 * proxy). Only fixed upstream hosts and allowlisted paths/endpoints are reachable, so no request
 * can make the PC fetch arbitrary addresses. Answers are cached for all devices; IGDB is throttled
 * to its rate limit.
 */
class MetadataProxy(
    private val http: OkHttpClient,
    private val keys: MetadataKeys,
    private val cache: ResponseCache,
    private val clock: () -> Long = System::currentTimeMillis,
) : MetadataProxyApi {
    private val igdbLock = Any()
    private var igdbLast = 0L
    private var igdbToken: String? = null
    private var igdbTokenUntil = 0L
    private var igdbTokenFor: String? = null

    override val steamGridDbConfigured get() = !keys.steamGridDbKey.isNullOrBlank()
    override val igdbConfigured get() = !keys.igdbClientId.isNullOrBlank() && !keys.igdbClientSecret.isNullOrBlank()

    /** SteamGridDB GET [path] (relative to /api/v2/) with the query parameters. */
    override fun steamGridDb(path: String, query: List<Pair<String, String>>): ProxyResult {
        val key = keys.steamGridDbKey
        if (key.isNullOrBlank()) return ProxyResult(ProxyResult.Status.NOT_CONFIGURED, 503)
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrNull() ?: return ProxyResult(ProxyResult.Status.NOT_ALLOWED, 404)
        if (decoded.contains("..") || !SGDB_PATH.matches(decoded)) return ProxyResult(ProxyResult.Status.NOT_ALLOWED, 404)
        if (query.size > 10 || query.any { (k, v) -> k !in SGDB_QUERY_KEYS || !SGDB_QUERY_VALUE.matches(v) }) {
            return ProxyResult(ProxyResult.Status.NOT_ALLOWED, 400)
        }
        // Rebuilt from validated parts, every segment escaped again.
        val url = SGDB_BASE + decoded.split('/').joinToString("/") { escape(it) } +
            if (query.isEmpty()) "" else "?" + query.sortedBy { it.first }.joinToString("&") { "${it.first}=${escape(it.second)}" }
        val cacheKey = ResponseCache.key("sgdb", url)
        cache.get(cacheKey)?.let { return ProxyResult(ProxyResult.Status.OK, 200, it) }
        return send(Request.Builder().url(url).header("Authorization", "Bearer $key").build(), cacheKey)
    }

    /** IGDB POST [endpoint] with an Apicalypse query. */
    override fun igdb(endpoint: String, query: String): ProxyResult {
        if (!igdbConfigured) return ProxyResult(ProxyResult.Status.NOT_CONFIGURED, 503)
        if (endpoint !in IGDB_ENDPOINTS) return ProxyResult(ProxyResult.Status.NOT_ALLOWED, 404)
        if (query.toByteArray().size > MAX_IGDB_QUERY) return ProxyResult(ProxyResult.Status.NOT_ALLOWED, 413)
        val cacheKey = ResponseCache.key("igdb", endpoint, query)
        cache.get(cacheKey)?.let { return ProxyResult(ProxyResult.Status.OK, 200, it) }
        for (attempt in 0..1) {
            val token = igdbToken(forceNew = attempt > 0) ?: return ProxyResult(ProxyResult.Status.UPSTREAM_AUTH, 502)
            val request = Request.Builder().url(IGDB_BASE + endpoint)
                .header("Client-ID", keys.igdbClientId!!)
                .header("Authorization", "Bearer $token")
                .post(query.toRequestBody())
                .build()
            throttleIgdb()
            val result = send(request, cacheKey)
            if (result.status != ProxyResult.Status.UPSTREAM_AUTH || attempt > 0) return result
        }
        return ProxyResult(ProxyResult.Status.UPSTREAM_AUTH, 502)
    }

    private fun send(request: Request, cacheKey: String): ProxyResult = try {
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return ProxyResult(ProxyResult.Status.UPSTREAM_AUTH, 502)
            val body = response.body?.byteStream()?.use { readLimited(it, MAX_UPSTREAM) } ?: return ProxyResult(ProxyResult.Status.UPSTREAM_ERROR, 502)
            if (!response.isSuccessful) return ProxyResult(ProxyResult.Status.UPSTREAM_ERROR, if (response.code == 404) 404 else 502)
            if (isJson(body)) cache.put(cacheKey, body)
            ProxyResult(ProxyResult.Status.OK, 200, body)
        }
    } catch (e: java.net.SocketTimeoutException) {
        ProxyResult(ProxyResult.Status.UPSTREAM_ERROR, 504)
    } catch (e: IOException) {
        ProxyResult(ProxyResult.Status.UPSTREAM_ERROR, 502)
    }

    private fun igdbToken(forceNew: Boolean): String? = synchronized(igdbLock) {
        val id = keys.igdbClientId
        val secret = keys.igdbClientSecret
        if (id.isNullOrBlank() || secret.isNullOrBlank()) return null
        // New credentials invalidate the token.
        val forKey = sha256("$id\n$secret")
        if (!forceNew && igdbToken != null && igdbTokenFor == forKey && clock() < igdbTokenUntil) return igdbToken
        val request = Request.Builder().url(TWITCH_TOKEN)
            .post(FormBody.Builder().add("client_id", id).add("client_secret", secret).add("grant_type", "client_credentials").build())
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    igdbToken = null
                    return null
                }
                val obj = Json.parseToJsonElement(response.body!!.string()).jsonObject
                igdbToken = obj["access_token"]!!.jsonPrimitive.content
                igdbTokenUntil = clock() + (obj["expires_in"]!!.jsonPrimitive.long - 120) * 1000
                igdbTokenFor = forKey
                igdbToken
            }
        } catch (e: Exception) {
            null
        }
    }

    // IGDB allows 4 requests per second.
    private fun throttleIgdb() = synchronized(igdbLock) {
        val wait = igdbLast + 260 - clock()
        if (wait > 0) Thread.sleep(wait)
        igdbLast = clock()
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray? {
        val data = input.readNBytes(limit + 1)
        return if (data.size > limit) null else data
    }

    private fun isJson(body: ByteArray) = runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)) as JsonElement }.isSuccess

    private fun escape(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val MAX_IGDB_QUERY = 8 * 1024
        private const val MAX_UPSTREAM = 4 * 1024 * 1024
        private const val SGDB_BASE = "https://www.steamgriddb.com/api/v2/"
        private const val IGDB_BASE = "https://api.igdb.com/v4/"
        private const val TWITCH_TOKEN = "https://id.twitch.tv/oauth2/token"

        private val IGDB_ENDPOINTS = setOf(
            "games", "alternative_names", "game_localizations", "covers", "artworks", "screenshots", "platforms", "genres",
            "involved_companies", "companies", "franchises", "collections", "game_modes", "age_ratings", "search",
        )
        private val SGDB_QUERY_KEYS = setOf("dimensions", "types", "nsfw", "humor", "styles", "mimes", "epilepsy", "limit", "page")

        // search/autocomplete/<term>, <grids|heroes|logos|icons>/<game|steam>/<id>, games/id/<id>, games/steam/<id>
        private val SGDB_PATH = Regex("""^(search/autocomplete/[^/]{1,200}|(grids|heroes|logos|icons)/(game|steam)/\d{1,12}|games/(id|steam)/\d{1,12})$""")
        private val SGDB_QUERY_VALUE = Regex("""^[A-Za-z0-9_,\-|x.]{0,200}$""")

        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }
    }
}

/** Successful upstream answers shared by all devices (saves the providers' rate limits); one file per answer. */
class ResponseCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    init {
        dir.mkdirs()
        prune()
    }

    fun get(key: String): ByteArray? {
        val file = File(dir, key)
        if (!file.isFile || clock() - file.lastModified() > LIFETIME_MS) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun put(key: String, body: ByteArray) {
        runCatching {
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(body)
            tmp.setLastModified(clock())
            java.nio.file.Files.move(tmp.toPath(), File(dir, key).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val now = clock()
        files.filter { now - it.lastModified() > LIFETIME_MS || it.name.endsWith(".tmp") }.forEach { it.delete() }
        files.filter { it.exists() }.sortedByDescending { it.lastModified() }.drop(MAX_ENTRIES).forEach { it.delete() }
    }

    companion object {
        const val LIFETIME_MS = 7 * 86_400_000L
        private const val MAX_ENTRIES = 50_000

        fun key(provider: String, request: String, body: String? = null) = MetadataProxy.sha256("$provider\n$request\n${body.orEmpty()}")
    }
}
