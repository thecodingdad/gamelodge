package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

/**
 * The user's own rating as five stars: tap, click or A on a star sets it, the same star again
 * clears it; with a controller left / right moves between the stars.
 */
@Composable
fun StarRating(stars: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val colors = LauncherTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(Res.string.rating_own), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted,
            modifier = Modifier.padding(end = 10.dp),
        )
        for (n in 1..5) {
            val filled = (stars ?: 0) >= n
            FocusSurface(
                onClick = { onRate(if (stars == n) null else n) },
                shape = CircleShape, color = Color.Transparent, focusScale = 1.15f, glow = false,
            ) {
                Icon(
                    if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(Res.string.rating_stars, n),
                    tint = if (filled) colors.accent else colors.onSurfaceMuted,
                    modifier = Modifier.padding(6.dp).size(28.dp),
                )
            }
        }
    }
}
