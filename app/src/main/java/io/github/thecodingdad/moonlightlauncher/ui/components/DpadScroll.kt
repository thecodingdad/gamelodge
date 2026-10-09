package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/** Lets the D-pad scroll read-only pages that have nothing focusable to move between. */
@Composable
fun Modifier.dpadScroll(state: ScrollState, step: Float = 220f): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val delta = when (e.key) {
            Key.DirectionDown -> step
            Key.DirectionUp -> -step
            else -> return@onPreviewKeyEvent false
        }
        scope.launch { state.animateScrollBy(delta) }
        true
    }
}
