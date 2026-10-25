package io.github.thecodingdad.gamelodge.stream

import android.os.Handler
import android.os.Looper
import com.limelight.Game
import com.limelight.StreamControl
import io.github.thecodingdad.gamelodge.data.remote.HostState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Artemis' stream activity runs in this process: steered directly (and via its embedding hook). */
class AndroidStreamControls(private val launcher: LaunchCoordinator) : StreamControls {
    private val main = Handler(Looper.getMainLooper())
    private val game: Game? get() = Game.instance

    override fun stats(): Flow<String> = callbackFlow {
        val listener = StreamControl.PerfListener { trySend(it.trim()) }
        StreamControl.perfListener = listener
        awaitClose { if (StreamControl.perfListener === listener) StreamControl.perfListener = null }
    }

    override val keyboard: Boolean get() = game != null

    // After the menu has closed, so the keyboard opens on the stream window.
    override fun showKeyboard() {
        main.postDelayed({ game?.toggleKeyboard() }, MENU_CLOSE_MS)
    }

    override fun serverCommands(): List<String> = game?.serverCmds.orEmpty()

    override fun runServerCommand(index: Int) {
        val g = game ?: return
        Thread({ runCatching { g.sendExecServerCmd(index) } }, "server-command").start()
    }

    override fun disconnect() {
        val g = game ?: return
        main.post { g.disconnect() }
    }

    override suspend fun quit(host: HostState): Boolean {
        val g = game ?: return false
        if (launcher.activeStream.value?.hostUuid != host.uuid) return false
        main.post { g.quitWithoutConfirmation() }
        return true
    }

    private companion object {
        const val MENU_CLOSE_MS = 350L
    }
}
