package io.github.thecodingdad.moonlightlauncher.data.library

import androidx.room.withTransaction
import io.github.thecodingdad.moonlightlauncher.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.KindSource
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

/** Entry point for everything library related: keeps sources in sync and exposes entries. */
class LibraryRepository(
    private val db: LauncherDatabase,
    private val sync: LibrarySync,
    private val localApps: LocalAppScanner,
    private val hosts: HostRepository,
    private val boxArt: BoxArtCache,
    private val scope: CoroutineScope,
    private val hostApi: io.github.thecodingdad.moonlightlauncher.data.remote.HostApiClient? = null,
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

    /** Re-applies a host's app list, e.g. after its Vibepollo access changed. */
    suspend fun resyncHost(hostUuid: String) {
        hosts.appLists.value[hostUuid]?.let { syncHost(hostUuid, it) }
    }

    private suspend fun syncHost(hostUuid: String, apps: List<RemoteApp>) {
        // Optional: the host API carries the companion's data (game/app, platform, store, groups,
        // installed) for the entries it manages.
        val api = hostApi?.cached(hostUuid, apps.map { it.app.appUUID })
        sync.syncHost(
            hostUuid,
            apps.map { remote ->
                val info = api?.infoForApp(remote.app.appUUID)
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
        Log.d("Host sync ${apps.firstOrNull()?.hostName}: ${apps.size} apps (${api?.companion?.size ?: 0} from the companion)")
        api?.let { applyCompanionInfo(hostUuid, it) }
        suggestPlatforms()
        fetchBoxArt(hostUuid, apps)
    }

    /** Groups, store name and platform from the companion data of each managed entry. */
    private suspend fun applyCompanionInfo(hostUuid: String, result: io.github.thecodingdad.moonlightlauncher.data.remote.HostApiClient.Result) {
        if (result.companion.isEmpty()) return
        for (target in dao.getAllTargets().filter { it.hostUuid == hostUuid }) {
            val info = result.infoForApp(target.appUuid) ?: continue
            val item = dao.getItem(target.itemId) ?: continue
            val groups = info.collections.joinToString(", ").ifEmpty { null }
            if (item.playniteCategories != groups || item.playnitePlugin != info.sourceLabel) {
                dao.setPlayniteInfo(item.id, groups, info.sourceLabel)
            }
            val platform = info.platform?.let(PlatformResolver::normalizeIgdb)
            if (PlatformResolver.mayReplace(item, PlatformSource.COMPANION, platform)) {
                dao.setPlatform(item.id, platform, PlatformSource.COMPANION)
            }
        }
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
    suspend fun setKind(id: String, kind: ItemKind) = dao.setKind(id, kind, KindSource.MANUAL)
}
