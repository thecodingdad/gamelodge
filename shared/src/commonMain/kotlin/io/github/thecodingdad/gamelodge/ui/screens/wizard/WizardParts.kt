package io.github.thecodingdad.gamelodge.ui.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/**
 * A big choice of the wizard (e.g. "Set up new" / "Restore from backup"): icon, title and one
 * sentence; [selected] marks the current one.
 */
@Composable
fun WizardChoiceCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
    initialFocus: Boolean = false,
) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick,
        // The chosen card has an accent frame (lighter is the focus).
        modifier = Modifier.fillMaxWidth().restoreFocusIf(initialFocus)
            .then(if (selected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(18.dp)) else Modifier),
        shape = RoundedCornerShape(18.dp),
        focusScale = 1.03f,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) colors.accent else colors.onSurface, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted) }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = colors.success)
        }
    }
}

/**
 * Something to set up with its state: done shows a check, otherwise [action] says what selecting
 * the row does ("Allow", "Pair" …). Without [onClick] it is only information.
 */
@Composable
fun WizardStatusRow(
    title: String,
    subtitle: String?,
    done: Boolean,
    icon: ImageVector? = null,
    action: String? = null,
    onClick: (() -> Unit)? = null,
    initialFocus: Boolean = false,
    /** A program's own icon instead of [icon] (emulators). */
    image: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val colors = LauncherTheme.colors
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (image != null) {
                androidx.compose.foundation.Image(image, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
            } else icon?.let {
                Icon(it, null, tint = colors.onSurface, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted) }
            }
            Spacer(Modifier.width(12.dp))
            when {
                done -> Icon(Icons.Filled.CheckCircle, null, tint = colors.success)
                action != null -> Text(action, style = MaterialTheme.typography.labelLarge, color = colors.accentText)
                else -> Unit
            }
            if (onClick != null && !done) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceMuted)
        }
    }
    if (onClick == null) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface),
        ) { content() }
    } else {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().restoreFocusIf(initialFocus),
            shape = RoundedCornerShape(14.dp),
            focusScale = 1.02f,
        ) { content() }
    }
}

/** A short explanation or hint between the rows of a step. */
@Composable
fun WizardNote(text: String, color: androidx.compose.ui.graphics.Color = LauncherTheme.colors.onSurfaceMuted) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}
