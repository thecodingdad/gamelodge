package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.feedback.HapticStrength
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.SoundStyle
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound

/** UI sounds and vibration: volume, timbre, pitch, per-event switches, strength, preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val feedback = LocalUiFeedback.current
    SettingsPage(stringResource(R.string.settings_sounds)) {
        SectionHeader(stringResource(R.string.sounds_section))
        SwitchRow(stringResource(R.string.setting_ui_sounds), settings.uiSounds, { v -> update { it.copy(uiSounds = v) } }, initialFocus = true)
        SliderRow(
            stringResource(R.string.setting_ui_volume), settings.uiSoundVolume, 0..100, 5,
            { v -> update { it.copy(uiSoundVolume = v) } }, { "$it %" },
        )
        ChoiceRow(
            stringResource(R.string.setting_sound_style), SoundStyle.entries.map { it.name }, settings.soundStyle,
            label = {
                stringResource(
                    when (it) {
                        "CLICK" -> R.string.sound_style_click
                        "RETRO" -> R.string.sound_style_retro
                        "GLASS" -> R.string.sound_style_glass
                        else -> R.string.sound_style_soft
                    },
                )
            },
            onSelect = { v -> update { it.copy(soundStyle = v) } },
        )
        SliderRow(
            stringResource(R.string.setting_sound_pitch), settings.soundPitch, -6..6, 1,
            { v -> update { it.copy(soundPitch = v) } }, { if (it > 0) "+$it" else "$it" },
        )
        SectionHeader(stringResource(R.string.sounds_events))
        listOf(
            UiSound.FOCUS to R.string.sound_focus,
            UiSound.CONFIRM to R.string.sound_confirm,
            UiSound.TOGGLE to R.string.sound_toggle,
            UiSound.BACK to R.string.sound_back,
            UiSound.LAUNCH to R.string.sound_launch,
            UiSound.ERROR to R.string.sound_error,
        ).forEach { (sound, label) ->
            SwitchRow(stringResource(label), sound.name !in settings.mutedSounds, { on ->
                update { s -> s.copy(mutedSounds = if (on) s.mutedSounds - sound.name else s.mutedSounds + sound.name) }
                if (on) feedback?.preview(sound)
            })
        }
        SectionHeader(stringResource(R.string.sounds_preview))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                UiSound.FOCUS to R.string.sound_focus, UiSound.CONFIRM to R.string.sound_confirm,
                UiSound.BACK to R.string.sound_back, UiSound.LAUNCH to R.string.sound_launch, UiSound.ERROR to R.string.sound_error,
            ).forEach { (sound, label) ->
                LauncherButton(stringResource(label), onClick = { feedback?.preview(sound) }, icon = Icons.Filled.PlayArrow)
            }
        }
        SectionHeader(stringResource(R.string.haptics_section))
        SwitchRow(stringResource(R.string.setting_haptics), settings.haptics, { v -> update { it.copy(haptics = v) } })
        ChoiceRow(
            stringResource(R.string.setting_haptic_strength), HapticStrength.entries.map { it.name }, settings.hapticStrength,
            label = {
                stringResource(
                    when (it) {
                        "LIGHT" -> R.string.haptic_light
                        "STRONG" -> R.string.haptic_strong
                        else -> R.string.haptic_medium
                    },
                )
            },
            onSelect = { v ->
                update { it.copy(hapticStrength = v) }
            },
        )
        SwitchRow(stringResource(R.string.setting_haptic_focus), settings.hapticOnFocus, { v -> update { it.copy(hapticOnFocus = v) } })
    }
}
