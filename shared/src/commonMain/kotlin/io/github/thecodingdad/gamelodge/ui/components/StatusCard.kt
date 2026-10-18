package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/**
 * State of a feature at the top of its settings page: coloured icon, state and details, actions
 * below (buttons, so the controller reaches them like any row). [busy] shows a spinner instead of the icon.
 */
@Composable
fun StatusCard(
    title: String,
    color: Color,
    icon: ImageVector,
    details: List<String> = emptyList(),
    busy: Boolean = false,
    actions: (@Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LauncherTheme.colors.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = color, strokeWidth = 2.5.dp)
                else Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
                details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted) }
            }
        }
        actions?.let { ButtonRow(content = it) }
    }
}
