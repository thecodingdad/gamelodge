package io.github.thecodingdad.moonlightlauncher.ui.components

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
class MarginBringIntoViewSpec(density: Density, margin: Dp = 72.dp) : BringIntoViewSpec {
    private val marginPx = with(density) { margin.toPx() }

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Small containers can't fit both margins: fall back to a proportional margin.
        val m = marginPx.coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
        val leading = offset - m
        val trailing = offset + size + m - containerSize
        return when {
            leading < 0f -> leading
            trailing > 0f -> trailing.coerceAtMost(leading)
            else -> 0f
        }
    }
}
