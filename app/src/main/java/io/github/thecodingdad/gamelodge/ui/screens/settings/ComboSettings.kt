package io.github.thecodingdad.gamelodge.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Public
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.input.ButtonCombos
import io.github.thecodingdad.gamelodge.input.ComboAccessibilityService
import io.github.thecodingdad.gamelodge.input.ComboAction
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import io.github.thecodingdad.gamelodge.resources.*

/** Rows for the Home / quick menu button combinations plus the optional system-wide service. */
@Composable
fun ButtonComboRows(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ComboAction?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val serviceOn = remember(tick) { ComboAccessibilityService.isEnabled(context) }
    val off = stringResource(Res.string.off)

    SectionHeader(stringResource(Res.string.combo_section))
    NavRow(
        stringResource(Res.string.combo_home), { editing = ComboAction.HOME },
        subtitle = stringResource(Res.string.combo_home_sub),
        trailing = settings.comboHome.takeIf { it.isNotEmpty() }?.let(ButtonCombos::describe) ?: off,
    )
    NavRow(
        stringResource(Res.string.combo_quick_menu), { editing = ComboAction.QUICK_MENU },
        subtitle = stringResource(Res.string.combo_quick_menu_sub),
        trailing = settings.comboQuickMenu.takeIf { it.isNotEmpty() }?.let(ButtonCombos::describe) ?: off,
    )
    NavRow(
        stringResource(Res.string.combo_service), {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        subtitle = stringResource(Res.string.combo_service_sub),
        trailing = stringResource(if (serviceOn) Res.string.combo_service_on else Res.string.off),
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
            val k = keys.orEmpty()
            when (action) {
                ComboAction.HOME -> update { it.copy(comboHome = k) }
                ComboAction.QUICK_MENU -> update { it.copy(comboQuickMenu = k) }
                null -> Unit
            }
        },
        onDismiss = { editing = null },
    )
}

/**
 * Records a combination for [action]. [current] null = the controller follows the global one
 * ([global] shown), only possible with a [descriptor] (per-controller dialog, which also
 * records from that controller only).
 */
@Composable
fun ComboDialog(
    action: ComboAction?,
    current: Set<Int>?,
    other: Set<Int>,
    onSave: (Set<Int>?) -> Unit,
    onDismiss: () -> Unit,
    descriptor: String? = null,
    global: Set<Int> = emptySet(),
) {
    var recording by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<org.jetbrains.compose.resources.StringResource?>(null) }
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
        ComboAction.QUICK_MENU -> stringResource(Res.string.combo_quick_menu)
        else -> stringResource(Res.string.combo_home)
    }
    val perController = descriptor != null
    Modal(visible = action != null, onDismiss = { if (!recording) onDismiss() }, title = title, maxWidth = 640.dp, closeButton = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    recording -> if (preview.isEmpty()) stringResource(Res.string.combo_recording_hint) else ButtonCombos.describe(preview)
                    current == null -> comboLabel(global, global = true)
                    else -> comboLabel(current)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = if (recording) LauncherTheme.colors.accent else LauncherTheme.colors.onSurface,
            )
            Text(
                stringResource(
                    when {
                        recording && perController -> Res.string.combo_recording_help_controller
                        recording -> Res.string.combo_recording_help
                        else -> Res.string.combo_help
                    },
                ),
                style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
            )
            error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (!recording) {
                ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
                    LauncherButton(stringResource(Res.string.combo_record), onClick = {
                        error = null
                        recording = true
                        ButtonCombos.startRecording(descriptor) { keys ->
                            recording = false
                            error = when {
                                ButtonCombos.isBlocked(keys) -> Res.string.combo_error_blocked
                                keys == other -> Res.string.combo_error_duplicate
                                else -> null
                            }
                            if (error == null) onSave(keys)
                        }
                    }, primary = true, icon = Icons.Filled.FiberManualRecord)
                    if (perController && current != null) {
                        LauncherButton(stringResource(Res.string.combo_use_global), onClick = { onSave(null) }, icon = Icons.Filled.Public)
                    }
                    if (current == null || current.isNotEmpty()) {
                        LauncherButton(
                            stringResource(if (perController) Res.string.combo_off else Res.string.combo_clear),
                            onClick = { onSave(emptySet()) }, icon = Icons.Filled.Delete,
                        )
                    }
                    LauncherButton(stringResource(Res.string.action_done), onClick = onDismiss)
                }
            }
        }
    }
}

/** "LB + RB", "Off", or for a controller following the global one "Global (LB + RB)". */
@Composable
fun comboLabel(keys: Set<Int>?, global: Boolean = false): String {
    val text = if (keys.isNullOrEmpty()) stringResource(Res.string.off) else ButtonCombos.describe(keys)
    return if (global) stringResource(Res.string.combo_global_value, text) else text
}
