package io.github.thecodingdad.moonlightlauncher.desktop

import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.PlaytimeTracker
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.desktop.games.GameLauncher
import io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary
import io.github.thecodingdad.moonlightlauncher.stream.LaunchError
import io.github.thecodingdad.moonlightlauncher.stream.LaunchPlatform
import io.github.thecodingdad.moonlightlauncher.stream.NetworkCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts the PC's games: play time runs from the start until the game's processes are gone, then
 * the launcher comes back to the front. Owned games that aren't installed open their store's
 * installer. Streaming arrives with phase 3.
 */
class DesktopLaunches(
    private val library: PcLibrary,
    private val playtime: PlaytimeTracker,
    private val scope: CoroutineScope,
    private val onGameEnded: () -> Unit,
    private val hosts: io.github.thecodingdad.moonlightlauncher.desktop.stream.DesktopHosts? = null,
    private val moonlight: () -> io.github.thecodingdad.moonlightlauncher.desktop.stream.MoonlightQt = { io.github.thecodingdad.moonlightlauncher.desktop.stream.MoonlightQt.locate() },
    private val identity: () -> io.github.thecodingdad.moonlightlauncher.desktop.stream.StreamIdentity = { error("no identity") },
    /** Options for Moonlight-Qt from the active stream profile. */
    private val streamOptions: () -> List<String> = { emptyList() },
    /** The stream window closed (play session, profile, host state). */
    private val onStreamEnded: () -> Unit = {},
) : LaunchPlatform {
    override suspend fun launchLocal(entry: LibraryEntry, target: LaunchTargetEntity): LaunchError? {
        val game = target.packageName?.let(library::entry) ?: return LaunchError.APP_NOT_INSTALLED
        if (!game.installed) {
            if (game.install == null) return LaunchError.APP_NOT_INSTALLED
            scope.launch {
                GameLauncher.install(game) { library.installedNow(game) }
                library.rescan()
            }
            return null
        }
        val started = CompletableDeferred<Boolean>()
        scope.launch {
            playtime.start(entry.item.id, target.id, null)
            GameLauncher.play(game) { started.complete(it) }
            playtime.finishOpenSession()
            library.rescan()
            onGameEnded()
        }
        return if (started.await()) null else LaunchError.START_FAILED
    }

    override suspend fun launchRom(entry: LibraryEntry, target: LaunchTargetEntity, emulatorKey: String?) = LaunchError.NO_EMULATOR
    override fun isReachable(host: HostState) = host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.ONLINE
    override fun isRemoteConnection(host: HostState) = false
    override suspend fun measureNetwork(host: HostState): NetworkCheckResult? = null

    /** Hands the stream to the bundled Moonlight-Qt; returns once it runs, the end is followed in the background. */
    override suspend fun startStream(entry: LibraryEntry, target: LaunchTargetEntity, host: HostState) {
        val hosts = hosts ?: error("No hosts")
        val client = moonlight()
        check(client.available) { "Moonlight-Qt is missing (${client.exe})" }
        val stored = hosts.stored(host.uuid) ?: error("Unknown host")
        val appId = target.appId ?: error("No app id")
        val result = CompletableDeferred<Int>()
        scope.launch {
            val code = runCatching { client.stream(identity(), hosts.storedHosts.value, stored, appId, streamOptions()) }.getOrDefault(-1)
            result.complete(code)
            hosts.invalidate(host.uuid)
            onStreamEnded()
            onGameEnded()
        }
        // A start that fails right away (host refused, app gone) is reported instead of a silent return.
        val early = kotlinx.coroutines.withTimeoutOrNull(4_000) { result.await() }
        if (early != null && early != 0) error("Moonlight-Qt ended with $early")
    }
}
