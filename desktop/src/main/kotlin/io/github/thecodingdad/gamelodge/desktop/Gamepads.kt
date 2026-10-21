package io.github.thecodingdad.gamelodge.desktop

import com.studiohartman.jamepad.ControllerIndex
import com.studiohartman.jamepad.ControllerManager
import com.studiohartman.jamepad.ControllerPowerLevel
import com.studiohartman.jamepad.ControllerState
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
 * Polls only while the launcher window has the focus: a game or stream in front gets the pads alone.
 * Only the Guide button is watched always ([onGuide]); it still reaches the game as well.
 */
class Gamepads(private val dispatcher: InputDispatcher) {
    private val manager = ControllerManager()
    private val _controllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    val controllers: StateFlow<List<ControllerInfo>> = _controllers.asStateFlow()

    /** Nintendo layout: confirm on the right button (A/B and X/Y swapped); the default of all pads. */
    @Volatile var nintendoLayout = false

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

    /** Touch, mouse or keyboard was used. */
    fun otherInput() {
        padInUse = false
    }

    /** Guide released after a short press (false) or held for [GUIDE_HOLD_MS] (true); called on the poll thread. */
    @Volatile var onGuide: (held: Boolean) -> Unit = {}
    private val guideDownAt = HashMap<Int, Long>() // pad -> press time, -1 once the hold fired

    /** Called when B goes down (back sound, like the Android activity plays it). */
    @Volatile var onBack: () -> Unit = {}

    @Volatile private var window: Window? = null
    private var started = false
    private var lastUsed = 0
    private val previous = HashMap<Int, ControllerState>()
    private val held = HashMap<String, Long>() // repeating key -> next repeat time
    private var selectDownAt = 0L
    private var selectRepeats = 0

    fun start(window: Window) {
        this.window = window
        if (started) return
        started = true
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
            val focused = window?.let { it.isActive || it.isFocused } == true
            try {
                synchronized(manager) {
                    manager.update()
                    val now = System.currentTimeMillis()
                    if (now - lastList > 2_000) {
                        val list = list()
                        if (list.map { it.name } != _controllers.value.map { it.name }) Log.i("Controllers: ${list.joinToString { it.name }.ifEmpty { "none" }}")
                        _controllers.value = list
                        lastList = now
                    }
                    guide(now)
                    if (focused) poll(now) else release()
                }
            } catch (e: Exception) {
                // A pad unplugged mid-poll and the like: log it and carry on with the next round.
                Log.w("Gamepad poll failed", e)
            }
            // In the background often enough to catch a short Guide press.
            Thread.sleep(if (focused) 16 else 50)
        }
    }

    private fun guide(now: Long) {
        for (i in 0 until manager.numControllers) {
            val down = runCatching { manager.getState(i) }.getOrNull()?.let { it.isConnected && it.guide } == true
            val since = guideDownAt[i]
            when {
                down && since == null -> guideDownAt[i] = now
                down && since != null && since >= 0 && now - since >= GUIDE_HOLD_MS -> {
                    guideDownAt[i] = -1
                    onGuide(true)
                }
                !down && since != null -> {
                    guideDownAt.remove(i)
                    if (since >= 0) onGuide(false)
                }
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
            val s = manager.getState(i)
            if (!s.isConnected) {
                previous.remove(i)
                continue
            }
            val p = previous[i]
            previous[i] = s
            if (p == null) continue
            if (changed(p, s)) {
                lastUsed = i
                padInUse = true
                names[i]?.let { name -> java.awt.EventQueue.invokeLater { io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.key = name } }
                onInput()
            }
            handle(p, s, now, names[i]?.let { layoutOverrides[it] } ?: nintendoLayout)
        }
        repeatHeld(now)
    }

    private fun changed(p: ControllerState, s: ControllerState) =
        p.a != s.a || p.b != s.b || p.x != s.x || p.y != s.y || p.lb != s.lb || p.rb != s.rb || p.start != s.start ||
            p.back != s.back || p.dpadUp != s.dpadUp || p.dpadDown != s.dpadDown || p.dpadLeft != s.dpadLeft ||
            p.dpadRight != s.dpadRight || abs(p.leftStickX - s.leftStickX) > 0.2f || abs(p.leftStickY - s.leftStickY) > 0.2f ||
            abs(p.rightStickX - s.rightStickX) > 0.2f || abs(p.rightStickY - s.rightStickY) > 0.2f ||
            abs(p.leftTrigger - s.leftTrigger) > 0.2f || abs(p.rightTrigger - s.rightTrigger) > 0.2f

    private fun handle(p: ControllerState, s: ControllerState, now: Long, nintendoLayout: Boolean) {
        val (confirm, back) = if (nintendoLayout) s.b to s.a else s.a to s.b
        val (wasConfirm, wasBack) = if (nintendoLayout) p.b to p.a else p.a to p.b
        val (y, x) = if (nintendoLayout) s.x to s.y else s.y to s.x
        val (wasY, wasX) = if (nintendoLayout) p.x to p.y else p.y to p.x

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
        select(p.back, s.back, now)
        // Same orientation as Android's axes (down positive). Only on change: 60 calls a second
        // on the UI thread for a resting stick cost frames for nothing.
        if (p.leftTrigger != s.leftTrigger || p.rightTrigger != s.rightTrigger || p.rightStickX != s.rightStickX || p.rightStickY != s.rightStickY) {
            EventQueue.invokeLater { dispatcher.dispatchAxes(s.leftTrigger, s.rightTrigger, s.rightStickX, s.rightStickY, now) }
        }
    }

    private fun button(button: PadButton, was: Boolean, now: Boolean, time: Long) {
        if (was == now) return
        val phase = if (now) InputDispatcher.KeyPhase.DOWN else InputDispatcher.KeyPhase.UP
        EventQueue.invokeLater { dispatcher.dispatchButton(button, phase, 0, time) }
    }

    /** Select: held long it opens the quick menu (the dispatcher counts repeats like Android's key repeat). */
    private fun select(was: Boolean, now: Boolean, time: Long) {
        when {
            now && !was -> {
                selectDownAt = time
                selectRepeats = 0
                EventQueue.invokeLater { dispatcher.dispatchButton(PadButton.SELECT, InputDispatcher.KeyPhase.DOWN, 0, time) }
            }
            now && was && time - selectDownAt > (selectRepeats + 1) * 50L -> {
                val repeat = ++selectRepeats
                EventQueue.invokeLater { dispatcher.dispatchButton(PadButton.SELECT, InputDispatcher.KeyPhase.DOWN, repeat, time) }
            }
            !now && was -> EventQueue.invokeLater { dispatcher.dispatchButton(PadButton.SELECT, InputDispatcher.KeyPhase.UP, 0, time) }
        }
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

    private fun key(code: Int, was: Boolean, now: Boolean) {
        if (now && !was) post(code, KeyEvent.KEY_PRESSED)
        if (!now && was) post(code, KeyEvent.KEY_RELEASED)
    }

    private fun press(code: Int, released: Boolean = false) {
        post(code, KeyEvent.KEY_PRESSED)
        if (released) post(code, KeyEvent.KEY_RELEASED)
    }

    private fun post(code: Int, type: Int) {
        val target = window ?: return
        EventQueue.invokeLater {
            val focusOwner = target.mostRecentFocusOwner ?: target
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

    private companion object {
        const val STICK = 0.6f
        const val FIRST_REPEAT_MS = 350L
        const val NO_CHAR = '\u0000'
        const val REPEAT_MS = 90L
        const val GUIDE_HOLD_MS = 600L
        val DIRECTIONS = mapOf("up" to KeyEvent.VK_UP, "down" to KeyEvent.VK_DOWN, "left" to KeyEvent.VK_LEFT, "right" to KeyEvent.VK_RIGHT)
    }
}
