package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import io.github.thecodingdad.moonlightlauncher.BuildConfig
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.settings.AccentMode
import io.github.thecodingdad.moonlightlauncher.data.settings.ButtonLayout
import io.github.thecodingdad.moonlightlauncher.data.settings.CardSize
import io.github.thecodingdad.moonlightlauncher.data.settings.HomeLayout
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.data.settings.ThemeMode

enum class SettingsPageId { HOSTS, PROFILES, APPEARANCE, FILTERS, CONTROLS, SOUNDS, METADATA, HOME_ASSISTANT, VPN, LAUNCHER, BACKUP, LANGUAGE, ABOUT }

@Composable
fun SettingsHome(open: (SettingsPageId) -> Unit, isFirstVisit: Boolean) {
    SettingsPage(stringResource(R.string.tab_settings)) {
        NavRow(stringResource(R.string.settings_hosts), { open(SettingsPageId.HOSTS) }, stringResource(R.string.settings_hosts_sub), Icons.Filled.Computer, initialFocus = isFirstVisit)
        NavRow(stringResource(R.string.settings_profiles), { open(SettingsPageId.PROFILES) }, stringResource(R.string.settings_profiles_sub), Icons.Filled.Tune)
        NavRow(stringResource(R.string.settings_appearance), { open(SettingsPageId.APPEARANCE) }, stringResource(R.string.settings_appearance_sub), Icons.Filled.Palette)
        NavRow(stringResource(R.string.settings_filters), { open(SettingsPageId.FILTERS) }, stringResource(R.string.settings_filters_sub), Icons.Filled.FilterAlt)
        NavRow(stringResource(R.string.settings_sounds), { open(SettingsPageId.SOUNDS) }, stringResource(R.string.settings_sounds_sub), Icons.Filled.VolumeUp)
        NavRow(stringResource(R.string.settings_controls), { open(SettingsPageId.CONTROLS) }, stringResource(R.string.settings_controls_sub), Icons.Filled.SportsEsports)
        NavRow(stringResource(R.string.settings_metadata), { open(SettingsPageId.METADATA) }, stringResource(R.string.settings_metadata_sub), Icons.Filled.Image)
        NavRow(stringResource(R.string.settings_home_assistant), { open(SettingsPageId.HOME_ASSISTANT) }, stringResource(R.string.settings_home_assistant_sub), Icons.Filled.Widgets)
        NavRow(stringResource(R.string.settings_vpn), { open(SettingsPageId.VPN) }, stringResource(R.string.settings_vpn_sub), Icons.Filled.VpnLock)
        NavRow(stringResource(R.string.settings_launcher), { open(SettingsPageId.LAUNCHER) }, stringResource(R.string.settings_launcher_sub), Icons.Filled.Home)
        NavRow(stringResource(R.string.settings_backup), { open(SettingsPageId.BACKUP) }, stringResource(R.string.settings_backup_sub), Icons.Filled.Backup)
        NavRow(stringResource(R.string.settings_language), { open(SettingsPageId.LANGUAGE) }, null, Icons.Filled.Language)
        NavRow(stringResource(R.string.settings_about), { open(SettingsPageId.ABOUT) }, null, Icons.Filled.Info)
    }
}

@Composable
fun AppearanceSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(R.string.settings_appearance)) {
        ChoiceRow(
            stringResource(R.string.setting_home_layout), HomeLayout.entries, settings.homeLayout,
            label = {
                stringResource(
                    when (it) {
                        HomeLayout.HERO -> R.string.layout_hero
                        HomeLayout.GRID -> R.string.layout_grid
                        HomeLayout.CAROUSEL -> R.string.layout_carousel
                    },
                )
            },
            onSelect = { v -> update { it.copy(homeLayout = v) } },
            initialFocus = true,
        )
        ChoiceRow(
            stringResource(R.string.setting_theme), ThemeMode.entries, settings.themeMode,
            label = {
                stringResource(
                    when (it) {
                        ThemeMode.DARK -> R.string.theme_dark
                        ThemeMode.BLACK -> R.string.theme_black
                        ThemeMode.LIGHT -> R.string.theme_light
                    },
                )
            },
            onSelect = { v -> update { it.copy(themeMode = v) } },
        )
        ChoiceRow(
            stringResource(R.string.setting_accent), AccentMode.entries, settings.accentMode,
            label = { stringResource(if (it == AccentMode.COVER) R.string.accent_cover else R.string.accent_fixed) },
            onSelect = { v -> update { it.copy(accentMode = v) } },
        )
        ChoiceRow(
            stringResource(R.string.setting_accent_color), accentPresets, accentPresets.firstOrNull { it == settings.accentColor } ?: accentPresets.first(),
            label = { c -> stringResource(accentNames[accentPresets.indexOf(c)]) },
            onSelect = { v -> update { it.copy(accentColor = v) } },
        )
        ChoiceRow(
            stringResource(R.string.setting_card_size), CardSize.entries, settings.cardSize,
            label = {
                stringResource(
                    when (it) {
                        CardSize.SMALL -> R.string.size_small
                        CardSize.MEDIUM -> R.string.size_medium
                        CardSize.LARGE -> R.string.size_large
                    },
                )
            },
            onSelect = { v -> update { it.copy(cardSize = v) } },
        )
        SwitchRow(stringResource(R.string.setting_animated_artwork), settings.animatedArtwork, { v -> update { it.copy(animatedArtwork = v) } })
        SwitchRow(stringResource(R.string.setting_reduce_motion), settings.reduceMotion, { v -> update { it.copy(reduceMotion = v) } })
        ChoiceRow(
            stringResource(R.string.setting_screensaver), listOf(0, 2, 5, 10, 20), settings.screensaverMinutes,
            label = { if (it == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, it) },
            onSelect = { v -> update { it.copy(screensaverMinutes = v) } },
        )
    }
}

private val accentPresets = listOf(0xFF7C9CFF, 0xFF4CD787, 0xFFFF7A59, 0xFFFF5C8A, 0xFFB58CFF, 0xFFFFC14D, 0xFF3DD6D0).map { it.toInt() }
private val accentNames = listOf(R.string.color_blue, R.string.color_green, R.string.color_orange, R.string.color_pink, R.string.color_purple, R.string.color_yellow, R.string.color_teal)

@Composable
fun ControlsSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, openControllers: () -> Unit) {
    SettingsPage(stringResource(R.string.settings_controls)) {
        ChoiceRow(
            stringResource(R.string.setting_button_layout), ButtonLayout.entries, settings.buttonLayout,
            label = { stringResource(if (it == ButtonLayout.XBOX) R.string.button_layout_xbox else R.string.button_layout_nintendo) },
            onSelect = { v -> update { it.copy(buttonLayout = v) } },
            subtitle = stringResource(R.string.setting_button_layout_sub),
            initialFocus = true,
        )
        NavRow(stringResource(R.string.settings_controllers), openControllers, stringResource(R.string.settings_controllers_sub))
    }
}

@Composable
fun LanguageSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(R.string.settings_language)) {
        ChoiceRow(
            stringResource(R.string.settings_language), listOf("system", "en", "de"), settings.language,
            label = {
                when (it) {
                    "en" -> "English"
                    "de" -> "Deutsch"
                    else -> stringResource(R.string.language_system)
                }
            },
            onSelect = { v ->
                update { it.copy(language = v) }
                AppCompatDelegate.setApplicationLocales(
                    if (v == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v),
                )
            },
            initialFocus = true,
        )
    }
}

@Composable
fun AboutSettings() {
    SettingsPage(stringResource(R.string.settings_about)) {
        InfoRow(stringResource(R.string.launcher_name), BuildConfig.VERSION_NAME)
        InfoRow(stringResource(R.string.about_streaming), "Artemis / Moonlight")
        InfoRow(stringResource(R.string.about_license), "GPLv3")
        InfoRow(stringResource(R.string.about_source), "github.com/thecodingdad/moonlight-launcher")
    }
}
