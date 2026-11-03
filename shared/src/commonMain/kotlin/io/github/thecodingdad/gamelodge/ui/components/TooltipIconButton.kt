package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/**
 * A round icon-only button. Its [label] shows as a tooltip above it while it has the controller
 * focus or the mouse over it (touch users get it as the content description).
 */
@Composable
fun TooltipIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val controller = isControllerMode()
    val colors = LauncherTheme.colors
    Box(modifier) {
        FocusSurface(
            onClick = onClick, modifier = Modifier.size(52.dp), shape = CircleShape, color = colors.surface,
            focusScale = 1.1f, interactionSource = interaction,
        ) {
            Icon(icon, label, tint = colors.onSurface, modifier = Modifier.align(Alignment.Center).size(24.dp))
        }
        if ((focused && controller) || hovered) {
            val lift = with(LocalDensity.current) { 46.dp.roundToPx() }
            Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -lift), properties = PopupProperties(focusable = false)) {
                Text(
                    label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 1, softWrap = false,
                    modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
