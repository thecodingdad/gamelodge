package io.github.thecodingdad.moonlightlauncher.ui.input

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
    val x = event.getAxisValue(if (useZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
    val y = event.getAxisValue(if (useZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)
    dispatchAxes(left, right, x, y, event.eventTime)
}
