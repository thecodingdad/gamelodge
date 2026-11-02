package io.github.thecodingdad.gamelodge.stream

import com.limelight.nvstream.http.NvApp
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.ServerHelper
import io.github.thecodingdad.gamelodge.AppContainer
import io.github.thecodingdad.gamelodge.CurrentActivity
import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.emulation.EmulatorLauncher
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.util.Log

/** Starts apps and emulators with intents and streams with Artemis' Game activity. */
class AndroidLaunchPlatform(private val container: AppContainer) : LaunchPlatform {
    private fun activity() = CurrentActivity.get() ?: error("No visible activity")

    override suspend fun launchLocal(entry: LibraryEntry, target: LaunchTargetEntity, action: String?): LaunchError? {
        val intent = target.packageName?.let(container.localApps::launchIntent) ?: return LaunchError.APP_NOT_INSTALLED
        // With usage access Android's statistics count the time (see UsageSync).
        if (!container.usage.granted) container.playtime.start(entry.item.id, target.id, null)
        activity().startActivity(intent)
        return null
    }

    override suspend fun launchRom(entry: LibraryEntry, target: LaunchTargetEntity, emulatorKey: String?): LaunchError? {
        val activity = activity()
        val platform = target.romPlatform
        val uri = target.romUri
        val choice = if (platform != null) container.emulation.resolve(platform, emulatorKey ?: target.emulatorKey) else null
        if (choice == null || uri == null) return LaunchError.NO_EMULATOR
        return when (val result = EmulatorLauncher.intent(activity, choice, uri, target.romPath)) {
            is EmulatorLauncher.Result.Problem -> LaunchError.ROM_NEEDS_PATH
            is EmulatorLauncher.Result.Ok -> {
                val started = runCatching { activity.startActivity(result.intent) }
                    .onFailure { Log.e("Emulator start failed (${choice.key})", it) }
                // Android's usage data only sees the emulator, not the game: always measured here.
                if (started.isSuccess) container.playtime.start(entry.item.id, target.id, null)
                if (started.isSuccess) null else LaunchError.START_FAILED
            }
        }
    }

    override fun isReachable(host: HostState) = container.hosts.details(host.uuid)?.activeAddress != null

    override fun isRemoteConnection(host: HostState): Boolean {
        val d = container.hosts.details(host.uuid) ?: return false
        // Using the host's remote address means we are not in the same network.
        return d.activeAddress != null && d.activeAddress == d.remoteAddress && d.remoteAddress != d.localAddress
    }

    override suspend fun measureNetwork(host: HostState) = NetworkCheck.measure(container.hosts, host)

    override suspend fun startStream(entry: LibraryEntry, target: LaunchTargetEntity, host: HostState) {
        val activity = activity()
        val details = container.hosts.details(host.uuid) ?: error("Host ${host.name} unknown")
        val binder = container.hosts.awaitBinder()
        val app = NvApp(target.name, target.appUuid, target.appId ?: error("No app id"), target.hdrSupported)
        val prefs = PreferenceConfiguration.readPreferences(activity)
        val vDisplay = prefs.useVirtualDisplay && details.vDisplaySupported && details.vDisplayDriverReady
        // The controller that started the stream becomes player 1; optionally the connected pads
        // attach at stream start instead of on their first input.
        io.github.thecodingdad.gamelodge.input.StarterPad.capture(true)
        com.limelight.binding.input.ControllerOverrides.attachAtStart = container.settings.current().controllerAttachAtStart
        com.limelight.binding.input.ControllerOverrides.starterDescriptor = io.github.thecodingdad.gamelodge.input.StarterPad.starter
        activity.startActivity(ServerHelper.createStartIntent(activity, app, details, binder, vDisplay))
    }
}
