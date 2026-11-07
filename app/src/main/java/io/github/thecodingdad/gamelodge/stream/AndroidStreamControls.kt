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
    private val context: android.content.Context,
) : StreamControls {
    private val main = Handler(Looper.getMainLooper())
    private val game: Game? get() = Game.instance

    override fun serverCommands(): List<String> = game?.serverCmds.orEmpty()

    override fun runServerCommand(index: Int) {
        val g = game ?: return
        Thread({ runCatching { g.sendExecServerCmd(index) } }, "server-command").start()
    }

    override fun disconnect() {
        if (io.github.thecodingdad.gamelodge.xbox.XboxStreams.current != null) return XboxStreamActivity.finishRunning()
        val g = game ?: return
        main.post { g.disconnect() }
    }

    override fun inFront(): Boolean = io.github.thecodingdad.gamelodge.CurrentActivity.resumed().let {
        it is XboxStreamActivity || (game != null && it === game)
    }

    override fun toggleOverlay(): Boolean = game != null && StreamControl.togglePerformanceOverlay()

    override fun touchModes(): List<Pair<Int, String>> {
        val names = context.resources.getStringArray(com.limelight.R.array.mouse_mode_names)
        return StreamControl.touchModes().filter { it in names.indices }.map { it to names[it] }
    }

    override fun touchMode(): Int = StreamControl.touchMode()

    override fun setTouchMode(mode: Int): Boolean = StreamControl.setTouchMode(mode)

    override val hasKeyboard: Boolean get() = true

    /** The keyboard the settings chose (Settings › Touch, mouse & keyboard), fed from the settings. */
    @Volatile var pcKeyboard = false

    override fun toggleKeyboard(): Boolean = StreamControl.showKeyboard(pcKeyboard)

    /** From the quick menu over the stream: the stream's own mouse (no pointer service needed). */
    override fun toggleMouse(): Boolean {
        if (game == null) return false
        main.post {
            val on = !io.github.thecodingdad.gamelodge.input.PointerMode.enabled
            io.github.thecodingdad.gamelodge.input.PointerMode.set(on)
            android.widget.Toast.makeText(context, if (on) io.github.thecodingdad.gamelodge.R.string.pointer_on else io.github.thecodingdad.gamelodge.R.string.pointer_off, android.widget.Toast.LENGTH_SHORT).show()
        }
        return true
    }

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
