package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.verticalScroll
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

/**
 * Vertical scrolling for menus and popups: D-pad up/down moves the focus as usual; when there is
 * no further element in that direction, the content scrolls to its very top/bottom so texts
 * above the first or below the last element become visible.
 */
@Composable
fun Modifier.controllerScroll(state: ScrollState = androidx.compose.foundation.rememberScrollState()): Modifier {
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    io.github.thecodingdad.gamelodge.ui.input.RegisterStickScroll(state)
    return this
        .mouseDragScroll(state)
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val direction = when (e.key) {
                Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                else -> return@onKeyEvent false
            }
            if (!focusManager.moveFocus(direction)) {
                scope.launch { state.animateScrollTo(if (direction == androidx.compose.ui.focus.FocusDirection.Down) state.maxValue else 0) }
            }
            true
        }
        .verticalScroll(state)
}

/**
 * A page's scroll container and where it sits on screen (root coordinates), so drag and drop
 * inside can scroll along when a row is dragged to the edge.
 */
class ScrollArea(val state: ScrollState) {
    var top = 0f
    var bottom = 0f
}

val LocalScrollArea = androidx.compose.runtime.staticCompositionLocalOf<ScrollArea?> { null }

/** [controllerScroll] that also publishes itself as [LocalScrollArea] for the content. */
@Composable
fun ScrollAreaColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: androidx.compose.ui.Alignment.Horizontal = androidx.compose.ui.Alignment.Start,
    inner: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val state = androidx.compose.foundation.rememberScrollState()
    val area = androidx.compose.runtime.remember(state) { ScrollArea(state) }
    androidx.compose.foundation.layout.Column(
        modifier
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                area.top = bounds.top
                area.bottom = bounds.bottom
            }
            .controllerScroll(state)
            .then(inner),
        horizontalAlignment = horizontalAlignment,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalScrollArea provides area) { content() }
    }
}

