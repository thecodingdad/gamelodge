package io.github.thecodingdad.gamelodge.input

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
    /** Global combinations. */
    @Volatile var combos: Map<ComboAction, Set<Int>> = emptyMap()

    /** Per-controller replacements by device descriptor (an empty set switches the action off). */
    @Volatile var overrides: Map<String, Map<ComboAction, Set<Int>>> = emptyMap()

    fun combosFor(descriptor: String?): Map<ComboAction, Set<Int>> =
        descriptor?.let { overrides[it] }?.let { combos + it } ?: combos

    // Held and swallowed buttons per controller: combinations never mix two controllers.
    private val down = HashMap<Int, LinkedHashSet<Int>>()
    private val consuming = HashMap<Int, HashSet<Int>>()

    // Recording: collects the largest set of buttons held at once, finishes when all are released.
    private var recorder: ((Set<Int>) -> Unit)? = null
    private var recordDescriptor: String? = null
    private val recorded = LinkedHashSet<Int>()
    private val _recordingPreview = MutableStateFlow<Set<Int>>(emptySet())
    val recordingPreview: StateFlow<Set<Int>> = _recordingPreview.asStateFlow()

    /** Records the next combination; with [descriptor] only from that controller. */
    fun startRecording(descriptor: String? = null, onDone: (Set<Int>) -> Unit) {
        recorded.clear()
        _recordingPreview.value = emptySet()
        recordDescriptor = descriptor
        recorder = onDone
    }

    fun cancelRecording() {
        recorder = null
        recordDescriptor = null
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
        val held = down.getOrPut(event.deviceId) { LinkedHashSet() }
        val swallowed = consuming.getOrPut(event.deviceId) { HashSet() }
        val descriptor = event.device?.descriptor
        val recordsThis = recorder != null && (recordDescriptor == null || recordDescriptor == descriptor)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) held += code
                if (recorder != null) {
                    // Other controllers are ignored while recording for a specific one.
                    if (recordsThis) {
                        recorded += code
                        _recordingPreview.value = LinkedHashSet(recorded)
                    }
                    return true
                }
                if (code in swallowed) return true
                if (event.repeatCount == 0) {
                    val hit = combosFor(descriptor).entries.firstOrNull { (_, keys) -> keys.isNotEmpty() && keys == held }
                    if (hit != null) {
                        // Only the completing button is swallowed: the others already reached
                        // the app, so their release has to reach it too (no stuck buttons).
                        swallowed += code
                        onAction(hit.key)
                        return true
                    }
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                held -= code
                recorder?.let { done ->
                    if (recordsThis && held.isEmpty() && recorded.isNotEmpty()) {
                        val result = LinkedHashSet(recorded)
                        cancelRecording()
                        done(result)
                    }
                    return true
                }
                return swallowed.remove(code)
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
