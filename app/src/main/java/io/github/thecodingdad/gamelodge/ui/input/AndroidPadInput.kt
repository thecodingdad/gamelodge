package io.github.thecodingdad.gamelodge.ui.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Android key and motion events for the [InputDispatcher]. */
object AndroidPad {
    fun buttonFor(keyCode: Int): PadButton? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadButton.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
        // Keyboard keys as the user assigned them (Settings › Touch, mouse & keyboard).
        else -> io.github.thecodingdad.gamelodge.input.KeyboardKeys.actionFor(androidx.compose.ui.input.key.Key(keyCode))?.button
    }

    /**
     * Nintendo layout: the confirm button sits where Xbox has B. Swapping key codes before
     * dispatch makes the rest of the app layout-agnostic.
     */
    /** Buttons whose unhandled presses mustn't turn into Android's fallback keys (confirm / back). */
    fun noFallback(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y

    /** Name of a controller button for the button test (D-pad only from controllers, not keyboards). */
    fun testName(event: KeyEvent): String? = when (event.keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
        KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
        KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
        KeyEvent.KEYCODE_BUTTON_START -> "START"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        else -> null
    }?.takeIf { KeyEvent.isGamepadButton(event.keyCode) || (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD }

    // Only confirm/back (A/B) swap for Nintendo. Joy-Cons report A/B by position (so they need the
    // swap) but X/Y by label (so swapping them would break X/Y); the other Nintendo pads match.
    fun swapForNintendo(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        else -> keyCode
    }

    /**
     * [event] in the layout of the controller it comes from (its own, else its model's);
     * remembers that controller as the active one (button hints follow it).
     */
    fun inLayout(event: KeyEvent, settings: io.github.thecodingdad.gamelodge.data.settings.LauncherSettings): KeyEvent {
        val device = event.device?.takeIf { (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD }
        val key = device?.descriptor
        if (key != null && event.action == KeyEvent.ACTION_DOWN) {
            io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.key = key
            // Button hints follow the pad in use: a Sony pad shows PlayStation glyphs, a Nintendo pad Nintendo.
            io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.detectedLayout =
                io.github.thecodingdad.gamelodge.ui.screens.settings.buttonLayoutForVendor(device.vendorId)
        }
        val layout = settings.controllerLayouts[key]
            ?: device?.let { io.github.thecodingdad.gamelodge.ui.screens.settings.buttonLayoutForVendor(it.vendorId) }
        if (layout != io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.NINTENDO) return event
        val swapped = swapForNintendo(event.keyCode)
        if (swapped == event.keyCode) return event
        return KeyEvent(event.downTime, event.eventTime, event.action, swapped, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
    }
}

/** Returns true if the event was consumed. Call for every key event of the activity. */
fun InputDispatcher.dispatch(event: KeyEvent): Boolean {
    val button = AndroidPad.buttonFor(event.keyCode) ?: return false
    val phase = when (event.action) {
        KeyEvent.ACTION_DOWN -> InputDispatcher.KeyPhase.DOWN
        KeyEvent.ACTION_UP -> InputDispatcher.KeyPhase.UP
        else -> InputDispatcher.KeyPhase.OTHER
    }
    return dispatchButton(button, phase, event.repeatCount, event.eventTime)
}

/** Triggers and right stick of a joystick motion event; never consumes it (the stream still sees it). */
fun InputDispatcher.dispatchMotion(event: MotionEvent) {
    if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return
    val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
    val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
    // Most pads report the right stick as Z/RZ, some as RX/RY.
    val device = event.device
    val useZ = device == null || device.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
    // The controller's own dead zone applies here too (it only matters above the launcher's own).
    val x = io.github.thecodingdad.gamelodge.input.PadPreferences.deadzone(device, event.getAxisValue(if (useZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX))
    val y = io.github.thecodingdad.gamelodge.input.PadPreferences.deadzone(device, event.getAxisValue(if (useZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY))
    dispatchAxes(left, right, x, y, event.eventTime)
}
