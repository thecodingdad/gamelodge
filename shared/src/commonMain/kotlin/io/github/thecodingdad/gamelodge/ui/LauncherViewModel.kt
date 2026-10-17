package io.github.thecodingdad.gamelodge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thecodingdad.gamelodge.LauncherContainer
import io.github.thecodingdad.gamelodge.data.db.CollectionEntity
import io.github.thecodingdad.gamelodge.data.db.FilterEntity
import io.github.thecodingdad.gamelodge.data.db.FilterScope
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.library.FilterEngine
import io.github.thecodingdad.gamelodge.data.library.FilterPlace
import io.github.thecodingdad.gamelodge.data.library.HomeRowOrder
import io.github.thecodingdad.gamelodge.data.library.FilterRules
import io.github.thecodingdad.gamelodge.data.library.ResolvedFilter
import io.github.thecodingdad.gamelodge.data.db.PlaySessionEntity
import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.stream.LaunchState
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

/** A game running on a host right now (drives the resume card). */
data class RunningSession(val host: HostState, val entry: LibraryEntry?, val appName: String)

class LauncherViewModel(private val c: LauncherContainer) : ViewModel() {
    private val defaultProfileName = kotlinx.coroutines.runBlocking { org.jetbrains.compose.resources.getString(Res.string.profile_default) }

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

    /** TV/monitor attached via USB-C or HDMI, if any. */
    val externalDisplay: StateFlow<io.github.thecodingdad.gamelodge.system.ExternalDisplayInfo?> =
        c.externalDisplay()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeProfile: StateFlow<StreamProfile> = c.profiles.changes()
        .map { c.profiles.active(defaultProfileName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamProfile(null, defaultProfileName))

    val profiles: StateFlow<List<StreamProfile>> = c.profiles.changes()
        .map { c.profiles.all(defaultProfileName) }
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

    /** Bumped when the Home button is pressed: the start tab then begins at the top. */
    val homeResets = androidx.compose.runtime.mutableIntStateOf(0)

    /** Start tab is in its initial state (top, first item): Home then does nothing. */
    var homeAtStart = true

    /** Focus key ("row:itemId") to restore when returning to a list, e.g. back from details. */
    /** Last focused element per main tab, so switching tabs never overwrites another tab's position. */
    val focusKeys = HashMap<String, String>()

    val allEntries: StateFlow<List<LibraryEntry>> =
        c.library.allEntries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun sessions(itemId: String) = c.database.sessionDao().observeForItem(itemId)

    val finishedSessions: StateFlow<List<PlaySessionEntity>> =
        c.database.sessionDao().observeFinished().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Collections with their member item ids in the group's manual order. Lists, not sets: a set
     * compares equal regardless of order, so a reorder would never reach the screens.
     */
    val collections: StateFlow<List<Pair<CollectionEntity, List<String>>>> =
        combine(c.library.collections, c.library.memberships) { cols, refs ->
            val byCollection = refs.groupBy({ it.collectionId }, { it.itemId })
            cols.map { it to byCollection[it.id].orEmpty() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filters: StateFlow<List<FilterEntity>> =
        c.filters.filters.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Host UUID → name, from live hosts and (for offline/removed ones) the stored targets. */
    private val hostNames = combine(hosts, allEntries) { live, list ->
        val stored = list.flatMap { it.remoteTargets }.mapNotNull { t -> t.hostUuid?.let { it to (t.hostName ?: it) } }.toMap()
        stored + live.mapValues { it.value.name } + (FilterRules.SOURCE_LOCAL to org.jetbrains.compose.resources.getString(Res.string.filter_local)) +
            (FilterRules.SOURCE_EMULATOR to org.jetbrains.compose.resources.getString(Res.string.filter_emulation))
    }

    private fun resolved(place: FilterPlace): StateFlow<List<ResolvedFilter>> =
        combine(filters, allEntries, hostNames, collections) { f, list, names, cols ->
            FilterEngine.resolve(f, place, list, names, cols.associate { (col, members) -> col.id to members.toSet() })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val libraryFilters = resolved(FilterPlace.LIBRARY)
    /** Start tab rows in their own order (settings), independent of the library chips. */
    val homeFilters: StateFlow<List<ResolvedFilter>> =
        combine(resolved(FilterPlace.HOME), settings) { rows, s -> HomeRowOrder.sort(rows, s.homeRowOrder) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val appsFilters = resolved(FilterPlace.APPS)

    fun createGroup(name: String, scope: FilterScope, addItem: String? = null, onCreated: (FilterEntity) -> Unit = {}) {
        viewModelScope.launch {
            val group = c.filters.createGroup(name, scope)
            if (addItem != null) c.library.setInCollection(addItem, group.id, true)
            onCreated(group)
        }
    }

    fun setInCollection(itemId: String, collectionId: String, member: Boolean) {
        viewModelScope.launch { c.library.setInCollection(itemId, collectionId, member) }
    }

    fun reorderGroup(collectionId: String, itemIds: List<String>) {
        viewModelScope.launch { c.library.reorderCollection(collectionId, itemIds) }
    }

    /** Emulator for one ROM of a game; null = the platform's default. */
    fun setGameEmulator(targetId: String, key: String?) {
        viewModelScope.launch { c.database.libraryDao().setTargetEmulator(targetId, key) }
    }

    fun saveFilter(filter: FilterEntity) {
        viewModelScope.launch { c.filters.save(filter) }
    }

    fun renameFilter(filter: FilterEntity, name: String) {
        viewModelScope.launch { c.filters.rename(filter, name) }
    }

    fun deleteFilter(filter: FilterEntity) {
        viewModelScope.launch { c.filters.delete(filter) }
    }

    fun reorderFilters(ids: List<String>) {
        viewModelScope.launch { c.filters.reorder(ids) }
    }

    fun createSmartFilter(name: String, scope: FilterScope, onCreated: (FilterEntity) -> Unit = {}) {
        viewModelScope.launch { onCreated(c.filters.createSmart(name, FilterRules(), scope)) }
    }

    fun resetDefaultFilters(scope: FilterScope) {
        viewModelScope.launch { c.filters.resetDefaults(scope) }
    }

    fun resetAllFilters(scope: FilterScope) {
        viewModelScope.launch { c.filters.resetAll(scope) }
    }

    fun setPlatform(entry: LibraryEntry, platform: String?) {
        viewModelScope.launch { c.library.setPlatform(entry.item.id, platform) }
    }

    /** Time of the last user input; drives the screensaver. */
    val lastInteraction = kotlinx.coroutines.flow.MutableStateFlow(System.currentTimeMillis())

    fun touch() {
        lastInteraction.value = System.currentTimeMillis()
    }

    /** Selected main tab; lives here so the Home button can reset it from the activity. */
    val tabState = androidx.compose.runtime.mutableIntStateOf(0)

    /** The configured start tab was applied once for this process (not again on recreation). */
    var startTabApplied = false
    var tab: Int
        get() = tabState.intValue
        set(value) {
            tabState.intValue = value
            // Choosing a tab (also the settings tab again) starts the settings without a search.
            settingsQuery.value = ""
        }

    /** [tab] as a delegate for the UI (`var tab by vm.tabProperty`). */
    val tabProperty = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = tab
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) { tab = value }
    }

    /** Text of the settings search; kept while a hit's page is open, so back shows the hits again. */
    val settingsQuery = androidx.compose.runtime.mutableStateOf("")

    /** Emits when the Home button is pressed while the launcher is the home app. */
    val homePressed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Quick menu requested by a button combination: true = open, false = toggle. */
    val quickMenuRequests = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** Quick menu opened by touch (tap on the clock): no focus ring. */
    val quickMenuTouch = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun launch(
        entry: LibraryEntry,
        target: LaunchTargetEntity,
        override: StreamProfile? = null,
        quitRunning: Boolean = false,
        skipNetworkCheck: Boolean = false,
        emulatorKey: String? = null,
    ) {
        viewModelScope.launch { c.launcher.launch(entry, target, override, quitRunning, skipNetworkCheck, emulatorKey) }
    }

    fun dismissLaunch() = c.launcher.dismiss()

    fun cancelLaunch() = c.launcher.cancel()

    fun setActiveProfile(profile: StreamProfile) = c.profiles.setActive(profile.uuid)

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
