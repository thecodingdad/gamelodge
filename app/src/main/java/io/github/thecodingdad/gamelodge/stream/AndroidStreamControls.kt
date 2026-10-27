package io.github.thecodingdad.gamelodge.stream

import android.os.Handler
import android.os.Looper
import com.limelight.Game
import com.limelight.StreamControl
import kotlinx.coroutines.flow.first
import io.github.thecodingdad.gamelodge.data.remote.HostState

/** Artemis' stream activity runs in this process: steered directly (and via its embedding hook). */
class AndroidStreamControls(
    private val launcher: LaunchCoordinator,
    private val hostActions: io.github.thecodingdad.gamelodge.data.remote.HostActions,
) : StreamControls {
    private val main = Handler(Looper.getMainLooper())
    private val game: Game? get() = Game.instance

    override fun serverCommands(): List<String> = game?.serverCmds.orEmpty()

    override fun runServerCommand(index: Int) {
        val g = game ?: return
        Thread({ runCatching { g.sendExecServerCmd(index) } }, "server-command").start()
    }

    override fun disconnect() {
        val g = game ?: return
        main.post { g.disconnect() }
    }

    override fun inFront(): Boolean = game.let { it != null && io.github.thecodingdad.gamelodge.CurrentActivity.resumed() === it }

    override fun toggleOverlay(): Boolean = game != null && StreamControl.togglePerformanceOverlay()

    /**
     * Leaves the stream, then quits the game on the host here: the host answers only once the game
     * has closed (that can take a while), and the caller learns when (no toast from Artemis).
     */
    override suspend fun quit(host: HostState): Boolean {
        val g = game ?: return false
        if (launcher.activeStream.value?.hostUuid != host.uuid) return false
        main.post { g.disconnect() }
        // The stream is gone before the host is asked (no "connection ended" error there).
        kotlinx.coroutines.withTimeoutOrNull(STREAM_END_MS) {
            launcher.activeStream.first { it?.hostUuid != host.uuid }
        }
        hostActions.quitApp(host)
        return true
    }

    private companion object {
        const val STREAM_END_MS = 10_000L
    }
}
