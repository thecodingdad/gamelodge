package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft edges on a scroll area while it can still scroll that way: the content fades out towards
 * the edge (so it fits any background). Put it before the scroll modifier. [bleed] room across the
 * scroll direction keeps focus rings and glows of the content from being cut off by the fade layer.
 */
@Composable
fun Modifier.fadingEdges(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    length: Dp = 44.dp,
    bleed: Dp = 16.dp,
): Modifier {
    val start by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, label = "fadeStart")
    val end by animateFloatAsState(if (state.canScrollForward) 1f else 0f, label = "fadeEnd")
    val vertical = orientation == Orientation.Vertical
    return this
        .layout { measurable, constraints ->
            val m = bleed.roundToPx()
            val placeable = if (vertical) {
                measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + 2 * m,
                        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * m else constraints.maxWidth,
                    ),
                )
            } else {
                measurable.measure(
                    constraints.copy(
                        minHeight = constraints.minHeight + 2 * m,
                        maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * m else constraints.maxHeight,
                    ),
                )
            }
            if (vertical) layout(placeable.width - 2 * m, placeable.height) { placeable.place(-m, 0) }
            else layout(placeable.width, placeable.height - 2 * m) { placeable.place(0, -m) }
        }
        // Offscreen only while fading: the mask needs its own layer.
        .graphicsLayer { compositingStrategy = if (start > 0f || end > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            val px = length.toPx()
            if (vertical) {
                if (start > 0f) {
                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = start), Color.Transparent), 0f, px), size = Size(size.width, px), blendMode = BlendMode.DstOut)
                }
                if (end > 0f) {
                    val top = size.height - px
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = end)), top, size.height), Offset(0f, top), Size(size.width, px), blendMode = BlendMode.DstOut)
                }
            } else {
                if (start > 0f) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = start), Color.Transparent), 0f, px), size = Size(px, size.height), blendMode = BlendMode.DstOut)
                }
                if (end > 0f) {
                    val left = size.width - px
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = end)), left, size.width), Offset(left, 0f), Size(px, size.height), blendMode = BlendMode.DstOut)
                }
            }
        }
        .then(if (vertical) Modifier.padding(horizontal = bleed) else Modifier.padding(vertical = bleed))
}
