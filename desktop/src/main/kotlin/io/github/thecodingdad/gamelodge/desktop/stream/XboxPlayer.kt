package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.desktop.SdlPads
import io.github.thecodingdad.gamelodge.input.PadKeys
import io.github.thecodingdad.gamelodge.util.Log
import io.github.thecodingdad.gamelodge.xbox.XboxPadState
import io.github.thecodingdad.gamelodge.xbox.XboxStreamController
import io.github.thecodingdad.gamelodge.xbox.XboxStreamState
import io.github.thecodingdad.gamelodge.xbox.XboxStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The controllers for the Xbox stream, from the launcher's gamepad thread (SDL reads them in the
 * background too): [sink] gets every pad's state while a stream runs; [vibrate] rumbles one.
 */
object XboxPadFeed {
    /** Every slot's state and the buttons held back by the launcher's combinations; null: no stream. */
    @Volatile var sink: ((List<Pair<SdlPads.State, Set<Int>>?>) -> Unit)? = null
    @Volatile var vibrate: (slot: Int, strength: Float, millis: Int) -> Unit = { _, _, _ -> }
    /** The stream takes the Guide button as the console's Xbox button (the dashboard). */
    @Volatile var guideToConsole = false
    /** The launcher's window is in front (the Guide is GameLodge's then). */
    @Volatile var launcherFocused = false
}

/**
 * Runs GameLodge's Xbox player (xbox/gamelodge-xbox-player.exe, a WebView2 window with the player
 * page): its messages go to the stream controller, the controller's to it (one JSON line each).
 * The process ending is the stream's end.
 */
class XboxPlayer(private val scope: CoroutineScope) {
    private val base: File by lazy {
        val jar = File(XboxPlayer::class.java.protectionDomain.codeSource.location.toURI())
        File(if (jar.isFile) jar.parentFile.parentFile else File("."), "xbox")
    }
    val exe: File get() = File(base, "gamelodge-xbox-player.exe")
    val available: Boolean get() = exe.isFile && File(base, "index.html").isFile

    @Volatile private var process: Process? = null

    /** The launcher's icon as an .ico (a PNG inside, which Windows reads since Vista) for the player window. */
    private fun streamIcon(): File? = runCatching {
        val file = File(io.github.thecodingdad.gamelodge.desktop.dataDirectory(), "cache/stream-icon.ico")
        if (!file.isFile) {
            file.parentFile.mkdirs()
            val png = XboxPlayer::class.java.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
            val header = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort(0).putShort(1).putShort(1)                 // ICONDIR: reserved, type icon, one image
                .put(0).put(0).put(0).put(0)                         // 256 x 256 (0 = 256), no palette, reserved
                .putShort(1).putShort(32).putInt(png.size).putInt(22) // planes, bit depth, size, offset
            file.writeBytes(header.array() + png)
        }
        file
    }.onFailure { Log.w("Xbox player icon not written", it) }.getOrNull()

    /** The player of a running stream is still there (Guide brought the launcher to the front): back to it. */
    fun focus(): Boolean {
        val p = process?.takeIf { it.isAlive } ?: return false
        return io.github.thecodingdad.gamelodge.desktop.games.GameWindow.focus(setOf(p.pid()))
    }

    fun start(stream: XboxStreamController, title: String? = null, onEnded: () -> Unit) {
        check(available) { "Xbox player missing (${exe.path})" }
        val data = File(io.github.thecodingdad.gamelodge.desktop.dataDirectory(), "xbox-player")
        data.mkdirs()
        val builder = ProcessBuilder(exe.path, base.path, data.path).redirectError(ProcessBuilder.Redirect.DISCARD)
        // Part of the launcher, like the Moonlight streams: its taskbar button, the game's name and the launcher's icon.
        builder.environment()["GAMELODGE_APPID"] = io.github.thecodingdad.gamelodge.desktop.TaskbarIdentity.APP_ID
        builder.environment()["GAMELODGE_TITLE"] = (title?.let { "$it – GameLodge" } ?: "GameLodge")
        streamIcon()?.let { builder.environment()["GAMELODGE_ICON"] = it.path }
        val p = builder.start()
        process = p
        val writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
        stream.send = { text ->
            synchronized(writer) { runCatching { writer.write(text.replace('\n', ' ')); writer.newLine(); writer.flush() } }
        }
        stream.onRumble = { index, strong, weak, ms -> slots.entries.firstOrNull { it.value == index }?.let { XboxPadFeed.vibrate(it.key, maxOf(strong, weak), ms) } }
        Thread({
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) stream.onMessage(line) }
            }
        }, "xbox-player-out").apply { isDaemon = true }.start()
        slots.clear()
        XboxPadFeed.guideToConsole = stream.guideToConsole
        XboxPadFeed.sink = { states -> stream.pads(padStates(states)) }
        // The stream ended (console, failure, quick menu): the window closes with the end of its input.
        scope.launch {
            stream.state.first { it is XboxStreamState.Ended || it is XboxStreamState.Failed }
            synchronized(writer) { runCatching { writer.close() } }
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        }
        p.onExit().thenRun {
            XboxPadFeed.sink = null
            XboxPadFeed.guideToConsole = false
            process = null
            stream.end()
            if (XboxStreams.current === stream) XboxStreams.current = null
            Log.i("Xbox player ended (${p.exitValue()})")
            onEnded()
        }
    }

    /** Pads on the console by the order they connected (first input takes the next free one, up to four). */
    private val slots = LinkedHashMap<Int, Int>()

    private fun padStates(states: List<Pair<SdlPads.State, Set<Int>>?>): List<XboxPadState> = states.mapIndexedNotNull { slot, entry ->
        val (s, held) = entry ?: return@mapIndexedNotNull null
        val index = slots[slot] ?: (0 until 4).firstOrNull { it !in slots.values }?.also { slots[slot] = it } ?: return@mapIndexedNotNull null
        fun on(v: Boolean, code: Int) = v && code !in held
        val view = on(s.back, PadKeys.SELECT)
        val menu = on(s.start, PadKeys.START)
        // View + Menu together: the Xbox button on the console; in the dashboard's stream also Guide
        // (while the player, not the launcher, is in front).
        val nexus = (view && menu) || (XboxPadFeed.guideToConsole && !XboxPadFeed.launcherFocused && s.guide)
        XboxPadState(
            index = index,
            a = on(s.a, PadKeys.A), b = on(s.b, PadKeys.B), x = on(s.x, PadKeys.X), y = on(s.y, PadKeys.Y),
            lb = on(s.lb, PadKeys.L1), rb = on(s.rb, PadKeys.R1),
            view = view && !nexus, menu = menu && !nexus, nexus = nexus,
            leftThumb = on(s.leftStickClick, PadKeys.THUMBL), rightThumb = on(s.rightStickClick, PadKeys.THUMBR),
            up = on(s.dpadUp, PadKeys.DPAD_UP), down = on(s.dpadDown, PadKeys.DPAD_DOWN),
            left = on(s.dpadLeft, PadKeys.DPAD_LEFT), right = on(s.dpadRight, PadKeys.DPAD_RIGHT),
            lx = s.leftStickX, ly = s.leftStickY, rx = s.rightStickX, ry = s.rightStickY,
            lt = s.leftTrigger, rt = s.rightTrigger,
        )
    }
}
