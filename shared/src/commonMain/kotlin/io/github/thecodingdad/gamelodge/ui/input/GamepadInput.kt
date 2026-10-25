package io.github.thecodingdad.gamelodge.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch

enum class PadButton {
    A, B, X, Y, L1, R1, L2, R2, START, SELECT,
    /** Right stick pushed in a direction (repeats while held); used for values like brightness. */
    RS_LEFT, RS_RIGHT, RS_UP, RS_DOWN,
}

/**
 * Central gamepad dispatcher. Screens register actions with [PadAction]; the most recently
 * registered enabled handler for a button wins, so an open overlay shadows the screen below.
 *
 * A (confirm) and B (back) are not routed here: A is handled by the focus system and B by
 * the platform's back handling, so touch, keyboard and gamepad share one code path.
 *
 * Platform-neutral: the platform translates its key and axis events ([dispatchButton], [dispatchAxes]).
 */
class InputDispatcher {
    private class Handler(val button: PadButton, val action: () -> Unit)

    private val handlers = ArrayList<Handler>()
    // Triggers: some controllers report them as keys, others only as analog axes, some both.
    private val triggerPressed = HashMap<PadButton, Boolean>()
    private val triggerFiredAt = HashMap<PadButton, Long>()
    // Right stick as repeating direction buttons.
    private val mainScope = kotlinx.coroutines.MainScope()
    private var stickDirection: PadButton? = null
    private var stickRepeat: kotlinx.coroutines.Job? = null

    // Right stick up/down scrolls the screen's (single) vertical scroll area like a swipe;
    // left/right stay direction buttons (paging details, dimming lights).
    private val scrollTargets = ArrayList<androidx.compose.foundation.gestures.ScrollableState>()

    /** Vertical right stick deflection for scrolling (-1..1, 0 in the dead zone). */
    var scrollVelocity by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set

    internal fun registerScroll(state: androidx.compose.foundation.gestures.ScrollableState) {
        scrollTargets += state
    }

    internal fun unregisterScroll(state: androidx.compose.foundation.gestures.ScrollableState) {
        scrollTargets.remove(state)
    }

    /** The focus moved into [state]'s area: it becomes the stick's target (several areas side by side). */
    internal fun focusScroll(state: androidx.compose.foundation.gestures.ScrollableState) {
        if (scrollTargets.lastOrNull() !== state && scrollTargets.remove(state)) scrollTargets += state
    }

    /** The most recently shown scroll area (a popup's, else the screen's). */
    val scrollTarget: androidx.compose.foundation.gestures.ScrollableState? get() = scrollTargets.lastOrNull()

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

    /** Phase of a key event. */
    enum class KeyPhase { DOWN, UP, OTHER }

    /**
     * The button test of the setup wizard: while set, every controller button ("A", "B", "X", "Y",
     * "LB", "RB", "LT", "RT", "START", "SELECT", "L3", "R3", "UP", "DOWN", "LEFT", "RIGHT"; face
     * buttons after the layout swap) goes here, pressed (true) and released (false), and does
     * nothing else. Fed by the platform.
     */
    @Volatile var buttonTest: ((name: String, down: Boolean) -> Unit)? = null

    fun reportTest(name: String, down: Boolean) {
        buttonTest?.invoke(name, down)
    }

    /** Returns true if the event was consumed. Call for every gamepad key event of the window. */
    fun dispatchButton(button: PadButton, phase: KeyPhase, repeatCount: Int, eventTime: Long): Boolean {
        if (button == PadButton.SELECT) return dispatchSelect(phase, repeatCount, eventTime)
        if ((button == PadButton.L2 || button == PadButton.R2) && phase == KeyPhase.DOWN && repeatCount == 0) {
            return fireTrigger(button, eventTime) || handlers.any { it.button == button }
        }
        // Start is always consumed: unhandled, Android would fall back to DPAD_CENTER and
        // "click" the focused element (open/start a game by accident).
        val alwaysConsume = button == PadButton.START
        return when (phase) {
            KeyPhase.DOWN -> (if (repeatCount == 0) fire(button) else handlers.any { it.button == button }) || alwaysConsume
            KeyPhase.UP -> handlers.any { it.button == button } || alwaysConsume
            KeyPhase.OTHER -> alwaysConsume
        }
    }

    /**
     * Analog triggers (0..1) as buttons for controllers that don't send L2/R2 key events, and the
     * right stick (-1..1) as direction buttons and scroll speed. Never consumes anything.
     */
    fun dispatchAxes(leftTrigger: Float, rightTrigger: Float, stickX: Float, stickY: Float, eventTime: Long) {
        trigger(PadButton.L2, leftTrigger, eventTime)
        trigger(PadButton.R2, rightTrigger, eventTime)
        rightStick(stickX, stickY)
    }

    private fun rightStick(x: Float, y: Float) {
        scrollVelocity = if (kotlin.math.abs(y) > SCROLL_DEAD_ZONE && kotlin.math.abs(y) > kotlin.math.abs(x)) y else 0f
        val direction = when {
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_RELEASE -> null
            stickDirection != null && maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_PRESS -> stickDirection
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) < STICK_PRESS -> null
            kotlin.math.abs(x) >= kotlin.math.abs(y) -> if (x > 0) PadButton.RS_RIGHT else PadButton.RS_LEFT
            else -> null
        }
        if (direction == stickDirection) return
        stickRepeat?.cancel()
        stickDirection = direction
        if (direction != null) {
            fire(direction)
            stickRepeat = mainScope.launch {
                kotlinx.coroutines.delay(STICK_FIRST_REPEAT_MS)
                while (true) {
                    fire(direction)
                    kotlinx.coroutines.delay(STICK_REPEAT_MS)
                }
            }
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

    /** Select acts when it is released (the quick menu only opens with its configured combination). */
    private fun dispatchSelect(phase: KeyPhase, repeatCount: Int, eventTime: Long): Boolean {
        if (phase == KeyPhase.UP) fire(PadButton.SELECT)
        return true
    }

    companion object {
        const val TRIGGER_DEDUP_MS = 250L
        const val STICK_PRESS = 0.6f
        const val SCROLL_DEAD_ZONE = 0.15f
        const val STICK_RELEASE = 0.3f
        const val STICK_FIRST_REPEAT_MS = 350L
        const val STICK_REPEAT_MS = 120L
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

/**
 * False for content that is composed but not shown (neighbour pages of a pager): its scroll
 * areas don't register for the right stick.
 */
val LocalStickScrollEnabled = staticCompositionLocalOf { true }

/**
 * Makes [state] the target of right stick scrolling while it is in the composition (unless
 * [LocalStickScrollEnabled] is off); the latest registered area wins, see also [stickScrollFocus].
 */
@Composable
fun RegisterStickScroll(state: androidx.compose.foundation.gestures.ScrollableState) {
    val dispatcher = LocalInputDispatcher.current
    val enabled = LocalStickScrollEnabled.current
    DisposableEffect(dispatcher, state, enabled) {
        if (enabled) dispatcher.registerScroll(state)
        onDispose { dispatcher.unregisterScroll(state) }
    }
}

/** Makes [state] the stick's target whenever the focus is inside this element. */
@Composable
fun androidx.compose.ui.Modifier.stickScrollFocus(state: androidx.compose.foundation.gestures.ScrollableState): androidx.compose.ui.Modifier {
    val dispatcher = LocalInputDispatcher.current
    return this.then(androidx.compose.ui.Modifier.onFocusChanged { if (it.hasFocus) dispatcher.focusScroll(state) })
}

/**
 * Scrolls the current scroll area while the right stick is held up/down; the further the stick
 * is pushed, the faster (eased, so small deflections allow fine control). Place once per window.
 */
@Composable
fun RightStickScrollDriver() {
    val dispatcher = LocalInputDispatcher.current
    val maxSpeed = with(androidx.compose.ui.platform.LocalDensity.current) { 2400.dp.toPx() } // px per second
    androidx.compose.runtime.LaunchedEffect(dispatcher) {
        androidx.compose.runtime.snapshotFlow { dispatcher.scrollVelocity != 0f }.collect { active ->
            if (!active) return@collect
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (dispatcher.scrollVelocity != 0f) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val seconds = (now - last) / 1_000_000_000f
                last = now
                val v = dispatcher.scrollVelocity
                val strength = ((kotlin.math.abs(v) - InputDispatcher.SCROLL_DEAD_ZONE) / (1f - InputDispatcher.SCROLL_DEAD_ZONE)).coerceIn(0f, 1f)
                dispatcher.scrollTarget?.scrollBy(kotlin.math.sign(v) * strength * strength * maxSpeed * seconds)
            }
        }
    }
}

/** Lazy list state that the right stick scrolls. */
@Composable
fun rememberStickLazyListState(): androidx.compose.foundation.lazy.LazyListState =
    androidx.compose.foundation.lazy.rememberLazyListState().also { RegisterStickScroll(it) }
