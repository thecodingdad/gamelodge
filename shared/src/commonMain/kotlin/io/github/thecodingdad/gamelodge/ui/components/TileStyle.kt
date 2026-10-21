package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.FocusZoom
import io.github.thecodingdad.gamelodge.data.settings.TileCorners

/** Look of the game and app tiles in every tab (appearance settings; corners are per tab). */
@Immutable
data class TileStyle(
    val zoom: FocusZoom = FocusZoom.LIGHT,
    val glow: Boolean = true,
    val dimNotInstalled: Boolean = true,
) {
    /** The focus zoom for a tile whose normal zoom is [base]. */
    fun scale(base: Float = 1.06f): Float = when (zoom) {
        FocusZoom.NONE -> 1f
        FocusZoom.LIGHT -> base
        FocusZoom.STRONG -> 1f + (base - 1f) * 2f
    }
}

/** A tile's outline for its tab's corner setting. */
val TileCorners.shape get() = RoundedCornerShape(
    when (this) {
        TileCorners.SHARP -> 4.dp
        TileCorners.SLIGHT -> 14.dp
        TileCorners.ROUND -> 26.dp
    },
)

val LocalTileStyle = staticCompositionLocalOf { TileStyle() }
