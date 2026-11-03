package io.github.thecodingdad.gamelodge.data.companion

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The host games of the paired companions: what each entry is, where it comes from and its
 * metadata. Answers are cached per companion (ETag); while a companion is offline (host off) the
 * last list stays in use.
 */
class CompanionFeed(
    filesDir: File,
    private val servers: ServerRepository,
    /** False for a companion the user switched off in the metadata sources. */
    private val isEnabled: (PairedServer) -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val imageDir = File(filesDir, "companion-images")
    private val cache = ConcurrentHashMap<String, Pair<String?, HostGamesList>>() // server id → (etag, list)

    /** App UUID (upper case) → host game, for the host's companion; null when none is paired. */
    suspend fun games(hostUuid: String): Map<String, HostGame>? = withContext(Dispatchers.IO) {
        val server = companionFor(hostUuid) ?: return@withContext null
        val list = fetch(server) ?: cache[server.id]?.second ?: return@withContext null
        list.games.filter { it.appUuid != null }.associateBy { it.appUuid!!.uppercase() }
    }

    /**
     * Like [games], but only when the list changed since the last [changedGames] (null otherwise).
     * Other fetches ([games]) don't count: they would hide a change from this check.
     */
    suspend fun changedGames(hostUuid: String): Map<String, HostGame>? = withContext(Dispatchers.IO) {
        val server = companionFor(hostUuid) ?: return@withContext null
        val list = fetch(server) ?: return@withContext null
        val tag = cache[server.id]?.first
        val before = applied.put(server.id, tag ?: "")
        if (before != null && tag != null && before == tag) return@withContext null
        list.games.filter { it.appUuid != null }.associateBy { it.appUuid!!.uppercase() }
    }

    private val applied = ConcurrentHashMap<String, String>() // server id → etag last seen by changedGames

    private fun companionFor(hostUuid: String): PairedServer? = servers.companionFor(hostUuid)?.takeIf(isEnabled)

    private fun fetch(server: PairedServer): HostGamesList? {
        val cached = cache[server.id]
        return runCatching {
            val client = servers.client(server)
            client.http.newCall(
                okhttp3.Request.Builder().url(server.baseUrl + "/v1/hostgames")
                    .apply { cached?.first?.let { header("If-None-Match", it) } }.build(),
            ).execute().use { r ->
                when {
                    r.code == 304 && cached != null -> cached.second
                    r.isSuccessful -> json.decodeFromString<HostGamesList>(r.body?.string().orEmpty())
                        .also { cache[server.id] = r.header("ETag") to it }
                    else -> null
                }
            }
        }.onFailure { Log.d("Companion ${server.name} not reachable: ${it.message}") }.getOrNull()
    }

    /**
     * Artwork reference as the launcher stores it: CDN URLs stay, "image:<id>" is downloaded from
     * the companion (mutual TLS) into a local file.
     */
    suspend fun artwork(hostUuid: String, ref: String?): String? = withContext(Dispatchers.IO) {
        if (ref == null) return@withContext null
        if (ref.startsWith("https://")) return@withContext ref
        val id = ref.removePrefix("image:").takeIf { ref.startsWith("image:") } ?: return@withContext null
        if (id.contains("..") || !id.all { it.isLetterOrDigit() || it in "-_." }) return@withContext null
        val server = companionFor(hostUuid) ?: return@withContext null
        val file = File(File(imageDir, server.id), "$id.png")
        if (file.exists()) return@withContext file.absolutePath
        runCatching {
            servers.client(server).rawGet("/v1/images/$id").use { r ->
                if (!r.isSuccessful) return@use null
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$id.tmp")
                r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                // A new version of an image ("<name>.v<time>"): the older ones go.
                val base = id.replace(VERSION_SUFFIX, "")
                file.parentFile?.listFiles { f -> f.name != file.name && (f.name == "$base.png" || f.name.startsWith("$base.v")) }?.forEach { it.delete() }
                file.absolutePath
            }
        }.getOrNull()
    }

    /**
     * [current] is a downloaded companion image and [ref] another version of the same image: it
     * is to be replaced (the same source, so no own choice is overwritten).
     */
    fun isOlderVersion(current: String?, ref: String?): Boolean {
        if (current == null || ref == null || !ref.startsWith("image:")) return false
        val file = File(current)
        if (file.parentFile?.parentFile?.absoluteFile != imageDir.absoluteFile) return false
        val name = file.name.removeSuffix(".png")
        val id = ref.removePrefix("image:")
        return name != id && name.replace(VERSION_SUFFIX, "") == id.replace(VERSION_SUFFIX, "")
    }

    /** Play data changes of the host's GameLodge after [after]; null when not reachable. */
    suspend fun pullPlayData(hostUuid: String, after: Long): PlayDataChanges? = withContext(Dispatchers.IO) {
        val server = companionFor(hostUuid) ?: return@withContext null
        runCatching {
            servers.client(server).rawGet("/v1/playdata?after=$after").use { r ->
                if (r.isSuccessful) json.decodeFromString<PlayDataChanges>(r.body?.string().orEmpty()) else null
            }
        }.onFailure { Log.d("Play data of ${server.name} not read: ${it.message}") }.getOrNull()
    }

    /** Sends this device's play data changes to the host's GameLodge. */
    suspend fun pushPlayData(hostUuid: String, changes: PlayDataChanges): Boolean = withContext(Dispatchers.IO) {
        val server = companionFor(hostUuid) ?: return@withContext false
        runCatching {
            servers.client(server).rawPost("/v1/playdata", json.encodeToString(changes), "application/json").use { r ->
                if (!r.isSuccessful) Log.w("Play data refused by ${server.name}: HTTP ${r.code}")
                r.isSuccessful
            }
        }.onFailure { Log.d("Play data not sent to ${server.name}: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Shares the SteamGridDB / IGDB keys with the host's GameLodge, where it allows this device (else it
     * answers 403): sends this device's keys when [send], then takes the newer ones from there.
     */
    suspend fun exchangeKeys(hostUuid: String, keys: io.github.thecodingdad.gamelodge.data.sync.KeySync, send: Boolean) = withContext(Dispatchers.IO) {
        val server = servers.companionFor(hostUuid) ?: return@withContext
        runCatching {
            val client = servers.client(server)
            val local = keys.local()
            if (send && !local.isEmpty) client.rawPut("/v1/keys", json.encodeToString(local)).close()
            client.rawGet("/v1/keys").use { r ->
                if (r.isSuccessful) keys.merge(json.decodeFromString<SharedKeys>(r.body?.string().orEmpty()))
            }
        }.onFailure { Log.d("Keys not shared with ${server.name}: ${it.message}") }
    }

    /** True when the host's companion is a GameLodge that shares play data (paired, both permissions). */
    fun sharesPlayData(hostUuid: String): Boolean =
        companionFor(hostUuid)?.permissions?.let { "hostgames" in it && "hostgames.write" in it } == true

    /** Sends a correction for the host entry [appUuid] to the host's companion. */
    suspend fun correct(hostUuid: String, appUuid: String, correction: HostGameCorrection): Boolean = withContext(Dispatchers.IO) {
        val server = companionFor(hostUuid) ?: return@withContext false
        val game = games(hostUuid)?.get(appUuid.uppercase()) ?: return@withContext false
        if (!server.permissions.contains("hostgames.write")) return@withContext false
        runCatching {
            servers.client(server).rawPut("/v1/hostgames/${java.net.URLEncoder.encode(game.id, "UTF-8").replace("+", "%20")}/metadata", json.encodeToString(correction)).use { r ->
                if (r.isSuccessful) cache.remove(server.id) // the next sync reads the changed list
                r.isSuccessful
            }
        }.onFailure { Log.w("Correction for ${game.name} not sent", it) }.getOrDefault(false)
    }

    private companion object {
        val VERSION_SUFFIX = Regex("""\.v\d+$""")
    }

    /** Forgets cached lists and images of a server (removed). */
    fun forget(serverId: String) {
        cache.remove(serverId)
        File(imageDir, serverId).deleteRecursively()
    }
}
