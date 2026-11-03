package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/**
 * Draws [content] at most [maxWidth] × [maxHeight] pixels and scales the picture up to the space
 * it has (e.g. a 4K TV): the GPU draws a quarter of the pixels, the layout and sizes stay the same
 * (the density shrinks with the pixels). Without [enabled] or on smaller screens it draws natively.
 */
@Composable
fun RenderAtMost(enabled: Boolean, maxWidth: Int = 1920, maxHeight: Int = 1080, content: @Composable () -> Unit) {
    if (!enabled) return content()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scale = maxOf(width / maxWidth.toFloat(), height / maxHeight.toFloat())
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight || scale <= 1.01f) {
            content()
            return@BoxWithConstraints
        }
        val outer = LocalDensity.current
        val innerWidth = (width / scale).roundToInt()
        val innerHeight = (height / scale).roundToInt()
        Box(
            Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(innerWidth, innerHeight))
                layout(width, height) {
                    // An offscreen layer of the small size, drawn scaled up: everything inside is
                    // rasterised at the small size.
                    placeable.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalDensity provides Density(outer.density / scale, outer.fontScale)) { content() }
        }
    }
}
