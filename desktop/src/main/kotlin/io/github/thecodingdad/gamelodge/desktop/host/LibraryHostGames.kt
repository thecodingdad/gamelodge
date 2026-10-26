package io.github.thecodingdad.gamelodge.desktop.host

import io.github.thecodingdad.gamelodge.host.*

import io.github.thecodingdad.gamelodge.data.companion.HostGame
import io.github.thecodingdad.gamelodge.data.companion.HostGameCorrection
import io.github.thecodingdad.gamelodge.data.companion.HostGamesList
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.KindSource
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.MetaField
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.library.TitleNormalizer
import io.github.thecodingdad.gamelodge.desktop.host.sync.AppBuilder
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostSync
import io.github.thecodingdad.gamelodge.desktop.host.sync.SyncEntry
import io.github.thecodingdad.gamelodge.metadata.ArtworkKind
import io.github.thecodingdad.gamelodge.metadata.MetadataCandidate
import io.github.thecodingdad.gamelodge.metadata.MetadataService
import io.github.thecodingdad.gamelodge.metadata.MetadataSource
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The PC's games as the launcher knows them (names, kinds and metadata chosen here), for the
 * sync to the streaming host and for paired launchers (/v1/hostgames). Corrections from paired
 * launchers are applied like edits made in this launcher.
 */
class LibraryHostGames(
    private val db: LauncherDatabase,
    /** The scanned game behind a local target (this PC's stores only). */
    private val localEntry: (String) -> io.github.thecodingdad.gamelodge.desktop.games.GameEntry?,
    private val metadata: () -> MetadataService,
    private val settings: () -> HostSettings,
    private val sync: () -> HostSync?,
    private val http: OkHttpClient,
    /** A game's start actions (paired launchers pick one with "start with…"). */
    private val actions: (String) -> List<io.github.thecodingdad.gamelodge.stream.StartAction> = { emptyList() },
) : HostGamesSource {
    private val dao get() = db.libraryDao()

    @Volatile private var cachedHostId: String? = null
    @Volatile private var hostIdAt = 0L

    /** The streaming host's unique id (its serverinfo, no pairing needed), re-read every few minutes. */
    override val hostId: String?
        get() {
            val now = System.currentTimeMillis()
            if (now - hostIdAt > 5 * 60_000L || cachedHostId == null && now - hostIdAt > 30_000L) {
                hostIdAt = now
                readHostId()?.let { cachedHostId = it }
            }
            return cachedHostId
        }

    private fun readHostId(): String? = runCatching {
        val host = URI(settings().hostUrl).host ?: return null
        val name = if (host.contains(':')) "[$host]" else host
        val client = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("http://$name:47989/serverinfo").build()).execute().use { r ->
            Regex("<uniqueid>\\s*([^<]+?)\\s*</uniqueid>").find(r.body?.string().orEmpty())?.groupValues?.get(1)
        }
    }.getOrNull()

    /**
     * The library's PC games that belong on the host: not hidden, startable (or installable when
     * wanted). Only games of this PC: games of other streaming hosts never go to the host, that
     * would stream a stream.
     */
    suspend fun syncEntries(): List<Pair<SyncEntry, LibraryItemEntity>> {
        val includeOwned = settings().syncNotInstalled
        val items = dao.getAllItems().associateBy { it.id }
        return dao.getAllTargets().filter { it.type == TargetType.LOCAL }.mapNotNull { t ->
            val entry = t.packageName?.let(localEntry) ?: return@mapNotNull null
            val item = items[t.itemId]?.takeIf { !it.hidden } ?: return@mapNotNull null
            val startable = if (entry.installed) !entry.launch.isNullOrBlank() else includeOwned && !entry.install.isNullOrBlank()
            if (!startable) return@mapNotNull null
            // The launcher's cover first, then the store's.
            SyncEntry(entry, item.title, item.kind == ItemKind.GAME, listOfNotNull(item.coverUrl) + entry.covers) to item
        }.distinctBy { it.first.entry.id }
    }

    override fun games(): HostGamesList = runBlocking {
        val games = syncEntries().map { (s, item) -> toHostGame(s, item) }
        HostGamesList(hostId, games)
    }

    private fun toHostGame(s: SyncEntry, item: LibraryItemEntity): HostGame {
        val e = s.entry
        val png = sync()?.coverFile(HostSync.fileName(e.id))
        fun web(url: String?) = url?.takeIf { it.startsWith("https://") }
        fun list(value: String?) = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return HostGame(
            id = e.id,
            appUuid = AppBuilder.uuid(e.id),
            name = item.title,
            kind = if (item.kind == ItemKind.GAME) "game" else "app",
            platform = e.platform,
            source = e.source,
            installed = e.installed,
            cover = if (png != null) "image:${HostSync.fileName(e.id)}" else web(item.coverUrl) ?: e.covers.firstNotNullOfOrNull { web(it) },
            hero = web(item.heroUrl),
            logo = web(item.logoUrl),
            icon = web(item.iconUrl),
            animatedCover = web(item.animatedCoverUrl),
            animatedHero = web(item.animatedHeroUrl),
            igdbId = item.igdbId,
            steamGridDbId = item.sgdbId,
            summary = item.description,
            genres = list(item.genres),
            developers = list(item.developer),
            publishers = list(item.publisher),
            series = list(item.series),
            gameModes = list(item.features),
            releaseDate = item.releaseDate?.let { it / 1000 },
            rating = item.rating?.toDouble(),
            communityRating = item.communityRating?.toDouble(),
            actions = actions(e.id).map { io.github.thecodingdad.gamelodge.data.companion.HostGameAction(it.id, it.name) },
            defaultAction = actions(e.id).firstOrNull { it.isDefault }?.id,
        )
    }

    override fun image(id: String): Pair<ByteArray, String>? = sync()?.coverFile(id)?.let { it.readBytes() to "image/png" }

    override fun correct(gameId: String, correction: HostGameCorrection, deviceName: String): Boolean = runBlocking {
        val itemId = dao.getAllTargets().firstOrNull { it.type == TargetType.LOCAL && it.packageName == gameId }?.itemId ?: return@runBlocking false
        val item = dao.getItem(itemId) ?: return@runBlocking false
        Log.i("Correction of $gameId from $deviceName")
        val m = metadata()
        val c = correction
        c.igdbId?.takeIf { it != item.igdbId }?.let { m.applyCandidate(itemId, MetadataCandidate(MetadataSource.IGDB, it, c.name ?: item.title, null, null, 1f)) }
        c.steamGridDbId?.let { id -> m.edit(itemId, null) { it.copy(sgdbId = id) } }
        c.name?.let { name -> m.edit(itemId, MetaField.TITLE) { it.copy(title = name, normalizedTitle = TitleNormalizer.normalize(name)) } }
        c.summary?.let { v -> m.edit(itemId, MetaField.DESCRIPTION) { it.copy(description = v) } }
        c.genres?.let { v -> m.edit(itemId, MetaField.GENRES) { it.copy(genres = v.joinToString(", ").ifEmpty { null }) } }
        c.developers?.let { v -> m.edit(itemId, MetaField.DEVELOPER) { it.copy(developer = v.joinToString(", ").ifEmpty { null }) } }
        c.publishers?.let { v -> m.edit(itemId, MetaField.PUBLISHER) { it.copy(publisher = v.joinToString(", ").ifEmpty { null }) } }
        c.releaseDate?.let { v ->
            m.edit(itemId, MetaField.RELEASE) { it.copy(releaseDate = v * 1000, releaseYear = Instant.ofEpochSecond(v).atOffset(ZoneOffset.UTC).year) }
        }
        val images = mapOf(
            ArtworkKind.COVER to c.cover, ArtworkKind.HERO to c.hero, ArtworkKind.LOGO to c.logo, ArtworkKind.ICON to c.icon,
            ArtworkKind.ANIMATED_COVER to c.animatedCover, ArtworkKind.ANIMATED_HERO to c.animatedHero,
        )
        images.forEach { (kind, url) -> url?.let { m.setOwnArtwork(itemId, kind, it) } }
        c.clear.orEmpty().forEach { field -> ArtworkKind.entries.firstOrNull { it.correction == field }?.let { m.clearArtwork(itemId, it) } }
        c.kind?.let { dao.setKind(itemId, if (it == "app") ItemKind.APP else ItemKind.GAME, KindSource.MANUAL) }
        c.reset.orEmpty().forEach { field ->
            val kind = ArtworkKind.entries.firstOrNull { it.correction == field }
            if (kind != null) {
                m.resetArtwork(itemId, kind)
            } else {
                when (field) {
                    "name" -> MetaField.TITLE
                    "summary" -> MetaField.DESCRIPTION
                    "genres" -> MetaField.GENRES
                    "developers" -> MetaField.DEVELOPER
                    "publishers" -> MetaField.PUBLISHER
                    "release" -> MetaField.RELEASE
                    else -> null
                }?.let { m.resetField(itemId, it) }
            }
        }
        true
    }
}
