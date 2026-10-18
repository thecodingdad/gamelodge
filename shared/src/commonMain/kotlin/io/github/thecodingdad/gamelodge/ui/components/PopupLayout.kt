package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/** Colour of settings rows where they sit (raised inside popups, so they stand out there). */
val LocalRowSurface = compositionLocalOf<Color?> { null }

/** How much taller than designed a popup's scroll area may grow on this screen (1 = as designed). */
val LocalPopupHeightScale = compositionLocalOf { 1f }

/** Popup height scale for the window: designed for about 900 dp of height, up to 1.6× on tall screens. */
@Composable
internal fun popupHeightScale(): Float {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return (height.value / 900f).coerceIn(1f, 1.6f)
}

/**
 * Maximum height of a scroll area in a popup: [max] as designed, more on tall screens. Only content
 * longer than that uses the room; short popups keep their size.
 */
@Composable
fun Modifier.popupHeightIn(max: Dp): Modifier = heightIn(max = max * LocalPopupHeightScale.current)
