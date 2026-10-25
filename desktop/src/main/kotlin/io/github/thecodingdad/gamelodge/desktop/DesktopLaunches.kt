package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.stream.LocalSessions

import io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.library.PlaytimeTracker
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.desktop.games.GameLauncher
import io.github.thecodingdad.gamelodge.desktop.games.PcLibrary
import io.github.thecodingdad.gamelodge.stream.LaunchError
import io.github.thecodingdad.gamelodge.stream.LaunchPlatform
import io.github.thecodingdad.gamelodge.stream.NetworkCheckResult
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
    private val hosts: io.github.thecodingdad.gamelodge.desktop.stream.DesktopHosts? = null,
    private val moonlight: () -> io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt = { io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.locate() },
    private val identity: () -> io.github.thecodingdad.gamelodge.desktop.stream.StreamIdentity = { error("no identity") },
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
            try {
                GameLauncher.play(game) { ok ->
                    // Followed while it runs: the quick menu offers resume and quit.
                    if (ok) LocalSessions.started(
                        LocalSessions.Running(
                            entry,
                            resume = { io.github.thecodingdad.gamelodge.desktop.games.GameWindow.focus(GameLauncher.gameProcesses()) },
                            quit = { io.github.thecodingdad.gamelodge.desktop.games.GameWindow.quit(GameLauncher.gameProcesses) },
                        ),
                    )
                    started.complete(ok)
                }
            } finally {
                LocalSessions.ended(entry)
            }
            playtime.finishOpenSession()
            library.rescan()
            onGameEnded()
        }
        return if (started.await()) null else LaunchError.START_FAILED
    }

    override suspend fun launchRom(entry: LibraryEntry, target: LaunchTargetEntity, emulatorKey: String?) = LaunchError.NO_EMULATOR
    override fun isReachable(host: HostState) = host.status == io.github.thecodingdad.gamelodge.data.remote.HostStatus.ONLINE
    override fun isRemoteConnection(host: HostState) = false
    override suspend fun measureNetwork(host: HostState): NetworkCheckResult? = null

    /** Hands the stream to the bundled Moonlight-Qt; returns once it runs, the end is followed in the background. */
    override suspend fun startStream(entry: LibraryEntry, target: LaunchTargetEntity, host: HostState) {
        val hosts = hosts ?: error("No hosts")
        val client = moonlight()
        check(client.available) { "Moonlight-Qt is missing (${client.exe})" }
        val stored = hosts.stored(host.uuid) ?: error("Unknown host")
        val appId = target.appId ?: error("No app id")
        // Still streaming it in the background (Guide brought the launcher to the front): back to that window.
        client.running?.takeIf { it.hostUuid == host.uuid && it.appId == appId && it.process.isAlive }?.let { r ->
            val pids = setOf(r.process.pid()) + r.process.descendants().map { it.pid() }.toList()
            if (io.github.thecodingdad.gamelodge.desktop.games.GameWindow.focus(pids)) return
        }
        val start = CompletableDeferred<io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.Start>()
        scope.launch {
            runCatching {
                val apps = hosts.appLists.value.mapValues { (_, list) ->
                    list.map { io.github.thecodingdad.gamelodge.desktop.stream.MoonlightIni.App(it.app.appId, it.app.appName, it.app.isHdrSupported) }
                }
                hosts.quietForStream()
                try {
                    client.stream(identity(), hosts.storedHosts.value, stored, appId, streamOptions(), apps) {
                        if (it is io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.Start.Streaming) hosts.markRunning(host.uuid, appId)
                        start.complete(it)
                    }
                } finally {
                    hosts.streamEnded()
                }
            }.onFailure { start.complete(io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.Start.Failed(it.message)) }
            hosts.invalidate(host.uuid)
            onStreamEnded()
            onGameEnded()
        }
        // The launcher's start overlay stays until the stream runs; Moonlight-Qt's errors show there.
        val result = start.await()
        if (result is io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.Start.Failed) {
            error(result.message ?: "Moonlight-Qt")
        }
    }
}
