package io.github.thecodingdad.moonlightlauncher.input

import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ComboAction { HOME, QUICK_MENU }

/**
 * User-defined controller button combinations (e.g. Select + Start -> Home). Used by the launcher
 * activity and, when enabled, by the accessibility service for system-wide handling. Single
 * process-wide state so recording in the settings works with either path.
 */
object ButtonCombos {
    @Volatile var combos: Map<ComboAction, Set<Int>> = emptyMap()

    private val down = LinkedHashSet<Int>()
    private val consuming = HashSet<Int>()

    // Recording: collects the largest set of buttons held at once, finishes when all are released.
    private var recorder: ((Set<Int>) -> Unit)? = null
    private val recorded = LinkedHashSet<Int>()
    private val _recordingPreview = MutableStateFlow<Set<Int>>(emptySet())
    val recordingPreview: StateFlow<Set<Int>> = _recordingPreview.asStateFlow()

    fun startRecording(onDone: (Set<Int>) -> Unit) {
        recorded.clear()
        _recordingPreview.value = emptySet()
        recorder = onDone
    }

    fun cancelRecording() {
        recorder = null
        recorded.clear()
        _recordingPreview.value = emptySet()
    }

    val isRecording: Boolean get() = recorder != null

    private fun isGamepadKey(event: KeyEvent): Boolean =
        KeyEvent.isGamepadButton(event.keyCode) ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    /** Returns true when the event belongs to a combo (or recording) and must not reach the app. */
    @Synchronized
    fun handle(event: KeyEvent, onAction: (ComboAction) -> Unit): Boolean {
        if (!isGamepadKey(event)) return false
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) down += code
                recorder?.let {
                    recorded += code
                    _recordingPreview.value = LinkedHashSet(recorded)
                    return true
                }
                if (code in consuming) return true
                if (event.repeatCount == 0) {
                    val hit = combos.entries.firstOrNull { (_, keys) -> keys.isNotEmpty() && keys == down }
                    if (hit != null) {
                        // Only the completing button is swallowed: the others already reached
                        // the app, so their release has to reach it too (no stuck buttons).
                        consuming += code
                        onAction(hit.key)
                        return true
                    }
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                down -= code
                recorder?.let { done ->
                    if (down.isEmpty() && recorded.isNotEmpty()) {
                        val result = LinkedHashSet(recorded)
                        cancelRecording()
                        done(result)
                    }
                    return true
                }
                return consuming.remove(code)
            }
        }
        return false
    }

    /** Forgets held buttons, e.g. when the window loses focus and releases may be missed. */
    @Synchronized
    fun reset() {
        down.clear()
        consuming.clear()
    }

    /** Combos that would break navigation (A, B or a D-pad direction alone). */
    fun isBlocked(keys: Set<Int>): Boolean = keys.size == 1 && keys.first() in setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /** "Select + Start" style name of a combo. */
    fun describe(keys: Set<Int>): String = keys.joinToString(" + ") { name(it) }

    fun name(code: Int): String = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
        KeyEvent.KEYCODE_BUTTON_L2 -> "LT"
        KeyEvent.KEYCODE_BUTTON_R2 -> "RT"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Guide"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_")
    }
}
