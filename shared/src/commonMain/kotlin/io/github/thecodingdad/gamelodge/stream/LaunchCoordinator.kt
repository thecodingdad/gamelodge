package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.library.PlaytimeTracker
import io.github.thecodingdad.gamelodge.data.remote.HostActions
import io.github.thecodingdad.gamelodge.data.remote.HostPermission
import io.github.thecodingdad.gamelodge.data.remote.HostRepository
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.data.settings.NetworkCheckMode
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.ha.HaRepository
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** What the launch overlay shows. */
sealed interface LaunchState {
    data object Idle : LaunchState
    /** [countdown]: seconds until the start goes on (game actions before the start). */
    data class Working(val entry: LibraryEntry, val step: LaunchStep, val countdown: Int? = null) : LaunchState
    data class Failed(val entry: LibraryEntry, val reason: LaunchError, val detail: String? = null) : LaunchState
    /** Another app runs on the host; the user must confirm quitting it. */
    data class ConfirmQuitRunning(val entry: LibraryEntry, val target: LaunchTargetEntity, val runningName: String, val override: StreamProfile?) : LaunchState
    /** The connection looks weak; the user may continue or pick a lighter profile. */
    data class NetworkWarning(val entry: LibraryEntry, val target: LaunchTargetEntity, val result: NetworkCheckResult, val override: StreamProfile?) : LaunchState
}

enum class LaunchStep { WAKING_HOST, CONNECTING_VPN, CHECKING_NETWORK, QUITTING_RUNNING, RUNNING_ACTIONS, STARTING }

enum class LaunchError { HOST_OFFLINE, WAKE_FAILED, NOT_PAIRED, NO_LAUNCH_PERMISSION, APP_NOT_INSTALLED, START_FAILED, QUIT_FAILED, NO_EMULATOR, ROM_NEEDS_PATH }

/** The VPN as a stream start uses it, filled by the VPN module (platforms without one never connect). */
interface StreamVpn {
    /**
     * The settings want the VPN for a stream to [hostUuid] right now (switched on, outside the
     * home networks if set, a tunnel for this host that isn't up yet).
     */
    suspend fun wantedForStream(hostUuid: String): Boolean

    /** Connects the tunnel for [hostUuid]; true when it is up. */
    suspend fun connectForStream(hostUuid: String): Boolean

    suspend fun disconnect()

    /** After a stream it connected, the VPN is disconnected again (setting). */
    suspend fun disconnectAfterStream(): Boolean

    object None : StreamVpn {
        override suspend fun wantedForStream(hostUuid: String) = false
        override suspend fun connectForStream(hostUuid: String) = false
        override suspend fun disconnect() = Unit
        override suspend fun disconnectAfterStream() = false
    }
}

/** The platform's part of starting things: apps, emulators and the stream itself. */
interface LaunchPlatform {
    /** Starts an installed app or game ([action]: a start action of PC games, null = its default); null when it started, else why not. */
    suspend fun launchLocal(entry: LibraryEntry, target: LaunchTargetEntity, action: String? = null): LaunchError?

    /** Starts a ROM in its emulator ([emulatorKey]: one-off choice); null when it started. */
    suspend fun launchRom(entry: LibraryEntry, target: LaunchTargetEntity, emulatorKey: String?): LaunchError?

    /** The host has an address we can reach right now. */
    fun isReachable(host: HostState): Boolean

    /** We reach the host through its remote (internet) address, i.e. not from its home network. */
    fun isRemoteConnection(host: HostState): Boolean

    suspend fun measureNetwork(host: HostState): NetworkCheckResult?

    /** Hands the stream of [target] on [host] to the streaming client; throws when that fails. */
    suspend fun startStream(entry: LibraryEntry, target: LaunchTargetEntity, host: HostState)
}

/**
 * Starts local apps and streams. All pre-launch steps (wake, VPN, permissions, network check)
 * live here so every entry point (home, library, detail, resume card) behaves identically.
 */
class LaunchCoordinator(
    private val hosts: HostRepository,
    private val hostActions: HostActions,
    private val hostPower: () -> HostPower,
    private val autoSleep: () -> AutoSleep,
    private val profiles: StreamProfiles,
    private val playtime: PlaytimeTracker,
    private val settings: SettingsRepository,
    private val ha: () -> HaRepository,
    private val scope: CoroutineScope,
    private val platform: LaunchPlatform,
) {
    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    /** Start action of a "start with…" (kept across the confirmations that start again). */
    private var oneOffAction: String? = null

    /** Profile that was active before a one-off "start with…"; restored when we return. */
    private var profileToRestore: Pair<Boolean, UUID?> = false to null

    var vpn: StreamVpn = StreamVpn.None

    /** Tells a paired GameLodge on the host's PC about the stream (Guide, game window on resume). */
    var streamNotices: io.github.thecodingdad.gamelodge.data.companion.StreamNotices? = null

    /** The VPN was connected for the stream being started / running (disconnected again afterwards). */
    @Volatile private var vpnForStream = false

    /** Home Assistant actions before a start and after a game ended. */
    val gameActions = io.github.thecodingdad.gamelodge.ha.GameActionRunner(
        { ha().runAction(it) }, { io.github.thecodingdad.gamelodge.ha.GameActionSettings.decode(settings.current().gameActions) }, scope,
    )

    /** The game actions before [entry] starts; the start screen counts down where the start waits. */
    private suspend fun runStartActions(entry: LibraryEntry) {
        gameActions.beforeStart(entry.item.id) { left -> _state.value = LaunchState.Working(entry, LaunchStep.RUNNING_ACTIONS, left) }
    }

    /**
     * What this launcher started last (game, app or stream) and whether it still runs: set by a
     * successful start, [running] false once [ended] (back in the launcher / game or stream closed).
     */
    data class NowPlaying(val entry: LibraryEntry, val target: LaunchTargetEntity, val startedAt: Long, val running: Boolean)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The game, app or stream started here ended: runs the end actions; safe to call more than once. */
    fun ended() {
        gameActions.ended()
        _nowPlaying.update { it?.copy(running = false) }
    }

    /** The stream this launcher started and that runs right now (until its window closes). */
    data class ActiveStream(val entry: LibraryEntry, val hostUuid: String, val appId: Int)

    private val _activeStream = kotlinx.coroutines.flow.MutableStateFlow<ActiveStream?>(null)

    /**
     * Known without asking the host: its polling pauses during the stream, so the quick menu over
     * the stream finds the running game here.
     */
    val activeStream: kotlinx.coroutines.flow.StateFlow<ActiveStream?> = _activeStream

    /** Game whose stream is being started; shown by the stream window while it connects. */
    val streamingEntry: LibraryEntry? get() = _activeStream.value?.entry

    fun dismiss() {
        _state.value = LaunchState.Idle
        dropStreamVpn()
    }

    /** A start didn't get to the stream (failed, cancelled, dismissed): a VPN connected for it goes again. */
    private fun dropStreamVpn() {
        if (!vpnForStream || streamingEntry != null) return
        vpnForStream = false
        scope.launch { runCatching { vpn.disconnect() } }
    }

    /** The stream ended (its window closed): a VPN connected for it goes again, if so set. Safe to call twice. */
    fun streamEnded() {
        _activeStream.value = null
        streamNotices?.stopped()
        if (!vpnForStream) return
        vpnForStream = false
        scope.launch { runCatching { if (vpn.disconnectAfterStream()) vpn.disconnect() } }
    }

    private fun fail(entry: LibraryEntry, error: LaunchError, detail: String? = null) {
        _state.value = LaunchState.Failed(entry, error, detail)
        dropStreamVpn()
    }

    private var running: kotlinx.coroutines.Job? = null

    /** User cancelled while waking/connecting: stop waiting (an already sent wake stays sent). */
    fun cancel() {
        running?.cancel()
        running = null
        _state.value = LaunchState.Idle
        dropStreamVpn()
    }

    /**
     * [override]: profile for this launch only ("start with…"); null keeps the active profile.
     * [quitRunning]/[skipNetworkCheck]: answers to earlier confirmation dialogs.
     */
    suspend fun launch(
        entry: LibraryEntry,
        target: LaunchTargetEntity,
        override: StreamProfile? = null,
        quitRunning: Boolean = false,
        skipNetworkCheck: Boolean = false,
        /** ROMs: emulator for this start only ("start with…"); null = the game's / platform's choice. */
        emulatorKey: String? = null,
        /** Start action for this start only ("start with…"); null = the game's default. */
        action: String? = null,
    ) {
        running = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        // Confirmations (quit the running game, poor network) start again: the choice stays.
        if (action != null || (!quitRunning && !skipNetworkCheck)) oneOffAction = action
        when (target.type) {
            TargetType.LOCAL -> { runStartActions(entry); finish(entry, target, platform.launchLocal(entry, target, oneOffAction)) }
            TargetType.REMOTE -> launchRemote(entry, target, override, quitRunning, skipNetworkCheck)
            TargetType.ROM -> { runStartActions(entry); finish(entry, target, platform.launchRom(entry, target, emulatorKey)) }
        }
    }

    private fun finish(entry: LibraryEntry, target: LaunchTargetEntity, error: LaunchError?) {
        if (error == null) started(entry, target)
        _state.value = if (error == null) LaunchState.Idle else LaunchState.Failed(entry, error)
    }

    private fun started(entry: LibraryEntry, target: LaunchTargetEntity) {
        _nowPlaying.value = NowPlaying(entry, target, System.currentTimeMillis(), running = true)
    }

    private fun host(uuid: String) = hosts.hosts.value[uuid]

    private suspend fun launchRemote(
        entry: LibraryEntry,
        target: LaunchTargetEntity,
        override: StreamProfile?,
        quitRunning: Boolean,
        skipNetworkCheck: Boolean,
    ) {
        val hostUuid = target.hostUuid ?: return
        val appId = target.appId ?: return

        // 1. Host not reachable: maybe only from here, so the VPN first (when wanted), then wake
        //    it and wait as long as set for this host.
        if (host(hostUuid)?.status != HostStatus.ONLINE) {
            if (!vpnForStream && runCatching { vpn.wantedForStream(hostUuid) }.getOrDefault(false)) {
                _state.value = LaunchState.Working(entry, LaunchStep.CONNECTING_VPN)
                if (runCatching { vpn.connectForStream(hostUuid) }.getOrDefault(false)) {
                    vpnForStream = true
                    waitOnline(hostUuid, VPN_WAIT_MS)
                }
            }
            if (host(hostUuid)?.status != HostStatus.ONLINE) {
                _state.value = LaunchState.Working(entry, LaunchStep.WAKING_HOST)
                if (!wakeAndWait(hostUuid)) {
                    fail(entry, LaunchError.HOST_OFFLINE)
                    return
                }
            }
        }
        val host = host(hostUuid)
        if (host == null || host.status != HostStatus.ONLINE || !platform.isReachable(host)) {
            fail(entry, LaunchError.HOST_OFFLINE)
            return
        }
        if (!host.paired) {
            fail(entry, LaunchError.NOT_PAIRED)
            return
        }
        // 3. Permissions (Apollo/Vibepollo): resuming the running app only needs "view".
        val resuming = host.isStreaming && host.runningAppId == appId
        if (!resuming && !HostPermission.has(host.permission, HostPermission.LAUNCH_APPS)) {
            fail(entry, LaunchError.NO_LAUNCH_PERMISSION)
            return
        }

        // 4. Network check.
        if (!skipNetworkCheck && shouldCheckNetwork(host)) {
            _state.value = LaunchState.Working(entry, LaunchStep.CHECKING_NETWORK)
            val result = platform.measureNetwork(host)
            if (result != null && !result.isGood) {
                _state.value = LaunchState.NetworkWarning(entry, target, result, override)
                return
            }
        }

        // 5. Another app is running.
        if (host.isStreaming && !resuming) {
            if (!quitRunning) {
                val runningName = hosts.appLists.value[hostUuid]
                    ?.firstOrNull { it.app.appId == host.runningAppId }?.app?.appName ?: "?"
                _state.value = LaunchState.ConfirmQuitRunning(entry, target, runningName, override)
                return
            }
            _state.value = LaunchState.Working(entry, LaunchStep.QUITTING_RUNNING)
            if (!hostActions.quitApp(host)) {
                Log.w("Quit failed")
                fail(entry, LaunchError.QUIT_FAILED)
                return
            }
        }

        runStartActions(entry)
        _state.value = LaunchState.Working(entry, LaunchStep.STARTING)
        if (override != null) applyOneOffProfile(override.uuid)
        autoSleep().cancel(hostUuid)

        try {
            val profileName = profiles.active("").name.ifEmpty { null }
            playtime.start(entry.item.id, target.id, profileName)
            _activeStream.value = ActiveStream(entry, hostUuid, appId)
            // The host's GameLodge learns which start action to run (its runner asks for it).
            streamNotices?.started(hostUuid, resuming, target.appUuid, oneOffAction.takeUnless { resuming })
            platform.startStream(entry, target, host)
            started(entry, target)
            _state.value = LaunchState.Idle
            scope.launch {
                ha().fireLauncherEvent(
                    "stream_started",
                    mapOf("game" to entry.item.title, "host" to host.name, "profile" to profileName, "app_id" to appId.toString()),
                )
            }
        } catch (e: Exception) {
            Log.e("Stream start failed", e)
            restoreProfile()
            _activeStream.value = null
            streamNotices?.stopped()
            fail(entry, LaunchError.START_FAILED, e.message)
        }
    }

    /** Sends the configured wake actions and waits until the host answers (as long as set for it). */
    private suspend fun wakeAndWait(hostUuid: String): Boolean {
        if (!hostPower().wake(hostUuid)) return false
        return waitOnline(hostUuid, hostPower().config(hostUuid).wakeTimeoutSeconds * 1000L)
    }

    private suspend fun waitOnline(hostUuid: String, timeoutMs: Long): Boolean {
        hosts.invalidate(hostUuid)
        return withTimeoutOrNull(timeoutMs) {
            hosts.hosts.first { it[hostUuid]?.status == HostStatus.ONLINE }
        } != null
    }

    private suspend fun shouldCheckNetwork(host: HostState): Boolean = when (settings.current().networkCheck) {
        NetworkCheckMode.OFF -> false
        NetworkCheckMode.ALWAYS -> true
        NetworkCheckMode.OUTSIDE_HOME -> platform.isRemoteConnection(host)
    }

    private fun applyOneOffProfile(profile: UUID?) {
        val current = profiles.active("").uuid
        if (profile == current) return
        if (!profileToRestore.first) profileToRestore = true to current
        profiles.setActive(profile)
    }

    /** Called when the launcher is visible again after a stream. */
    fun restoreProfile() {
        if (!profileToRestore.first) return
        profiles.setActive(profileToRestore.second)
        profileToRestore = false to null
    }

    private companion object {
        /** After connecting the VPN: how long to wait for the host before waking it. */
        const val VPN_WAIT_MS = 15_000L
    }
}
