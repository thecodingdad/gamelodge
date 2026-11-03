package io.github.thecodingdad.gamelodge.desktop

import com.studiohartman.jamepad.ControllerIndex
import com.studiohartman.jamepad.ControllerManager
import com.studiohartman.jamepad.ControllerPowerLevel
import com.studiohartman.jamepad.ControllerState
import io.github.thecodingdad.gamelodge.input.ButtonCombos
import io.github.thecodingdad.gamelodge.input.ComboAction
import io.github.thecodingdad.gamelodge.input.PadKeys
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.KeyEvent
import kotlin.math.abs

/**
 * Game controllers through SDL (Jamepad): Xbox, PlayStation, Switch and most other pads with the
 * standard layout. A, B, the D-pad and the left stick become key presses in the launcher window
 * (Enter, Escape, arrows), so focus, clicks and back work exactly as with a keyboard; the other
 * buttons and the right stick go to the [InputDispatcher] like on Android.
 *
 * Polls only while the launcher window has the focus: a game or stream in front gets the pads alone,
 * unless the quick menu is shown over it ([overlay]): then they drive that window.
 * The button combinations ([ButtonCombos]) are watched always; their buttons still reach the game
 * (Windows can't hold them back), only the launcher's own UI ignores them. While the mouse
 * emulation is on, the pads drive the Windows mouse pointer instead ([WindowsPointer]).
 */
class Gamepads(private val dispatcher: InputDispatcher) {
    private val manager = ControllerManager()
    private val _controllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    val controllers: StateFlow<List<ControllerInfo>> = _controllers.asStateFlow()

    /** Pads with their own layout, by name ([ControllerInfo.key] on the PC): true = Nintendo. */
    @Volatile var layoutOverrides: Map<String, Boolean> = emptyMap()

    /** Names of the connected pads by index (for their own layout). */
    private val names = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Called on every pad input (screensaver, sounds). */
    @Volatile var onInput: () -> Unit = {}

    /** The pad was the last input device (touch, mouse or keyboard reset it): only then it rumbles. */
    @Volatile var padInUse = false
        private set

    /** True while a key event made from pad input is being delivered (tells it from the keyboard). */
    @Volatile var dispatching = false
        private set

    /**
     * The pad used last, while it was the last input device: "<n>:<name>", the n-th (from 0) connected
     * pad of that name in SDL's order (Moonlight-Qt finds the same one; pads report no serial here).
     */
    fun starter(): String? {
        if (!padInUse) return null
        val index = lastUsed
        val name = names[index] ?: return null
        // Read from the poll thread's view (SDL is only used there).
        val before = previous.keys.count { i -> i < index && names[i] == name }
        return "$before:$name"
    }

    /** Touch, mouse or keyboard was used. */
    fun otherInput() {
        padInUse = false
    }

    /** A button combination of pad [Int] fired; called on the poll or combo thread. */
    @Volatile var onCombo: (ComboAction, Int) -> Unit = { _, _ -> }

    /** The stream is in front (it has its own mouse emulation then); asked twice a second at most. */
    @Volatile var streamInFront: () -> Boolean = { false }
    private var streamCheckedAt = 0L
    private var streamFront = false
    private val comboKeys = HashMap<Int, Set<Int>>() // pad -> buttons down (Android key codes)
    private val hidden = HashMap<Int, MutableSet<Int>>() // pad -> buttons a combination took

    /** Called when B goes down (back sound, like the Android activity plays it). */
    @Volatile var onBack: () -> Unit = {}

    /** A window the pads drive while it is shown over a game (it never takes the focus), with its own dispatcher. */
    class Overlay(val window: Window, val dispatcher: InputDispatcher)

    @Volatile var overlay: Overlay? = null
    private var lastOverlay: Overlay? = null

    @Volatile private var window: Window? = null

    /** Where pad input goes: the overlay while it is shown, else the launcher window. */
    private val ui: InputDispatcher get() = overlay?.dispatcher ?: dispatcher
    private var started = false
    private var lastUsed = 0
    private val previous = java.util.concurrent.ConcurrentHashMap<Int, Pad>()
    private val held = HashMap<String, Long>() // repeating key -> next repeat time

    fun start(window: Window) {
        this.window = window
        if (started) return
        started = true
        // A short press a hold combination held back from the launcher's UI reaches it after all.
        ButtonCombos.replay = { press, _ -> if (overlay != null || window.isActive) replayToUi(press as Int) }
        Thread({ loop() }, "gamepads").apply { isDaemon = true }.start()
    }

    /** Short rumble on a controller (test in the controller list) or on the one used last. */
    fun vibrate(id: Int = lastUsed, strength: Float = 0.6f, millis: Int = 250) {
        synchronized(manager) { runCatching { manager.getControllerIndex(id).doVibration(strength, strength, millis) } }
    }

    private fun loop() {
        try {
            synchronized(manager) { manager.initSDLGamepad() }
        } catch (e: Throwable) {
            Log.e("Gamepad support unavailable", e)
            return
        }
        var lastList = 0L
        while (true) {
            val shownOver = overlay
            val focused = shownOver != null || window?.let { it.isActive || it.isFocused } == true
            try {
                synchronized(manager) {
                    // Switched between the launcher and the overlay: start over (nothing held carries across).
                    if (shownOver !== lastOverlay) {
                        lastOverlay = shownOver
                        held.clear()
                        previous.clear()
                    }
                    manager.update()
                    val now = System.currentTimeMillis()
                    if (now - lastList > 2_000) {
                        val list = list()
                        if (list.map { it.name } != _controllers.value.map { it.name }) Log.i("Controllers: ${list.joinToString { it.name }.ifEmpty { "none" }}")
                        _controllers.value = list
                        lastList = now
                    }
                    combos(now)
                    if (WindowsPointer.enabled && now - streamCheckedAt > 500) {
                        streamCheckedAt = now
                        val front = streamInFront()
                        if (front && !streamFront) WindowsPointer.pause()
                        streamFront = front
                    }
                    if (WindowsPointer.enabled && !streamFront && shownOver == null) {
                        release()
                        pointer(now)
                    } else if (focused) {
                        poll(now)
                    } else {
                        release()
                    }
                }
            } catch (e: Exception) {
                // A pad unplugged mid-poll and the like: log it and carry on with the next round.
                Log.w("Gamepad poll failed", e)
            }
            // In the background often enough to catch a short Guide press.
            Thread.sleep(if (focused || WindowsPointer.enabled) 16 else 50)
        }
    }

    /** Feeds button changes of every pad into the combinations (also with a game in front). */
    private fun combos(now: Long) {
        for (i in 0 until manager.numControllers) {
            val s = runCatching { manager.getState(i) }.getOrNull()?.takeIf { it.isConnected }
            val keys = s?.let(::keysOf).orEmpty()
            val before = comboKeys[i].orEmpty()
            if (keys == before) continue
            comboKeys[i] = keys
            val taken = hidden.getOrPut(i) { HashSet() }
            for (code in before - keys) {
                ButtonCombos.handle(i, code, false, 0, now, names[i], code, onCombo)
                taken -= code
            }
            for (code in keys - before) {
                if (ButtonCombos.handle(i, code, true, 0, now, names[i], code, onCombo)) taken += code
            }
        }
    }

    private fun keysOf(s: ControllerState): Set<Int> = buildSet {
        if (s.a) add(PadKeys.A)
        if (s.b) add(PadKeys.B)
        if (s.x) add(PadKeys.X)
        if (s.y) add(PadKeys.Y)
        if (s.lb) add(PadKeys.L1)
        if (s.rb) add(PadKeys.R1)
        if (s.leftTrigger > 0.5f) add(PadKeys.L2)
        if (s.rightTrigger > 0.5f) add(PadKeys.R2)
        if (s.leftStickClick) add(PadKeys.THUMBL)
        if (s.rightStickClick) add(PadKeys.THUMBR)
        if (s.start) add(PadKeys.START)
        if (s.back) add(PadKeys.SELECT)
        if (s.guide) add(PadKeys.MODE)
        if (s.dpadUp) add(PadKeys.DPAD_UP)
        if (s.dpadDown) add(PadKeys.DPAD_DOWN)
        if (s.dpadLeft) add(PadKeys.DPAD_LEFT)
        if (s.dpadRight) add(PadKeys.DPAD_RIGHT)
    }

    /** A pad's state for the launcher UI, without the buttons a combination took. */
    private data class Pad(
        val a: Boolean, val b: Boolean, val x: Boolean, val y: Boolean, val lb: Boolean, val rb: Boolean,
        val start: Boolean, val back: Boolean, val leftStickClick: Boolean, val rightStickClick: Boolean,
        val dpadUp: Boolean, val dpadDown: Boolean, val dpadLeft: Boolean, val dpadRight: Boolean,
        val leftStickX: Float, val leftStickY: Float, val rightStickX: Float, val rightStickY: Float,
        val leftTrigger: Float, val rightTrigger: Float,
    )

    private fun padOf(s: ControllerState, taken: Set<Int>): Pad {
        fun on(code: Int, value: Boolean) = value && code !in taken
        return Pad(
            on(PadKeys.A, s.a), on(PadKeys.B, s.b), on(PadKeys.X, s.x), on(PadKeys.Y, s.y),
            on(PadKeys.L1, s.lb), on(PadKeys.R1, s.rb), on(PadKeys.START, s.start), on(PadKeys.SELECT, s.back),
            on(PadKeys.THUMBL, s.leftStickClick), on(PadKeys.THUMBR, s.rightStickClick),
            on(PadKeys.DPAD_UP, s.dpadUp), on(PadKeys.DPAD_DOWN, s.dpadDown), on(PadKeys.DPAD_LEFT, s.dpadLeft), on(PadKeys.DPAD_RIGHT, s.dpadRight),
            s.leftStickX, s.leftStickY, s.rightStickX, s.rightStickY,
            if (PadKeys.L2 in taken) 0f else s.leftTrigger, if (PadKeys.R2 in taken) 0f else s.rightTrigger,
        )
    }

    /** Mouse emulation: every pad steers the Windows pointer (buttons a combination took excluded). */
    private fun pointer(now: Long) {
        for (i in 0 until manager.numControllers) {
            val s = runCatching { manager.getState(i) }.getOrNull()?.takeIf { it.isConnected } ?: continue
            val p = padOf(s, hidden[i].orEmpty())
            WindowsPointer.update(i, p.leftStickX, p.leftStickY, p.rightStickX, p.rightStickY,
                p.a, p.b, p.x, p.lb, p.rb, p.dpadUp, p.dpadDown, p.dpadLeft, p.dpadRight, now)
        }
    }

    private fun replayToUi(code: Int) {
        val now = System.currentTimeMillis()
        val keys = mapOf(
            PadKeys.A to KeyEvent.VK_ENTER, PadKeys.B to KeyEvent.VK_ESCAPE, PadKeys.DPAD_UP to KeyEvent.VK_UP,
            PadKeys.DPAD_DOWN to KeyEvent.VK_DOWN, PadKeys.DPAD_LEFT to KeyEvent.VK_LEFT, PadKeys.DPAD_RIGHT to KeyEvent.VK_RIGHT,
        )
        val buttons = mapOf(
            PadKeys.X to PadButton.X, PadKeys.Y to PadButton.Y, PadKeys.L1 to PadButton.L1, PadKeys.R1 to PadButton.R1,
            PadKeys.START to PadButton.START, PadKeys.SELECT to PadButton.SELECT,
        )
        keys[code]?.let { press(it, released = true) }
        buttons[code]?.let { b ->
            EventQueue.invokeLater {
                ui.dispatchButton(b, InputDispatcher.KeyPhase.DOWN, 0, now)
                ui.dispatchButton(b, InputDispatcher.KeyPhase.UP, 0, now)
            }
        }
    }

    private fun list(): List<ControllerInfo> = (0 until manager.numControllers).mapNotNull { i ->
        val index: ControllerIndex = manager.getControllerIndex(i)
        if (!index.isConnected) return@mapNotNull null
        runCatching {
            names[i] = index.name
            ControllerInfo(
                id = i,
                name = index.name,
                external = true,
                batteryPercent = when (index.powerLevel) {
                    ControllerPowerLevel.POWER_EMPTY -> 5
                    ControllerPowerLevel.POWER_LOW -> 20
                    ControllerPowerLevel.POWER_MEDIUM -> 60
                    ControllerPowerLevel.POWER_FULL -> 100
                    else -> null // wired or unknown
                },
                canVibrate = index.canVibrate(),
            )
        }.getOrNull()
    }

    private fun poll(now: Long) {
        for (i in 0 until manager.numControllers) {
            val state = manager.getState(i)
            if (!state.isConnected) {
                previous.remove(i)
                continue
            }
            val s = padOf(state, hidden[i].orEmpty())
            val p = previous[i]
            previous[i] = s
            if (p == null) continue
            if (changed(p, s)) {
                io.github.thecodingdad.gamelodge.input.KeyboardKeys.onOtherInput()
                lastUsed = i
                padInUse = true
                names[i]?.let { name ->
                    java.awt.EventQueue.invokeLater {
                        io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.key = name
                        io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.detectedLayout = modelLayout(name)
                    }
                }
                onInput()
            }
            // The pad's own layout, else its model's (a Nintendo pad confirms on the right button).
            handle(p, s, now, names[i]?.let { layoutOverrides[it] ?: (modelLayout(it) == io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.NINTENDO) } ?: false)
        }
        repeatHeld(now)
    }

    private fun changed(p: Pad, s: Pad) =
        p.a != s.a || p.b != s.b || p.x != s.x || p.y != s.y || p.lb != s.lb || p.rb != s.rb || p.start != s.start ||
            p.back != s.back || p.dpadUp != s.dpadUp || p.dpadDown != s.dpadDown || p.dpadLeft != s.dpadLeft ||
            p.dpadRight != s.dpadRight || abs(p.leftStickX - s.leftStickX) > 0.2f || abs(p.leftStickY - s.leftStickY) > 0.2f ||
            abs(p.rightStickX - s.rightStickX) > 0.2f || abs(p.rightStickY - s.rightStickY) > 0.2f ||
            abs(p.leftTrigger - s.leftTrigger) > 0.2f || abs(p.rightTrigger - s.rightTrigger) > 0.2f

    private fun handle(p: Pad, s: Pad, now: Long, nintendoLayout: Boolean) {
        val (confirm, back) = if (nintendoLayout) s.b to s.a else s.a to s.b
        val (wasConfirm, wasBack) = if (nintendoLayout) p.b to p.a else p.a to p.b
        val (y, x) = if (nintendoLayout) s.x to s.y else s.y to s.x
        val (wasY, wasX) = if (nintendoLayout) p.x to p.y else p.y to p.x

        // The setup wizard's button test: buttons only light up there, nothing else happens.
        if (overlay == null && dispatcher.buttonTest != null) {
            listOf(
                "A" to (wasConfirm to confirm), "B" to (wasBack to back), "X" to (wasX to x), "Y" to (wasY to y),
                "LB" to (p.lb to s.lb), "RB" to (p.rb to s.rb), "START" to (p.start to s.start), "SELECT" to (p.back to s.back),
                "L3" to (p.leftStickClick to s.leftStickClick), "R3" to (p.rightStickClick to s.rightStickClick),
                "LT" to ((p.leftTrigger > 0.5f) to (s.leftTrigger > 0.5f)), "RT" to ((p.rightTrigger > 0.5f) to (s.rightTrigger > 0.5f)),
                "UP" to (p.dpadUp to s.dpadUp), "DOWN" to (p.dpadDown to s.dpadDown), "LEFT" to (p.dpadLeft to s.dpadLeft), "RIGHT" to (p.dpadRight to s.dpadRight),
            ).forEach { (name, change) -> if (change.first != change.second) EventQueue.invokeLater { dispatcher.reportTest(name, change.second) } }
            return
        }
        key(KeyEvent.VK_ENTER, wasConfirm, confirm)
        if (back && !wasBack) onBack()
        key(KeyEvent.VK_ESCAPE, wasBack, back)

        // D-pad and left stick: arrows that repeat while held. SDL reports stick down as positive.
        direction("up", p.dpadUp || p.leftStickY < -STICK, s.dpadUp || s.leftStickY < -STICK, KeyEvent.VK_UP, now)
        direction("down", p.dpadDown || p.leftStickY > STICK, s.dpadDown || s.leftStickY > STICK, KeyEvent.VK_DOWN, now)
        direction("left", p.dpadLeft || p.leftStickX < -STICK, s.dpadLeft || s.leftStickX < -STICK, KeyEvent.VK_LEFT, now)
        direction("right", p.dpadRight || p.leftStickX > STICK, s.dpadRight || s.leftStickX > STICK, KeyEvent.VK_RIGHT, now)

        button(PadButton.Y, wasY, y, now)
        button(PadButton.X, wasX, x, now)
        button(PadButton.L1, p.lb, s.lb, now)
        button(PadButton.R1, p.rb, s.rb, now)
        button(PadButton.START, p.start, s.start, now)
        button(PadButton.SELECT, p.back, s.back, now)
        // Same orientation as Android's axes (down positive). Only on change: 60 calls a second
        // on the UI thread for a resting stick cost frames for nothing.
        if (p.leftTrigger != s.leftTrigger || p.rightTrigger != s.rightTrigger || p.rightStickX != s.rightStickX || p.rightStickY != s.rightStickY) {
            val target = ui
            EventQueue.invokeLater { target.dispatchAxes(s.leftTrigger, s.rightTrigger, s.rightStickX, s.rightStickY, now) }
        }
    }

    private fun button(button: PadButton, was: Boolean, now: Boolean, time: Long) {
        if (was == now) return
        val phase = if (now) InputDispatcher.KeyPhase.DOWN else InputDispatcher.KeyPhase.UP
        val target = ui
        EventQueue.invokeLater { target.dispatchButton(button, phase, 0, time) }
    }

    private fun direction(name: String, was: Boolean, now: Boolean, code: Int, time: Long) {
        when {
            now && !was -> {
                press(code)
                held[name] = time + FIRST_REPEAT_MS
            }
            !now && was -> {
                held.remove(name)
                post(code, KeyEvent.KEY_RELEASED)
            }
        }
    }

    private fun repeatHeld(now: Long) {
        for ((name, next) in held.entries.toList()) {
            if (now < next) continue
            press(DIRECTIONS.getValue(name), released = false)
            held[name] = now + REPEAT_MS
        }
    }

    /** A keyboard key assigned to confirm / back: sent like the pad's (Enter / Esc). */
    fun postKey(code: Int, pressed: Boolean) = post(code, if (pressed) KeyEvent.KEY_PRESSED else KeyEvent.KEY_RELEASED)

    private fun key(code: Int, was: Boolean, now: Boolean) {
        if (now && !was) post(code, KeyEvent.KEY_PRESSED)
        if (!now && was) post(code, KeyEvent.KEY_RELEASED)
    }

    private fun press(code: Int, released: Boolean = false) {
        post(code, KeyEvent.KEY_PRESSED)
        if (released) post(code, KeyEvent.KEY_RELEASED)
    }

    private fun post(code: Int, type: Int) {
        val shownOver = overlay?.window
        val target = shownOver ?: window ?: return
        EventQueue.invokeLater {
            // The overlay never has the focus: its content takes the keys directly.
            // A window as focus owner (not its content) would drop them: the content then.
            val focusOwner = (if (shownOver != null) keyReceiver(shownOver) else target.mostRecentFocusOwner?.takeUnless { it is Window })
                ?: keyReceiver(target) ?: target
            dispatching = true
            try {
                // Char 0 (a control character): text fields must not take controller buttons as typed text
                // (CHAR_UNDEFINED is U+FFFF, which they would insert).
                focusOwner.dispatchEvent(KeyEvent(focusOwner, type, System.currentTimeMillis(), 0, code, NO_CHAR))
            } finally {
                dispatching = false
            }
        }
    }

    /** Window lost the focus: let go of everything that is still held. */
    private fun release() {
        if (held.isEmpty() && previous.isEmpty()) return
        held.keys.forEach { post(DIRECTIONS.getValue(it), KeyEvent.KEY_RELEASED) }
        held.clear()
        previous.clear()
    }

    companion object {
        /** The component of [window] that handles key events (Compose's drawing surface). */
        fun keyReceiver(window: java.awt.Container): java.awt.Component? {
            for (child in window.components) {
                if (child.keyListeners.isNotEmpty()) return child
                if (child is java.awt.Container) keyReceiver(child)?.let { return it }
            }
            return null
        }

        private const val STICK = 0.6f
        private const val FIRST_REPEAT_MS = 350L
        private const val NO_CHAR = '\u0000'
        private const val REPEAT_MS = 90L
        private val DIRECTIONS = mapOf("up" to KeyEvent.VK_UP, "down" to KeyEvent.VK_DOWN, "left" to KeyEvent.VK_LEFT, "right" to KeyEvent.VK_RIGHT)
    }
}

/** The layout of a pad's model, guessed from its name (null = unknown → Xbox). */
fun modelLayout(name: String): io.github.thecodingdad.gamelodge.data.settings.ButtonLayout? =
    io.github.thecodingdad.gamelodge.ui.screens.settings.buttonLayoutForVendor(
        io.github.thecodingdad.gamelodge.ui.screens.settings.controllerIdsFromName(name).first,
    )
