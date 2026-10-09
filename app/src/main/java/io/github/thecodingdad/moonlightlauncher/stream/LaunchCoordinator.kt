package io.github.thecodingdad.moonlightlauncher.stream

import android.app.Activity
import com.limelight.nvstream.http.NvApp
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.ServerHelper
import io.github.thecodingdad.moonlightlauncher.AppContainer
import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the launch overlay shows. */
sealed interface LaunchState {
    data object Idle : LaunchState
    data class Working(val entry: LibraryEntry, val step: LaunchStep) : LaunchState
    data class Failed(val entry: LibraryEntry, val reason: LaunchError) : LaunchState
    /** Another app runs on the host; the user must confirm quitting it. */
    data class ConfirmQuitRunning(val entry: LibraryEntry, val target: LaunchTargetEntity, val runningName: String, val override: StreamProfile?) : LaunchState
}

enum class LaunchStep { WAKING_HOST, CONNECTING_VPN, CHECKING_NETWORK, QUITTING_RUNNING, STARTING }

enum class LaunchError { HOST_OFFLINE, NOT_PAIRED, APP_NOT_INSTALLED, START_FAILED, QUIT_FAILED }

/**
 * Starts local apps and streams. Pre-launch steps (wake, VPN, network check) plug in here
 * so every entry point (home, library, detail, resume card) behaves identically.
 */
class LaunchCoordinator(private val container: AppContainer) {
    private val _state = MutableStateFlow<LaunchState>(LaunchState.Idle)
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    /** Profile that was active before a one-off "start with…"; restored when we return. */
    private var profileToRestore: Pair<Boolean, UUID?> = false to null

    /** Hooks for later steps (host wake, VPN, network check); each may suspend or fail. */
    var preStreamSteps: List<suspend (LibraryEntry, HostState?, (LaunchStep) -> Unit) -> LaunchError?> = emptyList()

    fun dismiss() {
        _state.value = LaunchState.Idle
    }

    /** [override]: profile for this launch only ("start with…"); null keeps the active profile. */
    suspend fun launch(
        activity: Activity,
        entry: LibraryEntry,
        target: LaunchTargetEntity,
        override: StreamProfile? = null,
        quitRunning: Boolean = false,
    ) {
        when (target.type) {
            TargetType.LOCAL -> launchLocal(activity, entry, target)
            TargetType.REMOTE -> launchRemote(activity, entry, target, override, quitRunning)
        }
    }

    private suspend fun launchLocal(activity: Activity, entry: LibraryEntry, target: LaunchTargetEntity) {
        val intent = target.packageName?.let(container.localApps::launchIntent)
        if (intent == null) {
            _state.value = LaunchState.Failed(entry, LaunchError.APP_NOT_INSTALLED)
            return
        }
        container.playtime.start(entry.item.id, target.id, null)
        activity.startActivity(intent)
        _state.value = LaunchState.Idle
    }

    private suspend fun launchRemote(
        activity: Activity,
        entry: LibraryEntry,
        target: LaunchTargetEntity,
        override: StreamProfile?,
        quitRunning: Boolean,
    ) {
        val hostUuid = target.hostUuid ?: return
        var host = container.hosts.hosts.value[hostUuid]

        for (step in preStreamSteps) {
            val error = step(entry, host) { s -> _state.value = LaunchState.Working(entry, s) }
            if (error != null) {
                _state.value = LaunchState.Failed(entry, error)
                return
            }
            host = container.hosts.hosts.value[hostUuid]
        }

        if (host == null || host.status != HostStatus.ONLINE || host.details.activeAddress == null) {
            _state.value = LaunchState.Failed(entry, LaunchError.HOST_OFFLINE)
            return
        }
        if (!host.paired) {
            _state.value = LaunchState.Failed(entry, LaunchError.NOT_PAIRED)
            return
        }

        val appId = target.appId ?: return
        if (host.isStreaming && host.runningAppId != appId) {
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

        try {
            val binder = container.hosts.awaitBinder()
            val app = NvApp(target.name, target.appUuid, appId, target.hdrSupported)
            val prefs = PreferenceConfiguration.readPreferences(activity)
            val vDisplay = prefs.useVirtualDisplay && host.details.vDisplaySupported && host.details.vDisplayDriverReady
            val intent = ServerHelper.createStartIntent(activity, app, host.details, binder, vDisplay)
            val profileName = StreamProfiles.active("").name.ifEmpty { null }
            container.playtime.start(entry.item.id, target.id, profileName)
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
            _state.value = LaunchState.Failed(entry, LaunchError.START_FAILED)
        }
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
