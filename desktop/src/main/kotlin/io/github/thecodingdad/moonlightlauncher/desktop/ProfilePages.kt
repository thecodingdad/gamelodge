package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.desktop.stream.DesktopProfiles
import io.github.thecodingdad.moonlightlauncher.desktop.stream.ProfileOption
import io.github.thecodingdad.moonlightlauncher.desktop.stream.ProfileOptions
import io.github.thecodingdad.moonlightlauncher.resources.*
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.util.UUID

/** Settings → Stream profiles on the desktop. */
@Composable
fun DesktopProfilesList(store: DesktopProfiles, profiles: List<StreamProfile>, active: StreamProfile, openProfile: (UUID?) -> Unit) {
    val keyboard = LocalKeyboardController.current
    var focusedIndex by remember { mutableIntStateOf(0) }
    val newTitle = stringResource(Res.string.profile_new_name)
    PadAction(PadButton.X) { profiles.getOrNull(focusedIndex)?.let { store.setActive(it.uuid) } }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(Res.string.profile_edit)),
            ButtonHint(PadButton.X, stringResource(Res.string.profile_set_active)),
            ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
        ),
    )
    SettingsPage(stringResource(Res.string.settings_profiles)) {
        Text(stringResource(Res.string.profiles_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        profiles.forEachIndexed { index, profile ->
            val isActive = profile.uuid == active.uuid
            Box(Modifier.onFocusEvent { if (it.hasFocus) focusedIndex = index }) {
                NavRow(
                    title = profile.name,
                    onClick = { openProfile(profile.uuid) },
                    subtitle = if (profile.uuid == null) stringResource(Res.string.dprofile_base_sub) else store.summary(profile.uuid).ifEmpty { null },
                    icon = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                    initialFocus = index == 0,
                    trailing = if (isActive) stringResource(Res.string.profile_active) else null,
                )
            }
        }
        LauncherButton(
            stringResource(Res.string.profile_new), icon = Icons.Filled.Add,
            onClick = { keyboard.open(newTitle, "") { name -> if (name.isNotBlank()) openProfile(store.create(name.trim())) } },
        )
    }
}

/** One profile: each setting either Moonlight-Qt's own ("Standard") or a value of this profile. */
@Composable
fun DesktopProfileEditor(store: DesktopProfiles, uuid: UUID?, name: String, openMoonlightSettings: () -> Unit, onDeleted: () -> Unit) {
    val keyboard = LocalKeyboardController.current
    val renameTitle = stringResource(Res.string.profile_rename)
    // Re-read after every change.
    val revision by store.changes().collectAsState(Unit)
    SettingsPage(name) {
        if (uuid == null) {
            Text(stringResource(Res.string.dprofile_base_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherButton(stringResource(Res.string.dprofile_open_moonlight), onClick = openMoonlightSettings, icon = Icons.Filled.Settings, primary = true)
            return@SettingsPage
        }
        ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(Res.string.profile_set_active), icon = Icons.Filled.CheckCircle, onClick = { store.setActive(uuid) })
            LauncherButton(stringResource(Res.string.profile_rename), icon = Icons.Filled.Edit, onClick = {
                keyboard.open(renameTitle, name) { n -> if (n.isNotBlank()) store.rename(uuid, n.trim()) }
            })
            HoldToConfirmButton(stringResource(Res.string.profile_delete), onConfirmed = { store.delete(uuid); onDeleted() }, icon = Icons.Filled.Delete)
        }
        Text(stringResource(Res.string.dprofile_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        @Suppress("UNUSED_EXPRESSION") revision
        val values = store.profile(uuid)?.values.orEmpty()
        ProfileOptions.all.forEach { option -> OptionRow(option, values[option.key]) { store.set(uuid, option.key, it) } }
    }
}

private val titles: Map<String, StringResource> = mapOf(
    "resolution" to Res.string.dprofile_resolution, "fps" to Res.string.dprofile_fps, "bitrate" to Res.string.dprofile_bitrate,
    "display-mode" to Res.string.dprofile_display_mode, "vsync" to Res.string.dprofile_vsync, "frame-pacing" to Res.string.dprofile_frame_pacing,
    "hdr" to Res.string.dprofile_hdr, "yuv444" to Res.string.dprofile_yuv444, "video-codec" to Res.string.dprofile_codec,
    "video-decoder" to Res.string.dprofile_decoder, "audio-config" to Res.string.dprofile_audio, "audio-on-host" to Res.string.dprofile_audio_on_host,
    "multi-controller" to Res.string.dprofile_multi_controller, "game-optimization" to Res.string.dprofile_game_optimization,
    "quit-after" to Res.string.dprofile_quit_after, "performance-overlay" to Res.string.dprofile_overlay, "capture-system-keys" to Res.string.dprofile_system_keys,
)

@Composable
private fun OptionRow(option: ProfileOption, value: String?, onChange: (String?) -> Unit) {
    val choices: List<String?> = listOf(null) + if (option.toggle) listOf("true", "false") else option.values
    val standard = stringResource(Res.string.dprofile_standard)
    val on = stringResource(Res.string.dprofile_on)
    val off = stringResource(Res.string.dprofile_off)
    val labels = valueLabels()
    ChoiceRow(
        stringResource(titles.getValue(option.key)), choices, value,
        label = { v ->
            when {
                v == null -> standard
                option.toggle -> if (v == "true") on else off
                option.key == "bitrate" -> "${v.toInt() / 1000} Mbit/s"
                option.key == "resolution" -> v.replace('x', '×')
                else -> labels[v] ?: v
            }
        },
        onSelect = onChange,
    )
}

@Composable
private fun valueLabels(): Map<String, String> = mapOf(
    "fullscreen" to stringResource(Res.string.dprofile_fullscreen), "borderless" to stringResource(Res.string.dprofile_borderless),
    "windowed" to stringResource(Res.string.dprofile_windowed), "auto" to stringResource(Res.string.dprofile_auto),
    "hardware" to stringResource(Res.string.dprofile_hardware), "software" to stringResource(Res.string.dprofile_software),
    "stereo" to "Stereo", "5.1-surround" to "5.1 Surround", "7.1-surround" to "7.1 Surround",
    "never" to stringResource(Res.string.dprofile_never), "always" to stringResource(Res.string.dprofile_always),
)
