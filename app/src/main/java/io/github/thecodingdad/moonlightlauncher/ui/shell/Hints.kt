package io.github.thecodingdad.moonlightlauncher.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint

/** Collects the button hints of the currently visible screen for the shell's hint bar. */
@Stable
class HintHolder {
    private val stack = mutableStateListOf<Pair<Any, List<ButtonHint>>>()
    val hints: List<ButtonHint> get() = stack.lastOrNull()?.second.orEmpty()

    internal fun push(owner: Any, hints: List<ButtonHint>) {
        val index = stack.indexOfFirst { it.first === owner }
        if (index >= 0) stack[index] = owner to hints else stack += owner to hints
    }

    internal fun remove(owner: Any) {
        stack.removeAll { it.first === owner }
    }
}

val LocalHintHolder = staticCompositionLocalOf { HintHolder() }

/** Declares the hints for the calling screen while it is composed. */
@Composable
fun ScreenHints(hints: List<ButtonHint>) {
    val holder = LocalHintHolder.current
    val owner = androidx.compose.runtime.remember { Any() }
    DisposableEffect(holder, hints) {
        holder.push(owner, hints)
        onDispose { }
    }
    DisposableEffect(holder) { onDispose { holder.remove(owner) } }
}
