package io.github.thecodingdad.gamelodge.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thecodingdad.gamelodge.ui.components.ButtonHint

/** Collects the button hints of the currently visible screen for the shell's hint bar. */
@Stable
class HintHolder {
    private class Entry(val owner: Any, val hints: List<ButtonHint>, val active: Boolean)

    private val stack = mutableStateListOf<Entry>()

    /**
     * The hints of the topmost screen that is not on its way out (navigation / tab cross-fade).
     * During a transition the leaving screen is already inactive while the entering one has not
     * registered its hints yet; falling back to the last entry then keeps the bar filled instead
     * of blinking empty for a frame (which would also collapse its height and jolt the layout).
     */
    val hints: List<ButtonHint> get() = (stack.lastOrNull { it.active } ?: stack.lastOrNull())?.hints.orEmpty()

    internal fun push(owner: Any, hints: List<ButtonHint>, active: Boolean = true) {
        val index = stack.indexOfFirst { it.owner === owner }
        // Keeps its place: a screen coming back keeps its order with the popups above it.
        if (index >= 0) stack[index] = Entry(owner, hints, active) else stack += Entry(owner, hints, active)
    }

    internal fun remove(owner: Any) {
        stack.removeAll { it.owner === owner }
    }
}

val LocalHintHolder = staticCompositionLocalOf { HintHolder() }

/**
 * False inside a screen that is leaving (the previous destination or tab while the next one
 * fades in): its hints no longer count, so the new screen's (or the default) show at once.
 */
val LocalHintsActive = androidx.compose.runtime.compositionLocalOf { true }

/** The destination navigation currently shows; screens of other back stack entries are leaving. */
val LocalCurrentNavEntry = androidx.compose.runtime.compositionLocalOf<Any?> { null }

/** Declares the hints for the calling screen while it is composed. */
@Composable
fun ScreenHints(hints: List<ButtonHint>) {
    val holder = LocalHintHolder.current
    val owner = androidx.compose.runtime.remember { Any() }
    val entry = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val current = LocalCurrentNavEntry.current
    val active = LocalHintsActive.current && (entry !is androidx.navigation.NavBackStackEntry || current == null || entry === current)
    DisposableEffect(holder, hints, active) {
        holder.push(owner, hints, active)
        onDispose { }
    }
    DisposableEffect(holder) { onDispose { holder.remove(owner) } }
}
