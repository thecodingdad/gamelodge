package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Dragging with the mouse scrolls like a finger, with a fling on release. Scroll areas ignore mouse
 * drags by default, and Windows hands touch input to the app as mouse events: without this, touch
 * screens on the PC couldn't scroll. A drag cancels the tap on the element underneath; real touch
 * input (Android) is left to the scroll area itself.
 */
fun Modifier.mouseDragScroll(state: ScrollableState, orientation: Orientation = Orientation.Vertical): Modifier = composed {
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val slop = LocalViewConfiguration.current.touchSlop
    pointerInput(state, orientation) {
        awaitEachGesture {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            val first = down.changes.firstOrNull() ?: return@awaitEachGesture
            if (down.type != PointerEventType.Press || first.type != PointerType.Mouse || !down.buttons.isPrimaryPressed) return@awaitEachGesture
            val tracker = VelocityTracker().apply { addPosition(first.uptimeMillis, first.position) }
            var last = first.position
            var along = 0f
            var across = 0f
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                val delta = change.position - last
                last = change.position
                val main = if (orientation == Orientation.Vertical) delta.y else delta.x
                if (!change.pressed) {
                    if (dragging) {
                        change.consume()
                        val v = tracker.calculateVelocity()
                        val velocity = if (orientation == Orientation.Vertical) v.y else v.x
                        scope.launch { state.scroll { with(fling) { performFling(-velocity) } } }
                    }
                    break
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!dragging) {
                    along += main
                    across += if (orientation == Orientation.Vertical) delta.x else delta.y
                    // Only in this area's direction: a sideways row inside a vertical list each get their own drags.
                    dragging = abs(along) > slop && abs(along) > abs(across)
                    if (!dragging && abs(across) > slop) break
                    if (dragging) state.dispatchRawDelta(-along)
                } else {
                    state.dispatchRawDelta(-main)
                }
                if (dragging) change.consume()
            }
        }
    }
}
