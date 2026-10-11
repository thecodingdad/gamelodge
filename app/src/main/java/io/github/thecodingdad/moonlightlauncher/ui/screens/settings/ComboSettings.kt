package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.input.ButtonCombos
import io.github.thecodingdad.moonlightlauncher.input.ComboAccessibilityService
import io.github.thecodingdad.moonlightlauncher.input.ComboAction
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/** Rows for the Home / quick menu button combinations plus the optional system-wide service. */
@Composable
fun ButtonComboRows(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ComboAction?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val serviceOn = remember(tick) { ComboAccessibilityService.isEnabled(context) }
    val off = stringResource(R.string.off)

    SectionHeader(stringResource(R.string.combo_section))
    NavRow(
        stringResource(R.string.combo_home), { editing = ComboAction.HOME },
        subtitle = stringResource(R.string.combo_home_sub),
        trailing = settings.comboHome.takeIf { it.isNotEmpty() }?.let(ButtonCombos::describe) ?: off,
    )
    NavRow(
        stringResource(R.string.combo_quick_menu), { editing = ComboAction.QUICK_MENU },
        subtitle = stringResource(R.string.combo_quick_menu_sub),
        trailing = settings.comboQuickMenu.takeIf { it.isNotEmpty() }?.let(ButtonCombos::describe) ?: off,
    )
    NavRow(
        stringResource(R.string.combo_service), {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        subtitle = stringResource(R.string.combo_service_sub),
        trailing = stringResource(if (serviceOn) R.string.combo_service_on else R.string.off),
    )

    val action = editing
    ComboDialog(
        action = action,
        current = when (action) {
            ComboAction.HOME -> settings.comboHome
            ComboAction.QUICK_MENU -> settings.comboQuickMenu
            null -> emptySet()
        },
        other = when (action) {
            ComboAction.HOME -> settings.comboQuickMenu
            ComboAction.QUICK_MENU -> settings.comboHome
            null -> emptySet()
        },
        onSave = { keys ->
            when (action) {
                ComboAction.HOME -> update { it.copy(comboHome = keys) }
                ComboAction.QUICK_MENU -> update { it.copy(comboQuickMenu = keys) }
                null -> Unit
            }
        },
        onDismiss = { editing = null },
    )
}

@Composable
private fun ComboDialog(
    action: ComboAction?,
    current: Set<Int>,
    other: Set<Int>,
    onSave: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var recording by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val preview by ButtonCombos.recordingPreview.collectAsState()
    // While recording every controller button is captured, so B can't cancel: time out instead.
    LaunchedEffect(recording) {
        if (recording) {
            delay(10_000)
            ButtonCombos.cancelRecording()
            recording = false
        }
    }
    DisposableEffect(action) { onDispose { ButtonCombos.cancelRecording(); recording = false; error = null } }
    val title = when (action) {
        ComboAction.QUICK_MENU -> stringResource(R.string.combo_quick_menu)
        else -> stringResource(R.string.combo_home)
    }
    Modal(visible = action != null, onDismiss = { if (!recording) onDismiss() }, title = title, maxWidth = 640.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val shown = if (recording) preview else current
            Text(
                if (shown.isEmpty()) stringResource(if (recording) R.string.combo_recording_hint else R.string.combo_none)
                else ButtonCombos.describe(shown),
                style = MaterialTheme.typography.headlineSmall,
                color = if (recording) LauncherTheme.colors.accent else LauncherTheme.colors.onSurface,
            )
            Text(
                stringResource(if (recording) R.string.combo_recording_help else R.string.combo_help),
                style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
            )
            error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (!recording) {
                ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
                    LauncherButton(stringResource(R.string.combo_record), onClick = {
                        error = null
                        recording = true
                        ButtonCombos.startRecording { keys ->
                            recording = false
                            error = when {
                                ButtonCombos.isBlocked(keys) -> R.string.combo_error_blocked
                                keys == other -> R.string.combo_error_duplicate
                                else -> null
                            }
                            if (error == null) onSave(keys)
                        }
                    }, primary = true, icon = Icons.Filled.FiberManualRecord)
                    if (current.isNotEmpty()) {
                        LauncherButton(stringResource(R.string.combo_clear), onClick = { onSave(emptySet()) }, icon = Icons.Filled.Delete)
                    }
                    LauncherButton(stringResource(R.string.action_done), onClick = onDismiss)
                }
            }
        }
    }
}
