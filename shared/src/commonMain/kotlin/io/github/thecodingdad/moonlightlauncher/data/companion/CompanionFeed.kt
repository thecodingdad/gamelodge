package io.github.thecodingdad.moonlightlauncher.data.companion

import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One game or app on the host as the companion delivers it (Companion.Protocol HostGame). */
@Serializable
data class HostGame(
    val id: String,
    val appUuid: String? = null,
    val name: String,
    val kind: String = "game",
    val platform: String? = null,
    val source: String? = null,
    val installed: Boolean = true,
    val emulator: String? = null,
    val collections: List<String> = emptyList(),
    /** Absolute URL or "image:<id>" (served by the companion). */
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    /** Animated cover / background (WebP or GIF), when there is one. */
    val animatedCover: String? = null,
    val animatedHero: String? = null,
    val igdbId: Long? = null,
    val steamGridDbId: Long? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val series: List<String> = emptyList(),
    val gameModes: List<String> = emptyList(),
    /** Unix seconds. */
    val releaseDate: Long? = null,
    val rating: Double? = null,
    val updated: Long = 0,
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

@Serializable
data class HostGamesList(val hostId: String? = null, val games: List<HostGame> = emptyList())

/**
 * A correction for the companion: only the fields set are changed and count as picked by hand;
 * [reset] hands fields back to the automatic choice (Companion.Protocol HostGameCorrection).
 */
@Serializable
data class HostGameCorrection(
    val igdbId: Long? = null,
    val steamGridDbId: Long? = null,
    val name: String? = null,
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    val animatedCover: String? = null,
    val animatedHero: String? = null,
    val summary: String? = null,
    val genres: List<String>? = null,
    val developers: List<String>? = null,
    val publishers: List<String>? = null,
    val releaseDate: Long? = null,
    val kind: String? = null,
    /** "name", "cover", "hero", "logo", "icon", "animatedCover", "animatedHero", "summary", "genres", "developers", "publishers", "release". */
    val reset: List<String>? = null,
    /** Fields chosen by hand to stay empty ("animatedCover", "animatedHero"). */
    val clear: List<String>? = null,
)

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
                file.absolutePath
            }
        }.getOrNull()
    }

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

    /** Forgets cached lists and images of a server (removed). */
    fun forget(serverId: String) {
        cache.remove(serverId)
        File(imageDir, serverId).deleteRecursively()
    }
}
