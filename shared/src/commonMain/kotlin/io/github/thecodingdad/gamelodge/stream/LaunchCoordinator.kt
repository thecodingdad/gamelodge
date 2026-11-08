package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.resources.xbox_dashboard_hint
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** What the launch overlay shows ([LaunchState.Working.retry]: number of the start's retry, null on the first try). */
sealed interface LaunchState {
    data object Idle : LaunchState
    /** [countdown]: seconds until the start goes on (game actions before the start). */
    data class Working(val entry: LibraryEntry, val step: LaunchStep, val countdown: Int? = null, val retry: Int? = null) : LaunchState
    data class Failed(val entry: LibraryEntry, val reason: LaunchError, val detail: String? = null) : LaunchState
    /** Another app runs on the host; the user must confirm quitting it. */
    data class ConfirmQuitRunning(val entry: LibraryEntry, val target: LaunchTargetEntity, val runningName: String, val override: StreamProfile?) : LaunchState
    /** The connection looks weak; the user may continue or pick a lighter profile. */
    data class NetworkWarning(val entry: LibraryEntry, val target: LaunchTargetEntity, val result: NetworkCheckResult, val override: StreamProfile?) : LaunchState
}

enum class LaunchStep { WAKING_HOST, CONNECTING_VPN, CHECKING_NETWORK, QUITTING_RUNNING, RUNNING_ACTIONS, STARTING }

enum class LaunchError { XBOX_SIGNED_OUT, XBOX_UNREACHABLE, XBOX_BUSY, HOST_OFFLINE, WAKE_FAILED, NOT_PAIRED, NO_LAUNCH_PERMISSION, APP_NOT_INSTALLED, START_FAILED, QUIT_FAILED, NO_EMULATOR, ROM_NEEDS_PATH }

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

    /** Opens the Xbox player for [stream] (session already set up); throws when that fails. */
    suspend fun startXboxStream(entry: LibraryEntry, target: LaunchTargetEntity, stream: io.github.thecodingdad.gamelodge.xbox.XboxStreamController): Unit =
        throw UnsupportedOperationException("No Xbox player on this platform")

    /** The Xbox stream still runs behind the launcher (Windows): its window comes to the front; false when gone. */
    fun resumeXboxStream(): Boolean = false
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

    /** Xbox consoles; null where the platform has none. */
    var xbox: io.github.thecodingdad.gamelodge.xbox.XboxRepository? = null

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
    data class NowPlaying(
        val entry: LibraryEntry,
        val target: LaunchTargetEntity,
        val startedAt: Long,
        val running: Boolean,
        /** Stream profile of a stream. */
        val profile: String? = null,
    )

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The game, app or stream started here ended: runs the end actions; safe to call more than once. */
    fun ended() {
        // A stream start that failed isn't over yet: it is retried (or reported) once its window closed.
        if (failedStart != null || retryPending) return
        gameActions.ended()
        val was = _nowPlaying.getAndUpdate { it?.copy(running = false) }
        if (was?.running == true) fireEvent("stopped", was, (System.currentTimeMillis() - was.startedAt) / 1000)
    }

    /** Home Assistant events "gamelodge_started" / "gamelodge_stopped" for automations. */
    private fun fireEvent(name: String, np: NowPlaying, durationSeconds: Long?) {
        val stream = np.target.type == TargetType.REMOTE || np.target.type == TargetType.XBOX
        val data = mapOf(
            "title" to np.entry.item.title,
            "kind" to if (np.entry.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.GAME) "game" else "app",
            "stream" to stream,
            "host" to np.target.hostName.takeIf { stream },
            "profile" to np.profile.takeIf { stream },
            "app_id" to np.target.appId.takeIf { stream },
            "duration_seconds" to durationSeconds,
        )
        scope.launch { ha().fireLauncherEvent(name, data) }
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

    /** Called when a stream ended (the library then looks for host changes often for a while). */
    @Volatile var afterStream: () -> Unit = {}

    /** The stream ended (its window closed): a VPN connected for it goes again, if so set. Safe to call twice. */
    fun streamEnded() {
        if (retryPending) return
        val failed = failedStart
        if (failed != null) {
            failedStart = null
            _activeStream.value = null
            StreamQuality.reset()
            streamNotices?.stopped()
            afterFailedStart(failed)
            return
        }
        if (_activeStream.value != null) afterStream()
        _activeStream.value = null
        StreamQuality.reset()
        streamNotices?.stopped()
        if (!vpnForStream) return
        vpnForStream = false
        scope.launch { runCatching { if (vpn.disconnectAfterStream()) vpn.disconnect() } }
    }

    /** The stream window reports a stream that never got going (Xbox): shown like a failed start. */
    fun reportFailure(error: LaunchError, detail: String?) {
        val entry = lastXboxEntry ?: return
        ended()
        fail(entry, error, detail)
    }

    @Volatile private var lastXboxEntry: LibraryEntry? = null

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
        if (retryPending) {
            // A retry given up: what the failed start skipped happens now.
            retryPending = false
            startAttempt = 0
            restoreProfile()
            ended()
        }
        dropStreamVpn()
    }

    /** The stream start that runs or ran last (a retry starts it again). */
    private class StreamStart(
        val entry: LibraryEntry,
        val target: LaunchTargetEntity,
        val host: HostState,
        val resuming: Boolean,
        val hostUuid: String,
        val appId: Int,
    )

    private class FailedStart(val error: LaunchError, val detail: String?)

    private var lastStart: StreamStart? = null

    /** Retries of the current start so far. */
    private var startAttempt = 0

    /** A failed start that waits for its stream window to close. */
    @Volatile private var failedStart: FailedStart? = null

    /** A retry waits for its turn (its stream window not started yet). */
    @Volatile private var retryPending = false

    /**
     * The streaming client reports that the stream didn't get going ([detail]: its stage and error
     * code); its window closes then ([streamEnded]), and the start is retried or reported.
     */
    fun streamStartFailed(error: LaunchError, detail: String?) {
        Log.w("Stream start failed ($detail), attempt ${startAttempt + 1}")
        failedStart = FailedStart(error, detail)
    }

    private fun afterFailedStart(failed: FailedStart) {
        val start = lastStart
        if (start != null && failed.error == LaunchError.START_FAILED && startAttempt < MAX_START_RETRIES) {
            startAttempt++
            retryPending = true
            _state.value = LaunchState.Working(start.entry, LaunchStep.STARTING, retry = startAttempt)
            running = scope.launch {
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
                // The stream window that closed planned the host's sleep: a new stream comes.
                autoSleep().cancel(start.hostUuid)
                retryPending = false
                beginStream(start, retry = true)
            }
            return
        }
        // Given up: what the failed start skipped (profile, end actions) happens now.
        startAttempt = 0
        restoreProfile()
        ended()
        fail(start?.entry ?: return, failed.error, failed.detail)
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
            TargetType.XBOX -> launchXbox(entry, target, override)
        }
    }

    /**
     * An Xbox game: the console is woken if needed (Xbox Live in standby, else the host's Home
     * Assistant action), the game started on it unless it already runs, then the stream opens.
     */
    private suspend fun launchXbox(entry: LibraryEntry, target: LaunchTargetEntity, override: StreamProfile?) {
        val xbox = xbox ?: return fail(entry, LaunchError.START_FAILED)
        val hostUuid = target.hostUuid ?: return
        val consoleId = io.github.thecodingdad.gamelodge.xbox.XboxRepository.consoleId(hostUuid) ?: return
        if (!xbox.auth.isSignedIn) return fail(entry, LaunchError.XBOX_SIGNED_OUT)
        // Still streaming from this console (Guide brought the launcher to the front): back to that window.
        if (_activeStream.value?.hostUuid == hostUuid && io.github.thecodingdad.gamelodge.xbox.XboxStreams.current != null && platform.resumeXboxStream()) {
            _state.value = LaunchState.Idle
            return
        }

        if (xbox.power(consoleId) != io.github.thecodingdad.gamelodge.xbox.XboxPower.ON) {
            _state.value = LaunchState.Working(entry, LaunchStep.WAKING_HOST)
            if (!hostPower().wake(hostUuid)) Log.w("Xbox: no wake signal sent")
            val timeout = hostPower().config(hostUuid).wakeTimeoutSeconds * 1000L
            if (!xbox.waitOn(consoleId, timeout)) return fail(entry, LaunchError.HOST_OFFLINE)
        }
        runStartActions(entry)
        _state.value = LaunchState.Working(entry, LaunchStep.STARTING)
        autoSleep().cancel(hostUuid)

        // The game in front on the console: started only when another (or none) runs. The
        // dashboard: the console's home screen (a running game stays, minimized).
        val dashboard = io.github.thecodingdad.gamelodge.xbox.XboxRepository.isDashboard(target)
        val titleId = target.appId?.toUInt()?.toLong()
        val productId = target.appUuid
        if (dashboard) {
            if (!xbox.goHome(consoleId)) Log.w("Xbox: home screen not confirmed")
        } else if (productId != null && (titleId == null || !xbox.isRunning(consoleId, titleId))) {
            if (!xbox.launch(consoleId, productId)) Log.w("Xbox: start of ${target.name} not confirmed")
        }

        // The profile's Xbox part: the one picked for this start, else the active profile.
        val profile = override ?: profiles.active("")
        val streamSettings = io.github.thecodingdad.gamelodge.xbox.XboxStreamSettings.of(profiles.values(profile.uuid), profile.uuid?.let { profiles.values(null) })
        val session = xbox.newSession(consoleId, streamSettings.resolution)
        try {
            session.start()
        } catch (e: io.github.thecodingdad.gamelodge.xbox.XhomeException) {
            Log.w("Xbox session: ${e.message}")
            return fail(entry, when (e.reason) {
                io.github.thecodingdad.gamelodge.xbox.XhomeException.Reason.CONSOLE_UNREACHABLE -> LaunchError.XBOX_UNREACHABLE
                io.github.thecodingdad.gamelodge.xbox.XhomeException.Reason.BUSY -> LaunchError.XBOX_BUSY
                else -> LaunchError.START_FAILED
            }, e.message)
        } catch (e: io.github.thecodingdad.gamelodge.xbox.XboxAuthException) {
            return fail(entry, if (e.signedOut) LaunchError.XBOX_SIGNED_OUT else LaunchError.START_FAILED, e.message)
        } catch (e: Exception) {
            Log.e("Xbox session", e)
            return fail(entry, LaunchError.START_FAILED, e.message)
        }
        val controller = io.github.thecodingdad.gamelodge.xbox.XboxStreamController(
            session, scope, io.github.thecodingdad.gamelodge.xbox.XboxStreamController.Config(
                maxBitrateKbps = streamSettings.maxBitrateMbps * 1000,
                vibration = streamSettings.vibration,
                sharpness = streamSettings.sharpness,
                fit = streamSettings.fit,
                dashboard = dashboard,
                hint = if (dashboard) org.jetbrains.compose.resources.getString(io.github.thecodingdad.gamelodge.resources.Res.string.xbox_dashboard_hint) else null,
            ),
        )
        try {
            val profileName = profile.name.ifEmpty { null }
            playtime.start(entry.item.id, target.id, profileName)
            StreamQuality.reset()
            _activeStream.value = ActiveStream(entry, hostUuid, target.appId ?: 0)
            io.github.thecodingdad.gamelodge.xbox.XboxStreams.current = controller
            lastXboxEntry = entry
            if (!dashboard) titleId?.let { xbox.markRunning(consoleId, it) }
            platform.startXboxStream(entry, target, controller)
            started(entry, target, profileName)
            _state.value = LaunchState.Idle
        } catch (e: Exception) {
            Log.e("Xbox stream start failed", e)
            io.github.thecodingdad.gamelodge.xbox.XboxStreams.current = null
            _activeStream.value = null
            controller.end()
            fail(entry, LaunchError.START_FAILED, e.message)
        }
    }

    private fun finish(entry: LibraryEntry, target: LaunchTargetEntity, error: LaunchError?) {
        if (error == null) started(entry, target)
        _state.value = if (error == null) LaunchState.Idle else LaunchState.Failed(entry, error)
    }

    private fun started(entry: LibraryEntry, target: LaunchTargetEntity, profile: String? = null) {
        val np = NowPlaying(entry, target, System.currentTimeMillis(), running = true, profile = profile)
        _nowPlaying.value = np
        fireEvent("started", np, null)
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
                    // The network changed: a Home Assistant connection from before is dead or hangs
                    // (a wake through Home Assistant would wait for it and fail).
                    runCatching { ha().client.restart() }
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
        // What runs on the host right now (another client's stream too), not the last poll's state.
        withTimeoutOrNull(REFRESH_WAIT_MS) { hosts.refresh(hostUuid) }
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
        // The connection warning as the stream's profile says.
        profiles.active("").uuid.let { uuid -> StreamQuality.mode = StreamQuality.modeOf(profiles.values(uuid), uuid?.let { profiles.values(null) }) }
        autoSleep().cancel(hostUuid)

        startAttempt = 0
        beginStream(StreamStart(entry, target, host, resuming, hostUuid, appId), retry = false)
    }

    /** Hands the stream to the client ([retry]: again, after a start that didn't get going). */
    private suspend fun beginStream(start: StreamStart, retry: Boolean) {
        val entry = start.entry
        val target = start.target
        val host = start.host
        val resuming = start.resuming
        lastStart = start
        // The start screen as it is now: a failure reported meanwhile (retry, error) keeps its own.
        val starting = _state.value
        try {
            val profileName = profiles.active("").name.ifEmpty { null }
            playtime.start(entry.item.id, target.id, profileName)
            StreamQuality.reset()
            _activeStream.value = ActiveStream(entry, start.hostUuid, start.appId)
            // The host's GameLodge learns which start action to run (its runner asks for it).
            streamNotices?.started(start.hostUuid, resuming, target.appUuid, oneOffAction.takeUnless { resuming })
            // Another client's session (Vibepollo) is taken over through its copy of the game.
            val copy = host.foreignCopy?.takeIf { resuming }
            val streamTarget = if (copy != null) target.copy(appId = copy.appId, appUuid = copy.appUUID, name = copy.appName.trim()) else target
            platform.startStream(entry, streamTarget, host)
            // A retry continues the start that is already reported as started.
            if (!retry) started(entry, target, profileName)
            _state.compareAndSet(starting, LaunchState.Idle)
        } catch (e: Exception) {
            Log.e("Stream start failed", e)
            restoreProfile()
            _activeStream.value = null
            streamNotices?.stopped()
            // A retry's start already counts as started: it ends here.
            if (retry) ended()
            startAttempt = 0
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
        // A failed start keeps its one-off profile for the retry.
        if (failedStart != null || retryPending) return
        if (!profileToRestore.first) return
        profiles.setActive(profileToRestore.second)
        profileToRestore = false to null
    }

    companion object {
        /** A stream that didn't get going is started again this often before the error shows. */
        private const val MAX_START_RETRIES = 2
        /** Tries of a stream start in all (the first and its retries). */
        const val MAX_START_ATTEMPTS = MAX_START_RETRIES + 1
        /** After connecting the VPN: how long to wait for the host before waking it. */
        private const val VPN_WAIT_MS = 15_000L
        /** Longest wait for the host's current state before a start. */
        private const val REFRESH_WAIT_MS = 4_000L
        /** Pause before such a retry (the host tidies up the failed session). */
        private const val RETRY_DELAY_MS = 2_000L
    }
}
