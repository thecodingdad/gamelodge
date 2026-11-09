package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * When the focus moves, scroll so the focused element keeps [margin] to the container edges
 * instead of sticking to them. This also keeps row titles above a focused card visible.
 */
@OptIn(ExperimentalFoundationApi::class)
class MarginBringIntoViewSpec(density: Density, margin: Dp = 72.dp, trailingMargin: Dp = margin) : BringIntoViewSpec {
    private val leadingPx = with(density) { margin.toPx() }
    private val trailingPx = with(density) { trailingMargin.toPx() }

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Small containers can't fit both margins: shrink them proportionally.
        val free = (containerSize - size).coerceAtLeast(0f)
        val factor = if (leadingPx + trailingPx > free && leadingPx + trailingPx > 0f) free / (leadingPx + trailingPx) else 1f
        val leading = offset - leadingPx * factor
        val trailing = offset + size + trailingPx * factor - containerSize
        return when {
            leading < 0f -> leading
            trailing > 0f -> trailing.coerceAtMost(leading)
            else -> 0f
        }
    }
}

/**
 * Keeps the focused element at [startPx] from the container's start: the content moves under a
 * fixed focus. At the end the list can't scroll further, so from there the focus moves on.
 */
@OptIn(ExperimentalFoundationApi::class)
class PinnedStartBringIntoViewSpec(private val startPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - startPx
}
