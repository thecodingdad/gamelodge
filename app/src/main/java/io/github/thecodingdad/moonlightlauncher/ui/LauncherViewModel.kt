package io.github.thecodingdad.moonlightlauncher.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.CollectionEntity
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.PlaySessionEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.stream.LaunchState
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfiles
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A game running on a host right now (drives the resume card). */
data class RunningSession(val host: HostState, val entry: LibraryEntry?, val appName: String)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val c = application.container
    private val defaultProfileName = application.getString(R.string.profile_default)

    val settings: StateFlow<LauncherSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())

    val entries: StateFlow<List<LibraryEntry>> = c.library.entries

    val games: StateFlow<List<LibraryEntry>> = entries
        .map { list -> list.filter { it.item.kind == ItemKind.GAME } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val apps: StateFlow<List<LibraryEntry>> = entries
        .map { list -> list.filter { it.item.kind == ItemKind.APP } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hosts: StateFlow<Map<String, HostState>> = c.hosts.hosts

    val activeProfile: StateFlow<StreamProfile> = StreamProfiles.changes()
        .map { StreamProfiles.active(defaultProfileName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamProfile(null, defaultProfileName))

    val profiles: StateFlow<List<StreamProfile>> = StreamProfiles.changes()
        .map { StreamProfiles.all(defaultProfileName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val launchState: StateFlow<LaunchState> = c.launcher.state

    /** Sessions currently running on any host, matched to library entries where possible. */
    val runningSessions: StateFlow<List<RunningSession>> = combine(hosts, entries) { hostMap, list ->
        hostMap.values.filter { it.isStreaming }.map { host ->
            val entry = list.firstOrNull { e ->
                e.remoteTargets.any { it.hostUuid == host.uuid && it.appId == host.runningAppId }
            }
            val name = entry?.item?.title
                ?: c.hosts.appLists.value[host.uuid]?.firstOrNull { it.app.appId == host.runningAppId }?.app?.appName
                ?: ""
            RunningSession(host, entry, name)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Focus key ("row:itemId") to restore when returning to a list, e.g. back from details. */
    var lastFocusedKey: String? = null

    val allEntries: StateFlow<List<LibraryEntry>> =
        c.library.allEntries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sessions(itemId: String) = c.database.sessionDao().observeForItem(itemId)

    val finishedSessions: StateFlow<List<PlaySessionEntity>> =
        c.database.sessionDao().observeFinished().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Collections with their member item ids. */
    val collections: StateFlow<List<Pair<CollectionEntity, Set<String>>>> =
        combine(c.library.collections, c.library.memberships) { cols, refs ->
            val byCollection = refs.groupBy({ it.collectionId }, { it.itemId })
            cols.map { it to byCollection[it.id].orEmpty().toSet() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun createCollection(name: String, addItem: String? = null) {
        viewModelScope.launch {
            val id = c.library.createCollection(name)
            if (addItem != null) c.library.setInCollection(addItem, id, true)
        }
    }

    fun setInCollection(itemId: String, collectionId: String, member: Boolean) {
        viewModelScope.launch { c.library.setInCollection(itemId, collectionId, member) }
    }

    fun deleteCollection(collection: CollectionEntity) {
        viewModelScope.launch { c.library.deleteCollection(collection) }
    }

    /** Selected main tab; lives here so the Home button can reset it from the activity. */
    val tabState = androidx.compose.runtime.mutableIntStateOf(0)
    var tab: Int
        get() = tabState.intValue
        set(value) { tabState.intValue = value }

    /** Emits when the Home button is pressed while the launcher is the home app. */
    val homePressed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun launch(
        activity: Activity,
        entry: LibraryEntry,
        target: LaunchTargetEntity,
        override: StreamProfile? = null,
        quitRunning: Boolean = false,
        skipNetworkCheck: Boolean = false,
    ) {
        viewModelScope.launch { c.launcher.launch(activity, entry, target, override, quitRunning, skipNetworkCheck) }
    }

    fun dismissLaunch() = c.launcher.dismiss()

    fun setActiveProfile(profile: StreamProfile) = StreamProfiles.setActive(profile.uuid)

    fun setFavorite(entry: LibraryEntry, favorite: Boolean) {
        viewModelScope.launch { c.library.setFavorite(entry.item.id, favorite) }
    }

    fun setHidden(entry: LibraryEntry, hidden: Boolean) {
        viewModelScope.launch { c.library.setHidden(entry.item.id, hidden) }
    }

    fun setKind(entry: LibraryEntry, kind: ItemKind) {
        viewModelScope.launch { c.library.setKind(entry.item.id, kind) }
    }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun refreshHosts() = c.hosts.refreshAppLists()

    fun entry(id: String) = c.library.entry(id)
}
