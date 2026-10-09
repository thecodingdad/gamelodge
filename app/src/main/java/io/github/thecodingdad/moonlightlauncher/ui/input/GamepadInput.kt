package io.github.thecodingdad.moonlightlauncher.ui.input

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

enum class PadButton { A, B, X, Y, L1, R1, L2, R2, START, SELECT, SELECT_HOLD }

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
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) fire(button) else handlers.any { it.button == button }
            KeyEvent.ACTION_UP -> handlers.any { it.button == button }
            else -> false
        }
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
