package io.github.thecodingdad.moonlightlauncher.ui.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

enum class PadButton {
    A, B, X, Y, L1, R1, L2, R2, START, SELECT, SELECT_HOLD,
    /** Right stick pushed in a direction (repeats while held); used for values like brightness. */
    RS_LEFT, RS_RIGHT, RS_UP, RS_DOWN,
}

/**
 * Central gamepad dispatcher. Screens register actions with [PadAction]; the most recently
 * registered enabled handler for a button wins, so an open overlay shadows the screen below.
 *
 * A (confirm) and B (back) are not routed here: A is handled by the focus system and B by
 * Android's fallback to BACK, so touch, keyboard and gamepad share one code path.
 */
class InputDispatcher {
    private class Handler(val button: PadButton, val action: () -> Unit)

    private val handlers = ArrayList<Handler>()
    // Triggers: some controllers report them as keys, others only as analog axes, some both.
    private val triggerPressed = HashMap<PadButton, Boolean>()
    private val triggerFiredAt = HashMap<PadButton, Long>()
    // Right stick as repeating direction buttons.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stickDirection: PadButton? = null
    private val stickRepeat = object : Runnable {
        override fun run() {
            val dir = stickDirection ?: return
            fire(dir)
            mainHandler.postDelayed(this, STICK_REPEAT_MS)
        }
    }
    private var selectDownAt = 0L
    private var selectHoldFired = false

    internal fun register(button: PadButton, action: () -> Unit): Any {
        val handler = Handler(button, action)
        handlers += handler
        return handler
    }

    internal fun unregister(token: Any) {
        handlers.remove(token)
    }

    private fun fire(button: PadButton): Boolean {
        val handler = handlers.lastOrNull { it.button == button } ?: return false
        handler.action()
        return true
    }

    /** Returns true if the event was consumed. Call for every key event of the activity. */
    fun dispatch(event: KeyEvent): Boolean {
        val button = buttonFor(event.keyCode) ?: return false
        if (button == PadButton.SELECT) return dispatchSelect(event)
        if ((button == PadButton.L2 || button == PadButton.R2) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            return fireTrigger(button, event.eventTime) || handlers.any { it.button == button }
        }
        // Start is always consumed: unhandled, Android would fall back to DPAD_CENTER and
        // "click" the focused element (open/start a game by accident).
        val alwaysConsume = button == PadButton.START
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> (if (event.repeatCount == 0) fire(button) else handlers.any { it.button == button }) || alwaysConsume
            KeyEvent.ACTION_UP -> handlers.any { it.button == button } || alwaysConsume
            else -> alwaysConsume
        }
    }

    /**
     * Analog triggers as buttons for controllers that don't send L2/R2 key events.
     * Never consumes the event (the stream and other handlers still see it).
     */
    fun dispatchMotion(event: MotionEvent) {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return
        val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        trigger(PadButton.L2, left, event.eventTime)
        trigger(PadButton.R2, right, event.eventTime)
        rightStick(event)
    }

    private fun rightStick(event: MotionEvent) {
        // Most pads report the right stick as Z/RZ, some as RX/RY.
        val device = event.device
        val useZ = device == null || device.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
        val x = event.getAxisValue(if (useZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        val y = event.getAxisValue(if (useZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)
        val direction = when {
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_RELEASE -> null
            stickDirection != null && maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_PRESS -> stickDirection
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_PRESS -> null
            kotlin.math.abs(x) >= kotlin.math.abs(y) -> if (x > 0) PadButton.RS_RIGHT else PadButton.RS_LEFT
            else -> if (y > 0) PadButton.RS_DOWN else PadButton.RS_UP
        }
        if (direction == stickDirection) return
        mainHandler.removeCallbacks(stickRepeat)
        stickDirection = direction
        if (direction != null) {
            fire(direction)
            mainHandler.postDelayed(stickRepeat, STICK_FIRST_REPEAT_MS)
        }
    }

    private fun trigger(button: PadButton, value: Float, time: Long) {
        val pressed = triggerPressed[button] == true
        // Hysteresis so a half-pulled trigger doesn't flicker.
        if (!pressed && value > 0.6f) {
            triggerPressed[button] = true
            fireTrigger(button, time)
        } else if (pressed && value < 0.3f) {
            triggerPressed[button] = false
        }
    }

    /** Fires once per pull even when key and axis events both arrive. */
    private fun fireTrigger(button: PadButton, time: Long): Boolean {
        val last = triggerFiredAt[button] ?: Long.MIN_VALUE / 2
        if (time - last < TRIGGER_DEDUP_MS) return true
        triggerFiredAt[button] = time
        return fire(button)
    }

    /** Select: short press and hold are separate actions (hold opens the quick menu). */
    private fun dispatchSelect(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    selectDownAt = event.eventTime
                    selectHoldFired = false
                } else if (!selectHoldFired && event.eventTime - selectDownAt >= HOLD_MS) {
                    selectHoldFired = fire(PadButton.SELECT_HOLD)
                }
            }
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - selectDownAt
                if (!selectHoldFired) {
                    if (held >= HOLD_MS) fire(PadButton.SELECT_HOLD) else fire(PadButton.SELECT)
                }
            }
        }
        return true
    }

    companion object {
        const val HOLD_MS = 450L
        const val TRIGGER_DEDUP_MS = 250L
        const val STICK_PRESS = 0.6f
        const val STICK_RELEASE = 0.3f
        const val STICK_FIRST_REPEAT_MS = 350L
        const val STICK_REPEAT_MS = 120L

        fun buttonFor(keyCode: Int): PadButton? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
            // Keyboard equivalents, handy on the emulator and with BT keyboards
            KeyEvent.KEYCODE_PAGE_UP -> PadButton.L1
            KeyEvent.KEYCODE_PAGE_DOWN -> PadButton.R1
            KeyEvent.KEYCODE_F1 -> PadButton.Y
            KeyEvent.KEYCODE_F2 -> PadButton.X
            else -> null
        }

        /**
         * Nintendo layout: the confirm button sits where Xbox has B. Swapping key codes before
         * dispatch makes the rest of the app layout-agnostic.
         */
        fun swapForNintendo(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    }
}

val LocalInputDispatcher = staticCompositionLocalOf { InputDispatcher() }

/** Registers [onPress] for [button] while this composable is in the composition. */
@Composable
fun PadAction(button: PadButton, enabled: Boolean = true, onPress: () -> Unit) {
    val dispatcher = LocalInputDispatcher.current
    val current = rememberUpdatedState(onPress)
    DisposableEffect(dispatcher, button, enabled) {
        val token = if (enabled) dispatcher.register(button) { current.value() } else null
        onDispose { token?.let(dispatcher::unregister) }
    }
}
