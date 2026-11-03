package io.github.thecodingdad.gamelodge.ui.input

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import io.github.thecodingdad.gamelodge.input.KeyAction
import io.github.thecodingdad.gamelodge.input.KeyShortcut
import io.github.thecodingdad.gamelodge.input.KeyboardKeys

/**
 * A physical keyboard in GameLodge's activities: recording keys, the quick menu shortcut, and the
 * keys assigned to confirm / back / quick menu / home. The other assigned keys reach the
 * dispatcher as controller buttons ([AndroidPad.buttonFor]).
 */
object AndroidKeyboard {
    /** Keys of the shortcut being handled (its release is swallowed too). */
    private var shortcutDown = -1

    /** A real keyboard (not a controller, not the volume / power keys). */
    fun isKeyboard(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if ((device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return false
        if ((device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false
        return device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    private fun isPad(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** The system-wide shortcut, if [event] is it (any device with letters). */
    fun matchesShortcut(event: KeyEvent): Boolean {
        val shortcut = KeyboardKeys.quickMenuShortcut ?: return false
        if (KeyboardKeys.recordingShortcut != null) return false
        val name = KeyboardKeys.shortcutNameOf(Key(event.keyCode)) ?: return false
        return name == shortcut.key && event.isCtrlPressed == shortcut.ctrl && event.isAltPressed == shortcut.alt &&
            event.isShiftPressed == shortcut.shift
    }

    /**
     * Shortcut handling for the accessibility service (every app): true = consumed, [open]
     * opens the quick menu.
     */
    fun handleShortcut(event: KeyEvent, open: () -> Unit): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == shortcutDown) {
            shortcutDown = -1
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || !matchesShortcut(event)) return false
        if (event.repeatCount == 0) {
            shortcutDown = event.keyCode
            open()
        }
        return true
    }

    /**
     * The mouse's side buttons (Android sends them as Back / Forward keys): in tab mode they are
     * LB / RB; otherwise Back stays back and Forward does nothing. True = consumed.
     */
    fun handleMouseButtons(event: KeyEvent, tabs: Boolean, dispatcher: InputDispatcher): Boolean {
        if ((event.source and InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE) return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_FORWARD) return false
        KeyboardKeys.onOtherInput()
        if (!tabs) return event.keyCode == KeyEvent.KEYCODE_FORWARD
        val phase = if (event.action == KeyEvent.ACTION_DOWN) InputDispatcher.KeyPhase.DOWN else InputDispatcher.KeyPhase.UP
        dispatcher.dispatchButton(if (event.keyCode == KeyEvent.KEYCODE_BACK) PadButton.L1 else PadButton.R1, phase, event.repeatCount, event.eventTime)
        return true
    }

    /**
     * Handles [event] in an activity. Returns the result to give back, or null to go on as usual.
     * [superDispatch] hands a key to the focus system (Enter / Back), [action] runs quick menu / home.
     */
    fun handle(event: KeyEvent, superDispatch: (KeyEvent) -> Boolean, action: (KeyAction) -> Unit): Boolean? {
        if (isPad(event)) KeyboardKeys.onOtherInput()
        if (!isKeyboard(event)) return null
        KeyboardKeys.onKeyboard()
        val down = event.action == KeyEvent.ACTION_DOWN
        // Recording a key / the shortcut: every key goes there, Esc cancels.
        KeyboardKeys.recording?.let { done ->
            if (down && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    KeyboardKeys.recording = null
                    done(null)
                } else {
                    KeyboardKeys.nameOf(Key(event.keyCode))?.let { name ->
                        KeyboardKeys.recording = null
                        done(name)
                    }
                }
            }
            return true
        }
        KeyboardKeys.recordingShortcut?.let { done ->
            if (down && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    KeyboardKeys.recordingShortcut = null
                    done(null)
                } else {
                    KeyboardKeys.shortcutNameOf(Key(event.keyCode))?.let { name ->
                        val shortcut = KeyShortcut(event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, name)
                        if (shortcut.hasModifier) {
                            KeyboardKeys.recordingShortcut = null
                            done(shortcut)
                        }
                    }
                }
            }
            return true
        }
        if (handleShortcut(event) { action(KeyAction.QUICK_MENU) }) return true
        // Esc goes back like on the PC (Android doesn't turn it into Back by itself).
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            superDispatch(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_BACK, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
            return true
        }
        val assigned = KeyboardKeys.actionFor(Key(event.keyCode)) ?: return null
        return when (assigned) {
            // Confirm / back like Enter / Esc.
            KeyAction.A, KeyAction.B -> {
                val code = if (assigned == KeyAction.A) KeyEvent.KEYCODE_ENTER else KeyEvent.KEYCODE_BACK
                superDispatch(KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
                true
            }
            KeyAction.QUICK_MENU, KeyAction.HOME -> {
                if (down && event.repeatCount == 0) action(assigned)
                true
            }
            else -> null
        }
    }
}
