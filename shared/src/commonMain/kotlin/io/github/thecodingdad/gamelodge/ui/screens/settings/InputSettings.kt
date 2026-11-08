package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.MouseSideButtons
import io.github.thecodingdad.gamelodge.input.KeyAction
import io.github.thecodingdad.gamelodge.input.KeyShortcut
import io.github.thecodingdad.gamelodge.input.KeyboardKeys
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A key's name as printed on the keyboard ("Bild ↑", "Pos1" … in German). */
@Composable
fun keyDisplayName(name: String): String = when (name) {
    "PageUp" -> stringResource(Res.string.key_name_page_up)
    "PageDown" -> stringResource(Res.string.key_name_page_down)
    "Home" -> stringResource(Res.string.key_name_home)
    "End" -> stringResource(Res.string.key_name_end)
    "Insert" -> stringResource(Res.string.key_name_insert)
    "Menu" -> stringResource(Res.string.key_name_menu)
    else -> name
}

/** "Strg+Alt+M" in German. */
@Composable
fun shortcutDisplayName(s: KeyShortcut): String =
    listOfNotNull(stringResource(Res.string.key_name_ctrl).takeIf { s.ctrl }, "Alt".takeIf { s.alt }, "Shift".takeIf { s.shift }, s.key)
        .joinToString("+")

private fun actionTitle(a: KeyAction): StringResource = when (a) {
    KeyAction.A -> Res.string.key_action_a
    KeyAction.B -> Res.string.key_action_b
    KeyAction.X -> Res.string.key_action_x
    KeyAction.Y -> Res.string.key_action_y
    KeyAction.L1 -> Res.string.key_action_l1
    KeyAction.R1 -> Res.string.key_action_r1
    KeyAction.L2 -> Res.string.key_action_l2
    KeyAction.R2 -> Res.string.key_action_r2
    KeyAction.START -> Res.string.key_action_start
    KeyAction.SELECT -> Res.string.key_action_select
    KeyAction.QUICK_MENU -> Res.string.combo_quick_menu
    KeyAction.HOME -> Res.string.combo_home
}

/**
 * Settings › Touch, mouse & keyboard: the device's touch / keyboard options of the stream (the
 * platform's, [deviceRows]), the mouse's side buttons, the keyboard's keys in GameLodge and the
 * system-wide quick menu shortcut.
 */
@Composable
fun InputSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val platform = io.github.thecodingdad.gamelodge.ui.PlatformUi.current
    var recordingAction by remember { mutableStateOf<KeyAction?>(null) }
    var recordingShortcut by remember { mutableStateOf(false) }
    var recordingDisconnect by remember { mutableStateOf(false) }
    val disconnect = KeyShortcut.parse(settings.keyboardDisconnect)
    val off = stringResource(Res.string.off)
    val shortcut = KeyShortcut.parse(settings.keyboardQuickMenu)

    SettingsPage(stringResource(Res.string.settings_input)) {
        // Touch (the screen as a trackpad in the stream): only where the platform has such options.
        platform.DeviceInputRows(io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.TOUCH, initialFocus = true)

        SectionHeader(stringResource(Res.string.input_mouse_section))
        ChoiceRow(
            stringResource(Res.string.input_mouse_side), MouseSideButtons.entries, settings.mouseSideButtons,
            label = { stringResource(if (it == MouseSideButtons.BACK) Res.string.input_mouse_side_back else Res.string.input_mouse_side_tabs) },
            onSelect = { v -> update { it.copy(mouseSideButtons = v) } },
            subtitle = stringResource(Res.string.input_mouse_side_sub),
        )
        platform.DeviceInputRows(io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.MOUSE, initialFocus = false)

        SectionHeader(stringResource(Res.string.input_keyboard_section))
        Text(stringResource(Res.string.input_keyboard_intro), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        NavRow(
            stringResource(Res.string.input_shortcut), { recordingShortcut = true },
            subtitle = stringResource(if (platform.hasButtonCombos) Res.string.input_shortcut_sub else Res.string.input_shortcut_sub_pc),
            trailing = shortcut?.let { shortcutDisplayName(it) } ?: off,
        )
        if (platform.hasDisconnectShortcut) {
            NavRow(
                stringResource(Res.string.input_disconnect), { recordingDisconnect = true },
                subtitle = stringResource(Res.string.input_disconnect_sub),
                trailing = disconnect?.let { shortcutDisplayName(it) } ?: off,
            )
        }
        KeyAction.entries.forEach { action ->
            val key = KeyboardKeys.keyOf(action, settings.keyboardKeys)
            NavRow(
                stringResource(actionTitle(action)), { recordingAction = action },
                subtitle = when (action) {
                    KeyAction.A -> stringResource(Res.string.key_action_a_sub)
                    KeyAction.B -> stringResource(Res.string.key_action_b_sub)
                    else -> null
                },
                trailing = key?.let { keyDisplayName(it) } ?: off,
            )
        }
        // Which on-screen keyboard the stream shows (quick menu, button combination).
        if (platform.hasStreamKeyboard) {
            ChoiceRow(
                stringResource(Res.string.input_stream_keyboard), io.github.thecodingdad.gamelodge.data.settings.StreamKeyboard.entries, settings.streamKeyboard,
                label = { stringResource(if (it == io.github.thecodingdad.gamelodge.data.settings.StreamKeyboard.SYSTEM) Res.string.input_stream_keyboard_system else Res.string.input_stream_keyboard_pc) },
                onSelect = { v -> update { it.copy(streamKeyboard = v) } },
                subtitle = stringResource(Res.string.input_stream_keyboard_sub),
            )
        }
        // The stream's keyboard options of this device (Android).
        platform.DeviceInputRows(io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.KEYBOARD, initialFocus = false)

        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_controllers), SettingsTarget.Controllers),
            SeeAlsoLink(stringResource(Res.string.settings_profiles), SettingsTarget.Page(SettingsPageId.HOSTS), highlight = stringResource(Res.string.settings_profiles), subtitle = stringResource(Res.string.settings_hosts)),
        )
    }

    KeyRecordDialog(
        title = recordingAction?.let { stringResource(actionTitle(it)) },
        help = stringResource(Res.string.input_record_key_help),
        current = recordingAction?.let { KeyboardKeys.keyOf(it, settings.keyboardKeys) }?.let { keyDisplayName(it) },
        isDefault = recordingAction?.let { it.name !in settings.keyboardKeys } ?: true,
        start = { done ->
            val action = recordingAction
            KeyboardKeys.recording = { name -> if (name != null && action != null) update { s -> s.withKey(action, name) }; done() }
        },
        stop = { KeyboardKeys.recording = null },
        onDefault = { recordingAction?.let { a -> update { s -> s.copy(keyboardKeys = s.keyboardKeys - a.name) } } },
        onNone = { recordingAction?.let { a -> update { s -> s.copy(keyboardKeys = s.keyboardKeys + (a.name to "")) } } },
        onDismiss = { recordingAction = null },
    )
    KeyRecordDialog(
        title = if (recordingShortcut) stringResource(Res.string.input_shortcut) else null,
        help = stringResource(Res.string.input_record_shortcut_help),
        current = shortcut?.let { shortcutDisplayName(it) },
        isDefault = true,
        start = { done -> KeyboardKeys.recordingShortcut = { s -> if (s != null) update { it.copy(keyboardQuickMenu = s.toString()) }; done() } },
        stop = { KeyboardKeys.recordingShortcut = null },
        onDefault = null,
        onNone = { update { it.copy(keyboardQuickMenu = "") } },
        onDismiss = { recordingShortcut = false },
    )
    KeyRecordDialog(
        title = if (recordingDisconnect) stringResource(Res.string.input_disconnect) else null,
        help = stringResource(Res.string.input_record_shortcut_help),
        current = disconnect?.let { shortcutDisplayName(it) },
        isDefault = settings.keyboardDisconnect == io.github.thecodingdad.gamelodge.data.settings.DEFAULT_DISCONNECT_SHORTCUT,
        start = { done -> KeyboardKeys.recordingShortcut = { s -> if (s != null) update { it.copy(keyboardDisconnect = s.toString()) }; done() } },
        stop = { KeyboardKeys.recordingShortcut = null },
        onDefault = { update { it.copy(keyboardDisconnect = io.github.thecodingdad.gamelodge.data.settings.DEFAULT_DISCONNECT_SHORTCUT) } },
        onNone = { update { it.copy(keyboardDisconnect = "") } },
        onDismiss = { recordingDisconnect = false },
    )
}

/** [action] on [key]; a key has one action only (it leaves the action that had it). */
private fun LauncherSettings.withKey(action: KeyAction, key: String): LauncherSettings {
    val freed = KeyAction.entries.filter { it != action && KeyboardKeys.keyOf(it, keyboardKeys) == key }
    return copy(keyboardKeys = keyboardKeys + freed.associate { it.name to "" } + (action.name to key))
}

/**
 * Waits for a key while open ([start] / [stop] hook the platform's key handling up). Esc or
 * "Cancel" closes it; [onDefault] (null = none) and [onNone] reset the key.
 */
@Composable
private fun KeyRecordDialog(
    title: String?,
    help: String,
    current: String?,
    isDefault: Boolean,
    start: (done: () -> Unit) -> Unit,
    stop: () -> Unit,
    onDefault: (() -> Unit)?,
    onNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = title != null
    DisposableEffect(visible) {
        if (visible) start { onDismiss() }
        onDispose { stop() }
    }
    Modal(visible = visible, onDismiss = onDismiss, title = title.orEmpty(), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD, closeButton = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.input_record_waiting), style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.accent)
            Text(help, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            Text(
                stringResource(Res.string.input_record_current, current ?: stringResource(Res.string.off)),
                style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface,
            )
            ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
                if (onDefault != null && !isDefault) {
                    LauncherButton(stringResource(Res.string.combo_use_global), onClick = { onDefault(); onDismiss() }, icon = Icons.Filled.Restore)
                }
                if (current != null) {
                    LauncherButton(stringResource(Res.string.combo_off), onClick = { onNone(); onDismiss() }, icon = Icons.Filled.Block)
                }
                LauncherButton(stringResource(Res.string.action_cancel), onClick = onDismiss, primary = true)
            }
        }
    }
}
