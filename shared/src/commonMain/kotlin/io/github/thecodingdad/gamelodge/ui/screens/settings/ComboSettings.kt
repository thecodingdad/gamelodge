package io.github.thecodingdad.gamelodge.ui.screens.settings

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.combo
import io.github.thecodingdad.gamelodge.data.settings.withCombo
import io.github.thecodingdad.gamelodge.input.ButtonCombos
import io.github.thecodingdad.gamelodge.input.ComboAction
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun comboTitle(action: ComboAction): StringResource = when (action) {
    ComboAction.HOME -> Res.string.combo_home
    ComboAction.QUICK_MENU -> Res.string.combo_quick_menu
    ComboAction.MOUSE -> Res.string.combo_mouse
    ComboAction.DISCONNECT -> Res.string.combo_disconnect
    ComboAction.STATS -> Res.string.combo_stats
}

fun comboSubtitle(action: ComboAction): StringResource = when (action) {
    ComboAction.HOME -> Res.string.combo_home_sub
    ComboAction.QUICK_MENU -> Res.string.combo_quick_menu_sub
    ComboAction.MOUSE -> Res.string.combo_mouse_sub
    ComboAction.DISCONNECT -> Res.string.combo_disconnect_sub
    ComboAction.STATS -> Res.string.combo_stats_sub
}

/**
 * One controller's combinations and pass-through, each either its own or the global one ([global]).
 * [combo] gives the controller's own combination (null = global), [descriptor] limits recording to it.
 */
@Composable
fun ControllerComboRows(
    global: LauncherSettings,
    combo: (ComboAction) -> Set<Int>?,
    forward: Boolean?,
    descriptor: String,
    onCombo: (ComboAction, Set<Int>?) -> Unit,
    onForward: (Boolean?) -> Unit,
    showForward: Boolean = true,
) {
    var editing by remember { mutableStateOf<ComboAction?>(null) }
    fun effective(a: ComboAction) = combo(a) ?: global.combo(a)
    ComboAction.entries.forEach { action ->
        val own = combo(action)
        NavRow(
            stringResource(comboTitle(action)), { editing = action },
            trailing = if (own == null) comboLabel(global.combo(action), global = true) else comboLabel(own),
        )
    }
    if (showForward) {
        val on = stringResource(Res.string.ha_on)
        val off = stringResource(Res.string.ha_off)
        ChoiceRow(
            stringResource(Res.string.combo_forward), listOf<Boolean?>(null, true, false), forward,
            label = { v ->
                when (v) {
                    null -> stringResource(Res.string.combo_global_value, if (global.comboForward) on else off)
                    true -> on
                    false -> off
                }
            },
            onSelect = onForward,
        )
    }
    val action = editing
    ComboDialog(
        action = action,
        current = action?.let { combo(it) },
        others = ComboAction.entries.filter { it != action }.map { effective(it) },
        global = action?.let { global.combo(it) }.orEmpty(),
        descriptor = descriptor,
        onSave = { keys -> action?.let { onCombo(it, keys) } },
        onDismiss = { editing = null },
    )
}

/** Pointer speed and curve of the mouse emulation (GameLodge's own pointer and the stream's). */
@Composable
fun MouseSettingsRows(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SliderRow(
        stringResource(Res.string.mouse_speed), settings.mouseSpeed, 25..300, 25,
        onChange = { v -> update { it.copy(mouseSpeed = v) } }, valueLabel = { "$it %" },
    )
    ChoiceRow(
        stringResource(Res.string.mouse_acceleration), io.github.thecodingdad.gamelodge.data.settings.MouseAcceleration.entries, settings.mouseAcceleration,
        label = {
            stringResource(
                when (it) {
                    io.github.thecodingdad.gamelodge.data.settings.MouseAcceleration.LOW -> Res.string.mouse_accel_low
                    io.github.thecodingdad.gamelodge.data.settings.MouseAcceleration.MEDIUM -> Res.string.mouse_accel_medium
                    io.github.thecodingdad.gamelodge.data.settings.MouseAcceleration.HIGH -> Res.string.mouse_accel_high
                },
            )
        },
        onSelect = { v -> update { it.copy(mouseAcceleration = v) } },
        subtitle = stringResource(Res.string.mouse_acceleration_sub),
    )
}

/** One row per [actions] with its combination, each editable in the record dialog. */
@Composable
fun ComboActionRows(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    actions: List<ComboAction> = ComboAction.entries,
    firstFocus: Boolean = false,
) {
    var editing by remember { mutableStateOf<ComboAction?>(null) }
    actions.forEachIndexed { i, action ->
        NavRow(
            stringResource(comboTitle(action)), { editing = action },
            subtitle = stringResource(comboSubtitle(action)),
            trailing = comboLabel(settings.combo(action)), initialFocus = firstFocus && i == 0,
        )
    }
    val action = editing
    ComboDialog(
        action = action,
        current = action?.let { settings.combo(it) }.orEmpty(),
        others = ComboAction.entries.filter { it != action }.map { settings.combo(it) },
        onSave = { keys -> action?.let { a -> update { it.withCombo(a, keys.orEmpty()) } } },
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
    others: Collection<Set<Int>>,
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
    val title = action?.let { stringResource(comboTitle(it)) }.orEmpty()
    val perController = descriptor != null
    Modal(visible = action != null, onDismiss = { if (!recording) onDismiss() }, title = title, maxWidth = 640.dp, closeButton = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    recording -> if (preview.isEmpty()) stringResource(Res.string.combo_recording_hint) else ButtonCombos.describe(preview, stringResource(Res.string.combo_hold))
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
                                (keys - ButtonCombos.HOLD).isNotEmpty() && keys in others -> Res.string.combo_error_duplicate
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
    val text = if (keys.isNullOrEmpty()) stringResource(Res.string.off) else ButtonCombos.describe(keys, stringResource(Res.string.combo_hold))
    return if (global) stringResource(Res.string.combo_global_value, text) else text
}
