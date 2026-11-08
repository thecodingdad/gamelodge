package io.github.thecodingdad.gamelodge.input

import io.github.thecodingdad.gamelodge.data.settings.COMBO_HOLD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What a button combination does. */
enum class ComboAction { HOME, QUICK_MENU, MOUSE, STATS, KEYBOARD }

/** Controller buttons as Android key codes (also on the PC: the settings store them so). */
object PadKeys {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val A = 96
    const val B = 97
    const val X = 99
    const val Y = 100
    const val L1 = 102
    const val R1 = 103
    const val L2 = 104
    const val R2 = 105
    const val THUMBL = 106
    const val THUMBR = 107
    const val START = 108
    const val SELECT = 109
    const val MODE = 110
}

/**
 * User-defined controller button combinations (e.g. Select + Start -> Home). Fed by the platform
 * (Android: the launcher activity, the stream and the accessibility service; PC: the gamepad
 * poller). Single process-wide state so recording in the settings works with any input path.
 */
object ButtonCombos {
    /** Marker in a combination: the buttons have to be held ([HOLD_MS]) instead of pressed. */
    const val HOLD = COMBO_HOLD
    const val HOLD_MS = 600L

    /** Global combinations. */
    @Volatile var combos: Map<ComboAction, Set<Int>> = emptyMap()

    /** Per-controller replacements by device descriptor (an empty set switches the action off). */
    @Volatile var overrides: Map<String, Map<ComboAction, Set<Int>>> = emptyMap()

    /** The combinations' buttons reach the app / stream as well (the action runs in addition). */
    @Volatile var forward = false

    /** Per-controller replacement of [forward] by device descriptor. */
    @Volatile var forwardOverrides: Map<String, Boolean> = emptyMap()

    fun forwardFor(descriptor: String?): Boolean = descriptor?.let { forwardOverrides[it] } ?: forward

    /**
     * Hands a short press held back by a hold combination to the app after all: the platform's
     * press and release events (as given to [handle]). Where that isn't possible it stays unset.
     */
    @Volatile var replay: ((press: Any, release: Any) -> Unit)? = null

    /** Set by the platform while it delivers a [replay]: those events pass untouched. */
    @Volatile var replaying = false

    /**
     * A stream in front wants the Guide button for itself (the Xbox dashboard: the console's Xbox
     * button): no combination takes it, it goes on to the stream.
     */
    @Volatile var guidePassThrough = false

    fun combosFor(descriptor: String?): Map<ComboAction, Set<Int>> =
        descriptor?.let { overrides[it] }?.let { combos + it } ?: combos

    // Held and swallowed buttons per controller: combinations never mix two controllers.
    private val down = HashMap<Int, LinkedHashSet<Int>>()
    private val consuming = HashMap<Int, HashSet<Int>>()

    // Recording: collects the largest set of buttons held at once, finishes when all are released.
    private var recorder: ((Set<Int>) -> Unit)? = null
    private var recordDescriptor: String? = null
    private val recorded = LinkedHashSet<Int>()
    private var recordGrewAt = 0L
    private var recordReleasedAt = 0L
    private var recordHoldTimer: ScheduledFuture<*>? = null
    private val _recordingPreview = MutableStateFlow<Set<Int>>(emptySet())
    val recordingPreview: StateFlow<Set<Int>> = _recordingPreview.asStateFlow()

    /** Records the next combination; with [descriptor] only from that controller. */
    @Synchronized
    fun startRecording(descriptor: String? = null, onDone: (Set<Int>) -> Unit) {
        recorded.clear()
        recordReleasedAt = 0L
        _recordingPreview.value = emptySet()
        recordDescriptor = descriptor
        recorder = onDone
    }

    @Synchronized
    fun cancelRecording() {
        recordHoldTimer?.cancel(false)
        recordHoldTimer = null
        recorder = null
        recordDescriptor = null
        recorded.clear()
        _recordingPreview.value = emptySet()
    }

    val isRecording: Boolean get() = recorder != null

    // A press that can still become a hold: decided on release or when the hold time is up.
    private class Pending(
        val device: Int, val code: Int, val press: Any,
        val tap: ComboAction?, val hold: ComboAction, val onAction: (ComboAction, Int) -> Unit,
    )
    private var pending: Pending? = null
    private var holdTimer: ScheduledFuture<*>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "button-combos").apply { isDaemon = true } }

    private fun cancelPending() {
        pending = null
        holdTimer?.cancel(false)
        holdTimer = null
    }

    /**
     * One button of controller [device] went down or up ([code] an Android key code, [event] the
     * platform's event for a later [replay]). Returns true when the button belongs to a combination
     * (or a recording) and must not reach the app. [onAction] gets the action and the controller.
     * [canHoldBack] false: a press that may become a hold reaches the app at once (where a held-back
     * press couldn't be handed on later), the hold's action runs in addition.
     */
    @Synchronized
    fun handle(
        device: Int, code: Int, isDown: Boolean, repeat: Int, time: Long, descriptor: String?, event: Any,
        canHoldBack: Boolean = true,
        onAction: (ComboAction, Int) -> Unit,
    ): Boolean {
        if (replaying) return false
        if (guidePassThrough && code == PadKeys.MODE) return false
        val held = down.getOrPut(device) { LinkedHashSet() }
        val swallowed = consuming.getOrPut(device) { HashSet() }
        val recordsThis = recorder != null && (recordDescriptor == null || recordDescriptor == descriptor)
        if (isDown) {
            if (repeat == 0) held += code
            if (recorder != null) {
                // Other controllers are ignored while recording for a specific one.
                if (recordsThis && repeat == 0 && recorded.add(code)) {
                    recordGrewAt = time
                    _recordingPreview.value = LinkedHashSet(recorded)
                    // Held on without a change: shown as a hold already before letting go.
                    recordHoldTimer?.cancel(false)
                    val grew = recordGrewAt
                    recordHoldTimer = timer.schedule({
                        synchronized(this) {
                            if (recorder != null && recordGrewAt == grew && recordReleasedAt == 0L) {
                                _recordingPreview.value = LinkedHashSet(recorded) + HOLD
                            }
                        }
                    }, HOLD_MS, TimeUnit.MILLISECONDS)
                }
                return true
            }
            if (code in swallowed) return true
            if (repeat != 0) return false
            // Another button joined: a pending hold is no longer meant.
            cancelPending()
            val combos = combosFor(descriptor)
            val hit = combos.entries.firstOrNull { (_, keys) -> keys.isNotEmpty() && keys == held }
            val holdHit = combos.entries.firstOrNull { (_, keys) -> HOLD in keys && keys.size > 1 && keys - HOLD == held }
            if (holdHit != null) {
                val p = Pending(device, code, event, hit?.key, holdHit.key, onAction)
                pending = p
                holdTimer = timer.schedule({
                    val fire = synchronized(this) { pending?.takeIf { it === p }?.also { pending = null } }
                    fire?.onAction?.invoke(fire.hold, fire.device)
                }, HOLD_MS, TimeUnit.MILLISECONDS)
                if (!canHoldBack || forwardFor(descriptor)) return false
                swallowed += code
                return true
            }
            if (hit != null) {
                onAction(hit.key, device)
                if (forwardFor(descriptor)) return false
                // Only the completing button is swallowed: the others already reached the app,
                // so their release has to reach it too (no stuck buttons).
                swallowed += code
                return true
            }
            return false
        }
        held -= code
        recorder?.let { done ->
            if (recordsThis && recordReleasedAt == 0L) {
                recordReleasedAt = time
                recordHoldTimer?.cancel(false)
            }
            if (recordsThis && held.isEmpty() && recorded.isNotEmpty()) {
                val result = LinkedHashSet(recorded)
                // Held still before letting go: a hold combination.
                if (recordReleasedAt - recordGrewAt >= HOLD_MS) result += HOLD
                cancelRecording()
                done(result)
            }
            return true
        }
        pending?.takeIf { it.device == device && it.code == code }?.let { p ->
            // Released before the hold time: a press. Without its own action the app gets it after
            // all (it was held back in case it became a hold).
            cancelPending()
            p.tap?.let { p.onAction(it, device) }
            if (p.tap == null && code in swallowed) {
                swallowed.remove(code)
                replay?.invoke(p.press, event)
                return true
            }
        }
        return swallowed.remove(code)
    }

    /** Forgets held buttons, e.g. when the window loses focus and releases may be missed. */
    @Synchronized
    fun reset() {
        cancelPending()
        down.clear()
        consuming.clear()
    }

    /** Combos that would break navigation (A, B or a D-pad direction alone). */
    fun isBlocked(keys: Set<Int>): Boolean = (keys - HOLD).let {
        it.size == 1 && it.first() in setOf(PadKeys.A, PadKeys.B, PadKeys.DPAD_UP, PadKeys.DPAD_DOWN, PadKeys.DPAD_LEFT, PadKeys.DPAD_RIGHT)
    }

    /** "Select + Start" style name of a combo; a hold combination gets [holdSuffix] ("Guide (hold)"). */
    fun describe(keys: Set<Int>, holdSuffix: String = "hold"): String =
        (keys - HOLD).joinToString(" + ") { name(it) } + if (HOLD in keys) " ($holdSuffix)" else ""

    fun name(code: Int): String = when (code) {
        PadKeys.A -> "A"
        PadKeys.B -> "B"
        PadKeys.X -> "X"
        PadKeys.Y -> "Y"
        PadKeys.L1 -> "LB"
        PadKeys.R1 -> "RB"
        PadKeys.L2 -> "LT"
        PadKeys.R2 -> "RT"
        PadKeys.THUMBL -> "L3"
        PadKeys.THUMBR -> "R3"
        PadKeys.SELECT -> "Select"
        PadKeys.START -> "Start"
        PadKeys.MODE -> "Guide"
        PadKeys.DPAD_UP -> "↑"
        PadKeys.DPAD_DOWN -> "↓"
        PadKeys.DPAD_LEFT -> "←"
        PadKeys.DPAD_RIGHT -> "→"
        else -> "#$code"
    }
}
