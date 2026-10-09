package io.github.thecodingdad.moonlightlauncher.data.library

import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.KindSource
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
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
    }

    private suspend fun syncHost(hostUuid: String, apps: List<RemoteApp>) {
        sync.syncHost(
            hostUuid,
            apps.map { remote ->
                SourceEntry(
                    targetId = SourceEntry.remoteId(hostUuid, remote.app.appId),
                    type = TargetType.REMOTE,
                    name = remote.app.appName,
                    hostUuid = hostUuid,
                    hostName = remote.hostName,
                    appId = remote.app.appId,
                    appUuid = remote.app.appUUID,
                    hdrSupported = remote.app.isHdrSupported,
                )
            },
        )
        Log.d("Host sync ${apps.firstOrNull()?.hostName}: ${apps.size} apps")
        fetchBoxArt(hostUuid, apps)
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

    /** User correction "this is a game / an app"; survives every future sync. */
    suspend fun setKind(id: String, kind: ItemKind) = dao.setKind(id, kind, KindSource.MANUAL)
}
