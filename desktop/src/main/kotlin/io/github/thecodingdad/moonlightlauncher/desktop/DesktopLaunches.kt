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
    override fun isReachable(host: HostState) = false
    override fun isRemoteConnection(host: HostState) = false
    override suspend fun measureNetwork(host: HostState): NetworkCheckResult? = null
    override suspend fun startStream(entry: LibraryEntry, target: LaunchTargetEntity, host: HostState) = error("Streaming is not available yet")
}
