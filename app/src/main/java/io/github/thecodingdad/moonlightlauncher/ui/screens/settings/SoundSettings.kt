package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.feedback.HapticStrength
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.SoundStyle
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.resources.*

/** UI sounds and vibration: volume, timbre, pitch, per-event switches, strength, preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val feedback = LocalUiFeedback.current
    SettingsPage(stringResource(Res.string.settings_sounds)) {
        SectionHeader(stringResource(Res.string.sounds_section))
        SwitchRow(stringResource(Res.string.setting_ui_sounds), settings.uiSounds, { v -> update { it.copy(uiSounds = v) } }, initialFocus = true)
        SliderRow(
            stringResource(Res.string.setting_ui_volume), settings.uiSoundVolume, 0..100, 5,
            { v -> update { it.copy(uiSoundVolume = v) } }, { "$it %" },
        )
        ChoiceRow(
            stringResource(Res.string.setting_sound_style), SoundStyle.entries.map { it.name }, settings.soundStyle,
            label = {
                stringResource(
                    when (it) {
                        "MUFFLED" -> Res.string.sound_style_muffled
                        "WOOD" -> Res.string.sound_style_wood
                        "DEEP" -> Res.string.sound_style_deep
                        "CLICK" -> Res.string.sound_style_click
                        "RETRO" -> Res.string.sound_style_retro
                        "GLASS" -> Res.string.sound_style_glass
                        else -> Res.string.sound_style_soft
                    },
                )
            },
            onSelect = { v -> update { it.copy(soundStyle = v) } },
        )
        SliderRow(
            stringResource(Res.string.setting_sound_pitch), settings.soundPitch, -6..6, 1,
            { v -> update { it.copy(soundPitch = v) } }, { if (it > 0) "+$it" else "$it" },
        )
        SectionHeader(stringResource(Res.string.sounds_events))
        listOf(
            UiSound.FOCUS to Res.string.sound_focus,
            UiSound.CONFIRM to Res.string.sound_confirm,
            UiSound.TOGGLE to Res.string.sound_toggle,
            UiSound.BACK to Res.string.sound_back,
            UiSound.LAUNCH to Res.string.sound_launch,
            UiSound.ERROR to Res.string.sound_error,
        ).forEach { (sound, label) ->
            SwitchRow(stringResource(label), sound.name !in settings.mutedSounds, { on ->
                update { s -> s.copy(mutedSounds = if (on) s.mutedSounds - sound.name else s.mutedSounds + sound.name) }
                if (on) feedback?.preview(sound)
            })
        }
        SectionHeader(stringResource(Res.string.sounds_preview))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                UiSound.FOCUS to Res.string.sound_focus, UiSound.CONFIRM to Res.string.sound_confirm,
                UiSound.BACK to Res.string.sound_back, UiSound.LAUNCH to Res.string.sound_launch, UiSound.ERROR to Res.string.sound_error,
            ).forEach { (sound, label) ->
                LauncherButton(stringResource(label), onClick = { feedback?.preview(sound) }, icon = Icons.Filled.PlayArrow)
            }
        }
        SectionHeader(stringResource(Res.string.haptics_section))
        SwitchRow(stringResource(Res.string.setting_haptics), settings.haptics, { v -> update { it.copy(haptics = v) } })
        ChoiceRow(
            stringResource(Res.string.setting_haptic_strength), HapticStrength.entries.map { it.name }, settings.hapticStrength,
            label = {
                stringResource(
                    when (it) {
                        "LIGHT" -> Res.string.haptic_light
                        "STRONG" -> Res.string.haptic_strong
                        else -> Res.string.haptic_medium
                    },
                )
            },
            onSelect = { v ->
                update { it.copy(hapticStrength = v) }
            },
        )
        SwitchRow(stringResource(Res.string.setting_haptic_focus), settings.hapticOnFocus, { v -> update { it.copy(hapticOnFocus = v) } })
    }
}
