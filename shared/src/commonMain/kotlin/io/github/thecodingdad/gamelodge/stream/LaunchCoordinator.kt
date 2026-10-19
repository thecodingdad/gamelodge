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

/** Optional VPN hook, filled by the VPN module: connects if needed, returns true when it did. */
fun interface VpnAutoConnect {
    suspend fun connectIfNeeded(host: HostState?): Boolean
}

/** The platform's part of starting things: apps, emulators and the stream itself. */
interface LaunchPlatform {
    /** Starts an installed app or game; null when it started, else why not. */
    suspend fun launchLocal(entry: LibraryEntry, target: LaunchTargetEntity): LaunchError?

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

    /** Profile that was active before a one-off "start with…"; restored when we return. */
    private var profileToRestore: Pair<Boolean, UUID?> = false to null

    var vpn: VpnAutoConnect = VpnAutoConnect { false }

    /** Home Assistant actions before a start and after a game ended. */
    val gameActions = io.github.thecodingdad.gamelodge.ha.GameActionRunner(
        { ha().runAction(it) }, { io.github.thecodingdad.gamelodge.ha.GameActionSettings.decode(settings.current().gameActions) }, scope,
    )

    /** The game actions before [entry] starts; the start screen counts down where the start waits. */
    private suspend fun runStartActions(entry: LibraryEntry) {
        gameActions.beforeStart(entry.item.id) { left -> _state.value = LaunchState.Working(entry, LaunchStep.RUNNING_ACTIONS, left) }
    }

    /** Game whose stream is being started; shown by the stream window while it connects. */
    @Volatile
    var streamingEntry: LibraryEntry? = null
        private set

    fun dismiss() {
        _state.value = LaunchState.Idle
    }

    private var running: kotlinx.coroutines.Job? = null

    /** User cancelled while waking/connecting: stop waiting (an already sent wake stays sent). */
    fun cancel() {
        running?.cancel()
        running = null
        _state.value = LaunchState.Idle
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
    ) {
        running = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        when (target.type) {
            TargetType.LOCAL -> { runStartActions(entry); finish(entry, platform.launchLocal(entry, target)) }
            TargetType.REMOTE -> launchRemote(entry, target, override, quitRunning, skipNetworkCheck)
            TargetType.ROM -> { runStartActions(entry); finish(entry, platform.launchRom(entry, target, emulatorKey)) }
        }
    }

    private fun finish(entry: LibraryEntry, error: LaunchError?) {
        _state.value = if (error == null) LaunchState.Idle else LaunchState.Failed(entry, error)
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

        // 1. Wake the host when it is offline.
        if (host(hostUuid)?.status != HostStatus.ONLINE) {
            _state.value = LaunchState.Working(entry, LaunchStep.WAKING_HOST)
            if (!wakeAndWait(hostUuid)) {
                // 2. Maybe it's only unreachable from here: try the VPN, then wait again.
                _state.value = LaunchState.Working(entry, LaunchStep.CONNECTING_VPN)
                val vpnUp = runCatching { vpn.connectIfNeeded(host(hostUuid)) }.getOrDefault(false)
                if (!vpnUp || !waitOnline(hostUuid, 30_000)) {
                    _state.value = LaunchState.Failed(entry, LaunchError.HOST_OFFLINE)
                    return
                }
            }
        }
        val host = host(hostUuid)
        if (host == null || host.status != HostStatus.ONLINE || !platform.isReachable(host)) {
            _state.value = LaunchState.Failed(entry, LaunchError.HOST_OFFLINE)
            return
        }
        if (!host.paired) {
            _state.value = LaunchState.Failed(entry, LaunchError.NOT_PAIRED)
            return
        }
        // 3. Permissions (Apollo/Vibepollo): resuming the running app only needs "view".
        val resuming = host.isStreaming && host.runningAppId == appId
        if (!resuming && !HostPermission.has(host.permission, HostPermission.LAUNCH_APPS)) {
            _state.value = LaunchState.Failed(entry, LaunchError.NO_LAUNCH_PERMISSION)
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
                _state.value = LaunchState.Failed(entry, LaunchError.QUIT_FAILED)
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
            streamingEntry = entry
            platform.startStream(entry, target, host)
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
            _state.value = LaunchState.Failed(entry, LaunchError.START_FAILED, e.message)
        }
    }

    /** Sends the configured wake actions and waits until the host answers. */
    private suspend fun wakeAndWait(hostUuid: String): Boolean {
        if (!hostPower().wake(hostUuid)) return false
        return waitOnline(hostUuid, 120_000)
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
}
