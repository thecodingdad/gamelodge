package io.github.thecodingdad.moonlightlauncher.stream

import android.app.Activity
import com.limelight.nvstream.http.NvApp
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.ServerHelper
import io.github.thecodingdad.moonlightlauncher.AppContainer
import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
import io.github.thecodingdad.moonlightlauncher.data.remote.HostPermission
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.data.settings.NetworkCheckMode
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** What the launch overlay shows. */
sealed interface LaunchState {
    data object Idle : LaunchState
    data class Working(val entry: LibraryEntry, val step: LaunchStep) : LaunchState
    data class Failed(val entry: LibraryEntry, val reason: LaunchError, val detail: String? = null) : LaunchState
    /** Another app runs on the host; the user must confirm quitting it. */
    data class ConfirmQuitRunning(val entry: LibraryEntry, val target: LaunchTargetEntity, val runningName: String, val override: StreamProfile?) : LaunchState
    /** The connection looks weak; the user may continue or pick a lighter profile. */
    data class NetworkWarning(val entry: LibraryEntry, val target: LaunchTargetEntity, val result: NetworkCheckResult, val override: StreamProfile?) : LaunchState
}

enum class LaunchStep { WAKING_HOST, CONNECTING_VPN, CHECKING_NETWORK, QUITTING_RUNNING, STARTING }

enum class LaunchError { HOST_OFFLINE, WAKE_FAILED, NOT_PAIRED, NO_LAUNCH_PERMISSION, APP_NOT_INSTALLED, START_FAILED, QUIT_FAILED, NO_EMULATOR, ROM_NEEDS_PATH }

/** Optional VPN hook, filled by the VPN module: connects if needed, returns true when it did. */
fun interface VpnAutoConnect {
    suspend fun connectIfNeeded(host: HostState?): Boolean
}

/**
 * Starts local apps and streams. All pre-launch steps (wake, VPN, permissions, network check)
 * live here so every entry point (home, library, detail, resume card) behaves identically.
 */
class LaunchCoordinator(private val container: AppContainer) {
    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    /** Profile that was active before a one-off "start with…"; restored when we return. */
    private var profileToRestore: Pair<Boolean, UUID?> = false to null

    var vpn: VpnAutoConnect = VpnAutoConnect { false }

    /** Game whose stream is being started; shown by the stream activity while it connects. */
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
        activity: Activity,
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
            TargetType.LOCAL -> launchLocal(activity, entry, target)
            TargetType.REMOTE -> launchRemote(activity, entry, target, override, quitRunning, skipNetworkCheck)
            TargetType.ROM -> launchRom(activity, entry, target, emulatorKey)
        }
    }

    private suspend fun launchRom(activity: Activity, entry: LibraryEntry, target: LaunchTargetEntity, emulatorKey: String?) {
        val platform = target.romPlatform
        val uri = target.romUri
        val choice = if (platform != null) container.emulation.resolve(platform, emulatorKey ?: target.emulatorKey) else null
        if (choice == null || uri == null) {
            _state.value = LaunchState.Failed(entry, LaunchError.NO_EMULATOR)
            return
        }
        when (val result = io.github.thecodingdad.moonlightlauncher.data.emulation.EmulatorLauncher.intent(activity, choice, uri, target.romPath)) {
            is io.github.thecodingdad.moonlightlauncher.data.emulation.EmulatorLauncher.Result.Problem ->
                _state.value = LaunchState.Failed(entry, LaunchError.ROM_NEEDS_PATH)
            is io.github.thecodingdad.moonlightlauncher.data.emulation.EmulatorLauncher.Result.Ok -> {
                val started = runCatching { activity.startActivity(result.intent) }
                    .onFailure { io.github.thecodingdad.moonlightlauncher.util.Log.e("Emulator start failed (${choice.key})", it) }
                // Android's usage data only sees the emulator, not the game: always measured here.
                if (started.isSuccess) container.playtime.start(entry.item.id, target.id, null)
                _state.value = if (started.isSuccess) LaunchState.Idle else LaunchState.Failed(entry, LaunchError.START_FAILED)
            }
        }
    }

    private suspend fun launchLocal(activity: Activity, entry: LibraryEntry, target: LaunchTargetEntity) {
        val intent = target.packageName?.let(container.localApps::launchIntent)
        if (intent == null) {
            _state.value = LaunchState.Failed(entry, LaunchError.APP_NOT_INSTALLED)
            return
        }
        // With usage access Android's statistics count the time (see UsageSync).
        if (!container.usage.granted) container.playtime.start(entry.item.id, target.id, null)
        activity.startActivity(intent)
        _state.value = LaunchState.Idle
    }

    private fun host(uuid: String) = container.hosts.hosts.value[uuid]

    private suspend fun launchRemote(
        activity: Activity,
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
        if (host == null || host.status != HostStatus.ONLINE || host.details.activeAddress == null) {
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
            val result = NetworkCheck.measure(container.hosts, host)
            if (result != null && !result.isGood) {
                _state.value = LaunchState.NetworkWarning(entry, target, result, override)
                return
            }
        }

        // 5. Another app is running.
        if (host.isStreaming && !resuming) {
            if (!quitRunning) {
                val runningName = container.hosts.appLists.value[hostUuid]
                    ?.firstOrNull { it.app.appId == host.runningAppId }?.app?.appName ?: "?"
                _state.value = LaunchState.ConfirmQuitRunning(entry, target, runningName, override)
                return
            }
            _state.value = LaunchState.Working(entry, LaunchStep.QUITTING_RUNNING)
            val quit = runCatching { withContext(Dispatchers.IO) { container.hosts.http(host).quitApp() } }
                .onFailure { Log.w("Quit failed", it) }.getOrDefault(false)
            if (!quit) {
                _state.value = LaunchState.Failed(entry, LaunchError.QUIT_FAILED)
                return
            }
        }

        _state.value = LaunchState.Working(entry, LaunchStep.STARTING)
        if (override != null) applyOneOffProfile(override.uuid)
        container.autoSleep.cancel(hostUuid)

        try {
            val binder = container.hosts.awaitBinder()
            val app = NvApp(target.name, target.appUuid, appId, target.hdrSupported)
            val prefs = PreferenceConfiguration.readPreferences(activity)
            val vDisplay = prefs.useVirtualDisplay && host.details.vDisplaySupported && host.details.vDisplayDriverReady
            val intent = ServerHelper.createStartIntent(activity, app, host.details, binder, vDisplay)
            val profileName = StreamProfiles.active("").name.ifEmpty { null }
            container.playtime.start(entry.item.id, target.id, profileName)
            streamingEntry = entry
            activity.startActivity(intent)
            _state.value = LaunchState.Idle
            container.scope.launch {
                container.ha.fireLauncherEvent(
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
        if (!container.hostPower.wake(hostUuid)) return false
        return waitOnline(hostUuid, 120_000)
    }

    private suspend fun waitOnline(hostUuid: String, timeoutMs: Long): Boolean {
        container.hosts.invalidate(hostUuid)
        return withTimeoutOrNull(timeoutMs) {
            container.hosts.hosts.first { it[hostUuid]?.status == HostStatus.ONLINE }
        } != null
    }

    private suspend fun shouldCheckNetwork(host: HostState): Boolean = when (container.settings.current().networkCheck) {
        NetworkCheckMode.OFF -> false
        NetworkCheckMode.ALWAYS -> true
        // Using the host's remote address means we are not in the same network.
        NetworkCheckMode.OUTSIDE_HOME -> host.details.activeAddress != null &&
            host.details.activeAddress == host.details.remoteAddress &&
            host.details.remoteAddress != host.details.localAddress
    }

    private fun applyOneOffProfile(profile: UUID?) {
        val current = com.limelight.profiles.ProfilesManager.getInstance().active?.uuid
        if (profile == current) return
        if (!profileToRestore.first) profileToRestore = true to current
        StreamProfiles.setActive(profile)
    }

    /** Called when the launcher is visible again after a stream. */
    fun restoreProfile() {
        if (!profileToRestore.first) return
        StreamProfiles.setActive(profileToRestore.second)
        profileToRestore = false to null
    }
}
