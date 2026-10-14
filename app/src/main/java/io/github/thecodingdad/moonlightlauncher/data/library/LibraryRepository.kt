package io.github.thecodingdad.moonlightlauncher.data.library

import androidx.room.withTransaction
import io.github.thecodingdad.moonlightlauncher.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.KindSource
import io.github.thecodingdad.moonlightlauncher.data.db.MetaField
import io.github.thecodingdad.moonlightlauncher.data.db.MetadataState
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.PlatformSource
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
import io.github.thecodingdad.moonlightlauncher.data.local.LocalAppScanner
import io.github.thecodingdad.moonlightlauncher.data.remote.BoxArtCache
import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.data.remote.RemoteApp
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Image hosts the companion accepts in corrections (Companion.Protocol ApiServer.ArtworkHosts). */
private val ARTWORK_HOSTS = listOf("steamgriddb.com", "images.igdb.com")

/** Entry point for everything library related: keeps sources in sync and exposes entries. */
class LibraryRepository(
    private val db: LauncherDatabase,
    private val sync: LibrarySync,
    private val localApps: LocalAppScanner,
    private val hosts: HostRepository,
    private val boxArt: BoxArtCache,
    private val scope: CoroutineScope,
    private val companion: io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed? = null,
) {
    private val dao = db.libraryDao()

    /** All visible entries (games and apps), sorted by title. */
    val entries: StateFlow<List<LibraryEntry>> =
        dao.observeVisible().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Including hidden items, for the "hidden" filter and settings. */
    val allEntries: Flow<List<LibraryEntry>> = dao.observeAll()

    fun entry(id: String): Flow<LibraryEntry?> = dao.observeEntry(id)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch(Dispatchers.IO) {
            localApps.packageChanges()
                .onStart { emit(Unit) }
                .debounce(500)
                .collect { syncLocal() }
        }
        scope.launch(Dispatchers.IO) {
            var previous = emptyMap<String, List<RemoteApp>>()
            hosts.appLists.collect { lists ->
                lists.filter { (uuid, apps) -> previous[uuid] !== apps }
                    .forEach { (uuid, apps) -> syncHost(uuid, apps) }
                previous = lists
            }
        }
    }

    suspend fun syncLocal() {
        val apps = localApps.scan()
        sync.syncLocal(
            apps.map {
                SourceEntry(
                    targetId = SourceEntry.localId(it.packageName),
                    type = TargetType.LOCAL,
                    name = it.label,
                    packageName = it.packageName,
                    androidGameFlag = it.isGame,
                )
            },
        )
        Log.d("Local sync: ${apps.size} apps")
        suggestPlatforms()
    }

    /**
     * Games found in the ROM folders. The folder decides the platform (trusted like the companion
     * data), so a ROM merges only with entries of the same platform.
     */
    suspend fun syncRoms(roms: List<io.github.thecodingdad.moonlightlauncher.data.emulation.RomFile>, keepUnder: List<String> = emptyList()) {
        val entries = roms.map { rom ->
            SourceEntry(
                targetId = SourceEntry.romId(rom.uri),
                type = TargetType.ROM,
                name = rom.name,
                romUri = rom.uri,
                romPath = rom.path,
                romPlatform = rom.platform.id,
                platform = rom.platform.displayName,
                boxArt = rom.image,
            )
        }
        sync.syncRoms(entries, keepUnder)
        val byTarget = entries.associateBy { it.targetId }
        for (target in dao.getAllTargets().filter { it.type == TargetType.ROM }) {
            val platform = byTarget[target.id]?.platform ?: continue
            val item = dao.getItem(target.itemId) ?: continue
            if (PlatformResolver.mayReplace(item, PlatformSource.COMPANION, platform)) dao.setPlatform(item.id, platform, PlatformSource.COMPANION)
        }
        Log.d("ROM sync: ${roms.size} games")
    }

    /** Re-applies a host's app list, e.g. after a companion was paired. */
    suspend fun resyncHost(hostUuid: String) {
        hosts.appLists.value[hostUuid]?.let { syncHost(hostUuid, it) }
    }

    private suspend fun syncHost(hostUuid: String, apps: List<RemoteApp>) {
        // Optional: the host's paired companion knows what each entry is (game/app, platform,
        // store, groups, installed) and brings its metadata.
        val feed = companion?.games(hostUuid)
        sync.syncHost(
            hostUuid,
            apps.map { remote ->
                val info = remote.app.appUUID?.uppercase()?.let { feed?.get(it) }
                SourceEntry(
                    targetId = SourceEntry.remoteId(hostUuid, remote.app.appId),
                    type = TargetType.REMOTE,
                    name = remote.app.appName,
                    hostUuid = hostUuid,
                    hostName = remote.hostName,
                    appId = remote.app.appId,
                    appUuid = remote.app.appUUID,
                    hdrSupported = remote.app.isHdrSupported,
                    companionGame = info?.isGame,
                    installed = info?.installed ?: true,
                )
            },
        )
        Log.d("Host sync ${apps.firstOrNull()?.hostName}: ${apps.size} apps (${feed?.size ?: 0} from the companion)")
        feed?.let { applyCompanion(hostUuid, it) }
        suggestPlatforms()
        fetchBoxArt(hostUuid, apps)
    }

    /**
     * Groups, store, platform and metadata of each host entry the companion knows. Items whose
     * metadata was chosen here by hand (locked) keep it; hand-edited fields always stay.
     */
    private suspend fun applyCompanion(hostUuid: String, games: Map<String, io.github.thecodingdad.moonlightlauncher.data.companion.HostGame>) {
        val feed = companion ?: return
        for (target in dao.getAllTargets().filter { it.hostUuid == hostUuid }) {
            val game = target.appUuid?.uppercase()?.let { games[it] } ?: continue
            val item = dao.getItem(target.itemId) ?: continue
            val groups = game.collections.joinToString(", ").ifEmpty { null }
            if (item.playniteCategories != groups || item.playnitePlugin != game.sourceLabel) {
                dao.setPlayniteInfo(item.id, groups, game.sourceLabel)
            }
            val platform = game.platform?.let(PlatformResolver::normalizeIgdb)
            if (PlatformResolver.mayReplace(item, PlatformSource.COMPANION, platform)) {
                dao.setPlatform(item.id, platform, PlatformSource.COMPANION)
            }
            val hasMetadata = game.igdbId != null || game.summary != null || game.cover != null
            if (!hasMetadata || item.metadataState == MetadataState.LOCKED) continue
            val fresh = dao.getItem(item.id) ?: continue
            val m = fresh.manual
            fun list(values: List<String>) = values.joinToString(", ").ifEmpty { null }
            val updated = fresh.copy(
                igdbId = game.igdbId ?: fresh.igdbId,
                sgdbId = game.steamGridDbId ?: fresh.sgdbId,
                description = if (MetaField.DESCRIPTION in m) fresh.description else game.summary ?: fresh.description,
                genres = if (MetaField.GENRES in m) fresh.genres else list(game.genres) ?: fresh.genres,
                developer = if (MetaField.DEVELOPER in m) fresh.developer else list(game.developers) ?: fresh.developer,
                publisher = if (MetaField.PUBLISHER in m) fresh.publisher else list(game.publishers) ?: fresh.publisher,
                series = if (MetaField.SERIES in m) fresh.series else list(game.series) ?: fresh.series,
                releaseDate = if (MetaField.RELEASE in m) fresh.releaseDate else game.releaseDate?.times(1000) ?: fresh.releaseDate,
                releaseYear = if (MetaField.RELEASE in m) fresh.releaseYear
                    else game.releaseDate?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).year } ?: fresh.releaseYear,
                rating = if (MetaField.RATING in m) fresh.rating else game.rating?.toFloat() ?: fresh.rating,
                coverUrl = feed.artwork(hostUuid, game.cover) ?: fresh.coverUrl,
                heroUrl = feed.artwork(hostUuid, game.hero) ?: fresh.heroUrl,
                logoUrl = feed.artwork(hostUuid, game.logo) ?: fresh.logoUrl,
                iconUrl = feed.artwork(hostUuid, game.icon) ?: fresh.iconUrl,
                metadataState = MetadataState.OK,
                matchConfidence = 1f,
            )
            if (updated != fresh) dao.upsertItem(updated)
        }
    }

    /**
     * A correction made here (match, artwork, edited fields, game/app) goes to the companion of
     * every host this item is on, so the other launchers get it too.
     */
    suspend fun pushCorrection(itemId: String) {
        val feed = companion ?: return
        val item = dao.getItem(itemId) ?: return
        val targets = dao.getTargetsForItem(itemId).filter { it.type == TargetType.REMOTE && it.hostUuid != null && it.appUuid != null }
        if (targets.isEmpty()) return
        // The companion only takes artwork from the providers' image servers (and no port).
        fun https(url: String?) = url?.takeIf { u ->
            val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return@takeIf false
            val host = uri.host?.lowercase() ?: return@takeIf false
            uri.scheme == "https" && uri.port == -1 && u.length <= 1024 &&
                ARTWORK_HOSTS.any { host == it || host.endsWith(".$it") }
        }
        fun split(value: String?) = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val correction = io.github.thecodingdad.moonlightlauncher.data.companion.HostGameCorrection(
            igdbId = item.igdbId,
            steamGridDbId = item.sgdbId,
            name = item.title.takeIf { MetaField.TITLE in item.manual },
            cover = https(item.coverUrl),
            hero = https(item.heroUrl),
            logo = https(item.logoUrl),
            icon = https(item.iconUrl),
            summary = item.description,
            genres = split(item.genres),
            developers = split(item.developer),
            publishers = split(item.publisher),
            releaseDate = item.releaseDate?.div(1000),
            kind = if (item.kindSource == KindSource.MANUAL) (if (item.kind == ItemKind.APP) "app" else "game") else null,
        )
        targets.forEach { feed.correct(it.hostUuid!!, it.appUuid!!, correction) }
    }

    /** Items nobody knows a platform for get a suggestion from how they are launched. */
    private suspend fun suggestPlatforms() {
        dao.observeAll().first().filter { it.item.platform == null }.forEach { entry ->
            PlatformResolver.suggest(entry)?.let { dao.setPlatform(entry.item.id, it, PlatformSource.SUGGESTED) }
        }
    }

    /** Manual platform choice; null returns the item to automatic detection. */
    suspend fun setPlatform(id: String, platform: String?) {
        if (platform != null) {
            dao.setPlatform(id, platform, PlatformSource.MANUAL)
        } else {
            dao.setPlatform(id, null, null)
            suggestPlatforms()
        }
    }

    private suspend fun fetchBoxArt(hostUuid: String, apps: List<RemoteApp>) {
        val host = hosts.hosts.value[hostUuid]?.takeIf { it.status == HostStatus.ONLINE } ?: return
        val http = runCatching { hosts.http(host) }.getOrNull() ?: return
        val targets = dao.getAllTargets().filter { it.hostUuid == hostUuid }.associateBy { it.id }
        val updated = apps.mapNotNull { remote ->
            val target = targets[SourceEntry.remoteId(hostUuid, remote.app.appId)] ?: return@mapNotNull null
            if (target.boxArtPath != null && java.io.File(target.boxArtPath).exists()) return@mapNotNull null
            val file = boxArt.fetch(http, hostUuid, remote.app) ?: return@mapNotNull null
            target.copy(boxArtPath = file.absolutePath)
        }
        if (updated.isNotEmpty()) dao.upsertTargets(updated)
    }

    suspend fun removeHost(hostUuid: String) {
        sync.removeHost(hostUuid)
        boxArt.deleteForHost(hostUuid)
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun setHidden(id: String, hidden: Boolean) = dao.setHidden(id, hidden)

    val collections = db.collectionDao().observeCollections()
    val memberships = db.collectionDao().observeMemberships()

    suspend fun setInCollection(itemId: String, collectionId: String, member: Boolean) {
        val dao = db.collectionDao()
        if (member) dao.addItem(ItemCollectionCrossRef(itemId, collectionId, dao.maxPosition(collectionId) + 1))
        else dao.removeItem(ItemCollectionCrossRef(itemId, collectionId))
    }

    /** Stores the manual order of a group's members. */
    suspend fun reorderCollection(collectionId: String, itemIds: List<String>) = db.withTransaction {
        itemIds.forEachIndexed { index, id -> db.collectionDao().setPosition(id, collectionId, index) }
    }

    /** User correction "this is a game / an app"; survives every future sync. */
    suspend fun setKind(id: String, kind: ItemKind) {
        dao.setKind(id, kind, KindSource.MANUAL)
        pushCorrection(id)
    }
}
