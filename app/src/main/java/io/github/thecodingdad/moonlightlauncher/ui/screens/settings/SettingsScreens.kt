package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.res.vectorResource
import io.github.thecodingdad.moonlightlauncher.BuildConfig
import io.github.thecodingdad.moonlightlauncher.R
import kotlin.math.roundToInt
import io.github.thecodingdad.moonlightlauncher.data.settings.AccentMode
import io.github.thecodingdad.moonlightlauncher.data.settings.ButtonLayout
import io.github.thecodingdad.moonlightlauncher.data.settings.CardSize
import io.github.thecodingdad.moonlightlauncher.data.settings.StartTab
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.data.settings.ThemeMode
import io.github.thecodingdad.moonlightlauncher.resources.*

enum class SettingsPageId { HOSTS, PROFILES, HOME_SCREEN, THEME, DISPLAY, FILTERS, APP_FILTERS, QUICK_MENU, CONTROLS, SOUNDS, METADATA, USAGE, EMULATORS, HOME_ASSISTANT, VPN, LAUNCHER, BACKUP, LANGUAGE, ABOUT, LICENSES }

@Composable
fun SettingsHome(open: (SettingsPageId) -> Unit, isFirstVisit: Boolean, openControllers: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val keyboard = io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController.current
    val searchTitle = stringResource(Res.string.settings_search)
    // START: search with the controller keyboard (the field itself works by touch and A too).
    io.github.thecodingdad.moonlightlauncher.ui.input.PadAction(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.START) {
        keyboard.open(searchTitle, query) { query = it }
    }
    SettingsPage(stringResource(Res.string.tab_settings)) {
        io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField(searchTitle, query, { query = it })
        if (query.isNotBlank()) {
            SettingsSearchResults(query) { target ->
                when (target) {
                    is SettingsTarget.Page -> open(target.id)
                    SettingsTarget.Controllers -> openControllers()
                }
            }
            return@SettingsPage
        }
        SectionHeader(stringResource(Res.string.settings_group_display))
        NavRow(stringResource(Res.string.settings_home_screen), { open(SettingsPageId.HOME_SCREEN) }, stringResource(Res.string.settings_home_screen_sub), Icons.Filled.Home, initialFocus = isFirstVisit)
        NavRow(stringResource(Res.string.settings_theme), { open(SettingsPageId.THEME) }, stringResource(Res.string.settings_theme_sub), Icons.Filled.Palette)
        NavRow(stringResource(Res.string.settings_display), { open(SettingsPageId.DISPLAY) }, stringResource(Res.string.settings_display_sub), Icons.Filled.AspectRatio)

        SectionHeader(stringResource(Res.string.settings_group_library))
        NavRow(stringResource(Res.string.settings_filters), { open(SettingsPageId.FILTERS) }, stringResource(Res.string.settings_filters_sub), Icons.Filled.FilterAlt)
        NavRow(stringResource(Res.string.settings_metadata), { open(SettingsPageId.METADATA) }, stringResource(Res.string.settings_metadata_sub), Icons.Filled.Image)
        NavRow(stringResource(Res.string.settings_usage), { open(SettingsPageId.USAGE) }, stringResource(Res.string.settings_usage_sub), Icons.Filled.QueryStats)
        NavRow(stringResource(Res.string.settings_emulators), { open(SettingsPageId.EMULATORS) }, stringResource(Res.string.settings_emulators_sub), Icons.Filled.SportsEsports)

        SectionHeader(stringResource(Res.string.settings_group_streaming))
        NavRow(stringResource(Res.string.settings_hosts), { open(SettingsPageId.HOSTS) }, stringResource(Res.string.settings_hosts_sub), Icons.Filled.Computer)
        NavRow(stringResource(Res.string.settings_profiles), { open(SettingsPageId.PROFILES) }, stringResource(Res.string.settings_profiles_sub), Icons.Filled.Tune)
        NavRow(stringResource(Res.string.settings_vpn), { open(SettingsPageId.VPN) }, stringResource(Res.string.settings_vpn_sub), Icons.Filled.VpnLock)
        NavRow(stringResource(Res.string.settings_home_assistant), { open(SettingsPageId.HOME_ASSISTANT) }, stringResource(Res.string.settings_home_assistant_sub), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_home_assistant))

        SectionHeader(stringResource(Res.string.settings_group_controls))
        NavRow(stringResource(Res.string.settings_controls), { open(SettingsPageId.CONTROLS) }, stringResource(Res.string.settings_controls_sub), Icons.Filled.SportsEsports)
        NavRow(stringResource(Res.string.settings_controllers), openControllers, stringResource(Res.string.settings_controllers_sub), Icons.Filled.Gamepad)
        NavRow(stringResource(Res.string.settings_quick_menu), { open(SettingsPageId.QUICK_MENU) }, stringResource(Res.string.settings_quick_menu_sub), Icons.Filled.Dashboard)
        NavRow(stringResource(Res.string.settings_sounds), { open(SettingsPageId.SOUNDS) }, stringResource(Res.string.settings_sounds_sub), Icons.Filled.VolumeUp)

        SectionHeader(stringResource(Res.string.settings_group_system))
        NavRow(stringResource(Res.string.settings_launcher), { open(SettingsPageId.LAUNCHER) }, stringResource(Res.string.settings_launcher_sub), Icons.Filled.Home)
        NavRow(stringResource(Res.string.settings_language), { open(SettingsPageId.LANGUAGE) }, null, Icons.Filled.Language)
        NavRow(stringResource(Res.string.settings_backup), { open(SettingsPageId.BACKUP) }, stringResource(Res.string.settings_backup_sub), Icons.Filled.Backup)
        NavRow(
            stringResource(Res.string.settings_android), {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
            },
            stringResource(Res.string.settings_android_sub), Icons.Filled.Android,
        )
        NavRow(stringResource(Res.string.settings_about), { open(SettingsPageId.ABOUT) }, null, Icons.Filled.Info)
    }
}

/** What the launcher shows after starting and when idle, and which rows the start tab has. */
@Composable
fun HomeScreenSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    vm: io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel,
    openFilters: () -> Unit,
) {
    val filters by vm.filters.collectAsState()
    SettingsPage(stringResource(Res.string.settings_home_screen)) {
        ChoiceRow(
            stringResource(Res.string.setting_start_tab), StartTab.entries, settings.startTab,
            label = {
                stringResource(
                    when (it) {
                        StartTab.HOME -> Res.string.tab_home
                        StartTab.LIBRARY -> Res.string.tab_library
                        StartTab.APPS -> Res.string.tab_apps
                    },
                )
            },
            onSelect = { v -> update { it.copy(startTab = v) } },
            subtitle = stringResource(Res.string.setting_start_tab_sub),
            initialFocus = true,
        )
        SectionHeader(stringResource(Res.string.home_rows_section))
        Text(stringResource(Res.string.home_rows_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        NavRow(stringResource(Res.string.settings_filters), openFilters, stringResource(Res.string.settings_filters_sub), Icons.Filled.FilterAlt)
        // Game filters first, then app filters (their rows show landscape app tiles).
        val isApps = { f: io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity -> f.scope == io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.APPS }
        val gameFilters = filters.sortedWith(compareBy({ isApps(it) }, { it.sortOrder }))
        val byId = gameFilters.associateBy { it.id }
        val appsPrefix = stringResource(Res.string.tab_apps)
        val HA = io.github.thecodingdad.moonlightlauncher.data.library.HomeRowOrder.HA
        ReorderableList(
            items = io.github.thecodingdad.moonlightlauncher.data.library.HomeRowOrder.ids(gameFilters.map { it.id }, settings.homeRowOrder),
            id = { it },
            onReorder = { ids -> update { it.copy(homeRowOrder = ids) } },
        ) { _, id ->
            if (id == HA) {
                SwitchRow(stringResource(Res.string.home_row_ha), settings.showHaRowOnHome, { v -> update { it.copy(showHaRowOnHome = v) } })
            } else {
                // Same switch as "Show on start tab" in the filter settings.
                byId[id]?.let { f ->
                    val label = if (isApps(f)) "$appsPrefix · ${filterEntityLabel(f)}" else filterEntityLabel(f)
                    SwitchRow(label, f.showOnHome, { vm.saveFilter(f.copy(showOnHome = it)) })
                }
            }
        }
    }
}

@Composable
fun ThemeSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
) {
    SettingsPage(stringResource(Res.string.settings_theme)) {
        ChoiceRow(
            stringResource(Res.string.setting_theme), ThemeMode.entries, settings.themeMode,
            label = {
                stringResource(
                    when (it) {
                        ThemeMode.DARK -> Res.string.theme_dark
                        ThemeMode.BLACK -> Res.string.theme_black
                        ThemeMode.LIGHT -> Res.string.theme_light
                    },
                )
            },
            onSelect = { v -> update { it.copy(themeMode = v) } },
            initialFocus = true,
        )
        ChoiceRow(
            stringResource(Res.string.setting_accent), AccentMode.entries, settings.accentMode,
            label = { stringResource(if (it == AccentMode.COVER) Res.string.accent_cover else Res.string.accent_fixed) },
            onSelect = { v -> update { it.copy(accentMode = v) } },
        )
        ChoiceRow(
            stringResource(Res.string.setting_accent_color), accentPresets, accentPresets.firstOrNull { it == settings.accentColor } ?: accentPresets.first(),
            label = { c -> stringResource(accentNames[accentPresets.indexOf(c)]) },
            onSelect = { v -> update { it.copy(accentColor = v) } },
        )
    }
}

/** Sizes, motion and system bars. */
@Composable
fun DisplaySettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    externalDisplay: io.github.thecodingdad.moonlightlauncher.system.ExternalDisplayInfo? = null,
) {
    SettingsPage(stringResource(Res.string.settings_display)) {
        ChoiceRow(
            stringResource(Res.string.setting_card_size), CardSize.entries, settings.cardSize,
            label = {
                stringResource(
                    when (it) {
                        CardSize.SMALL -> Res.string.size_small
                        CardSize.MEDIUM -> Res.string.size_medium
                        CardSize.LARGE -> Res.string.size_large
                    },
                )
            },
            onSelect = { v -> update { it.copy(cardSize = v) } },
            initialFocus = true,
        )
        // Switched on again: games matched meanwhile get their animated artwork looked up.
        SwitchRow(stringResource(Res.string.setting_animated_artwork), settings.animatedArtwork, { v -> update { it.copy(animatedArtwork = v, animatedBackfillDone = it.animatedBackfillDone && v) } })
        if (settings.animatedArtwork) {
            ChoiceRow(
                stringResource(Res.string.setting_animated_tiles), listOf(false, true), settings.animatedTilesAlways,
                label = { stringResource(if (it) Res.string.setting_animated_tiles_always else Res.string.setting_animated_tiles_focus) },
                onSelect = { v -> update { it.copy(animatedTilesAlways = v) } },
            )
            SwitchRow(
                stringResource(Res.string.setting_animated_mobile), settings.animatedOnMobileData, { v -> update { it.copy(animatedOnMobileData = v) } },
                subtitle = stringResource(Res.string.setting_animated_mobile_sub),
            )
        }
        SwitchRow(stringResource(Res.string.setting_reduce_motion), settings.reduceMotion, { v -> update { it.copy(reduceMotion = v) } })
        SliderRow(
            stringResource(Res.string.setting_ui_scale), settings.uiScale, 60..130, 5,
            { v -> update { it.copy(uiScale = v) } }, { "$it %" },
        )
        SwitchRow(
            stringResource(Res.string.setting_hide_nav_bar), settings.hideNavigationBar, { v -> update { it.copy(hideNavigationBar = v) } },
            subtitle = stringResource(Res.string.setting_hide_nav_bar_sub),
        )
        ChoiceRow(
            stringResource(Res.string.setting_screensaver), listOf(0, 2, 5, 10, 20), settings.screensaverMinutes,
            label = { if (it == 0) stringResource(Res.string.off) else stringResource(Res.string.minutes_short, it) },
            onSelect = { v -> update { it.copy(screensaverMinutes = v) } },
        )
        SectionHeader(stringResource(Res.string.tv_section))
        InfoRow(
            stringResource(Res.string.tv_detected),
            externalDisplay?.let { "${it.name} (${it.width}×${it.height})" } ?: stringResource(Res.string.tv_none),
        )
        SwitchRow(
            stringResource(Res.string.setting_tv_launcher), settings.tvLauncher, { v -> update { it.copy(tvLauncher = v) } },
            subtitle = stringResource(Res.string.setting_tv_launcher_sub),
        )
        if (settings.tvLauncher) {
            val modes = externalDisplay?.modes.orEmpty()
            ChoiceRow(
                stringResource(Res.string.setting_tv_resolution), listOf(0) + modes.map { it.id }.filter { it != 0 },
                settings.tvModeId.takeIf { id -> id == 0 || modes.any { it.id == id } } ?: 0,
                label = { id ->
                    modes.firstOrNull { it.id == id }?.let { "${it.width}×${it.height} @ ${it.refreshRate.roundToInt()} Hz" }
                        ?: stringResource(Res.string.setting_tv_resolution_auto)
                },
                onSelect = { v -> update { it.copy(tvModeId = v) } },
                subtitle = stringResource(if (externalDisplay == null) Res.string.setting_tv_resolution_none else Res.string.setting_tv_resolution_sub),
            )
        }
        SwitchRow(
            stringResource(Res.string.setting_tv_scale_auto), settings.tvScaleAuto, { v -> update { it.copy(tvScaleAuto = v) } },
            subtitle = stringResource(Res.string.setting_tv_scale_auto_sub),
        )
        SliderRow(
            stringResource(Res.string.setting_tv_scale), settings.tvScale, 40..100, 5,
            { v -> update { it.copy(tvScale = v) } }, { "$it %" },
        )
    }
}

private val accentPresets = listOf(0xFF7C9CFF, 0xFF4CD787, 0xFFFF7A59, 0xFFFF5C8A, 0xFFB58CFF, 0xFFFFC14D, 0xFF3DD6D0).map { it.toInt() }
private val accentNames = listOf(Res.string.color_blue, Res.string.color_green, Res.string.color_orange, Res.string.color_pink, Res.string.color_purple, Res.string.color_yellow, Res.string.color_teal)

@Composable
fun ControlsSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(Res.string.settings_controls)) {
        ChoiceRow(
            stringResource(Res.string.setting_button_layout), ButtonLayout.entries, settings.buttonLayout,
            label = { stringResource(if (it == ButtonLayout.XBOX) Res.string.button_layout_xbox else Res.string.button_layout_nintendo) },
            onSelect = { v -> update { it.copy(buttonLayout = v) } },
            subtitle = stringResource(Res.string.setting_button_layout_sub),
            initialFocus = true,
        )
        SwitchRow(
            stringResource(Res.string.setting_hint_bar), settings.showHintBar, { v -> update { it.copy(showHintBar = v) } },
            subtitle = stringResource(Res.string.setting_hint_bar_sub),
        )
        SwitchRow(
            stringResource(Res.string.setting_games_direct_start), settings.gamesDirectStart, { v -> update { it.copy(gamesDirectStart = v) } },
            subtitle = stringResource(Res.string.setting_games_direct_start_sub),
        )
        ButtonComboRows(settings, update)
    }
}

@Composable
fun LanguageSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsPage(stringResource(Res.string.settings_language)) {
        ChoiceRow(
            stringResource(Res.string.settings_language), listOf("system", "en", "de"), settings.language,
            label = {
                when (it) {
                    "en" -> "English"
                    "de" -> "Deutsch"
                    else -> stringResource(Res.string.language_system)
                }
            },
            onSelect = { v ->
                update { it.copy(language = v) }
                io.github.thecodingdad.moonlightlauncher.util.AppLanguage.apply(context, v)
                // Before Android 13 the activity takes the language when it starts again.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) (context as? android.app.Activity)?.recreate()
            },
            initialFocus = true,
        )
    }
}

