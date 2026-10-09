package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Scrollable settings page with title; the first focusable element gets initial focus. */
@Composable
fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 16.dp)
            .focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = LauncherTheme.colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = LauncherTheme.colors.accent,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun RowShell(
    onClick: () -> Unit,
    modifier: Modifier,
    initialFocus: Boolean,
    content: @Composable () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp).restoreFocusIf(initialFocus),
        color = LauncherTheme.colors.surface,
        focusScale = 1.02f,
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun Texts(title: String, subtitle: String?, icon: ImageVector?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = LauncherTheme.colors.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            }
        }
    }
}

/** Opens a sub page or performs an action. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    initialFocus: Boolean = false,
    trailing: String? = null,
) {
    RowShell(onClick, Modifier, initialFocus) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Texts(title, subtitle, icon, Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurfaceMuted)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    initialFocus: Boolean = false,
) {
    RowShell({ onChange(!checked) }, Modifier, initialFocus) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Texts(title, subtitle, icon, Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = LauncherTheme.colors.accent),
            )
        }
    }
}

/** Cycles through [options] with A, D-pad left/right or touch on the arrows. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    initialFocus: Boolean = false,
) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    val feedback = LocalUiFeedback.current
    fun step(delta: Int) {
        feedback?.play(UiSound.TOGGLE)
        onSelect(options[(index + delta).mod(options.size)])
    }
    RowShell(
        onClick = { step(1) },
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        },
        initialFocus = initialFocus,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Texts(title, subtitle, icon, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = LauncherTheme.colors.onSurfaceMuted)
            Text(
                label(options[index]),
                style = MaterialTheme.typography.labelLarge,
                color = LauncherTheme.colors.onSurface,
                modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}

/** Value slider controlled by D-pad left/right (hold to repeat) or touch drag. */
@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
    valueLabel: (Int) -> String = { it.toString() },
    icon: ImageVector? = null,
) {
    RowShell(
        onClick = {},
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // Holding the key repeats; repeats accelerate after the first few steps.
            val factor = if (e.nativeKeyEvent.repeatCount > 6) 4 else 1
            when (e.key) {
                Key.DirectionLeft -> { onChange((value - step * factor).coerceIn(range)); true }
                Key.DirectionRight -> { onChange((value + step * factor).coerceIn(range)); true }
                else -> false
            }
        },
        initialFocus = false,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Texts(title, null, icon, Modifier.weight(1f))
                Text(valueLabel(value), style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurface)
            }
            androidx.compose.material3.Slider(
                value = value.toFloat(),
                onValueChange = { onChange(((it / step).toInt() * step).coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
        }
    }
}

/** Plain info line (versions, status). */
@Composable
fun InfoRow(title: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LauncherTheme.colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
    }
}
