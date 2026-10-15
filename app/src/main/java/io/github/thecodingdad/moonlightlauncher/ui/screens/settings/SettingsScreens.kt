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
import androidx.compose.ui.res.stringResource
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

enum class SettingsPageId { HOSTS, PROFILES, HOME_SCREEN, THEME, DISPLAY, FILTERS, APP_FILTERS, QUICK_MENU, CONTROLS, SOUNDS, METADATA, USAGE, EMULATORS, HOME_ASSISTANT, VPN, LAUNCHER, BACKUP, LANGUAGE, ABOUT, LICENSES }

@Composable
fun SettingsHome(open: (SettingsPageId) -> Unit, isFirstVisit: Boolean, openControllers: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val keyboard = io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController.current
    val searchTitle = stringResource(R.string.settings_search)
    // START: search with the controller keyboard (the field itself works by touch and A too).
    io.github.thecodingdad.moonlightlauncher.ui.input.PadAction(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.START) {
        keyboard.open(searchTitle, query) { query = it }
    }
    SettingsPage(stringResource(R.string.tab_settings)) {
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
        SectionHeader(stringResource(R.string.settings_group_display))
        NavRow(stringResource(R.string.settings_home_screen), { open(SettingsPageId.HOME_SCREEN) }, stringResource(R.string.settings_home_screen_sub), Icons.Filled.Home, initialFocus = isFirstVisit)
        NavRow(stringResource(R.string.settings_theme), { open(SettingsPageId.THEME) }, stringResource(R.string.settings_theme_sub), Icons.Filled.Palette)
        NavRow(stringResource(R.string.settings_display), { open(SettingsPageId.DISPLAY) }, stringResource(R.string.settings_display_sub), Icons.Filled.AspectRatio)

        SectionHeader(stringResource(R.string.settings_group_library))
        NavRow(stringResource(R.string.settings_filters), { open(SettingsPageId.FILTERS) }, stringResource(R.string.settings_filters_sub), Icons.Filled.FilterAlt)
        NavRow(stringResource(R.string.settings_metadata), { open(SettingsPageId.METADATA) }, stringResource(R.string.settings_metadata_sub), Icons.Filled.Image)
        NavRow(stringResource(R.string.settings_usage), { open(SettingsPageId.USAGE) }, stringResource(R.string.settings_usage_sub), Icons.Filled.QueryStats)
        NavRow(stringResource(R.string.settings_emulators), { open(SettingsPageId.EMULATORS) }, stringResource(R.string.settings_emulators_sub), Icons.Filled.SportsEsports)

        SectionHeader(stringResource(R.string.settings_group_streaming))
        NavRow(stringResource(R.string.settings_hosts), { open(SettingsPageId.HOSTS) }, stringResource(R.string.settings_hosts_sub), Icons.Filled.Computer)
        NavRow(stringResource(R.string.settings_profiles), { open(SettingsPageId.PROFILES) }, stringResource(R.string.settings_profiles_sub), Icons.Filled.Tune)
        NavRow(stringResource(R.string.settings_vpn), { open(SettingsPageId.VPN) }, stringResource(R.string.settings_vpn_sub), Icons.Filled.VpnLock)
        NavRow(stringResource(R.string.settings_home_assistant), { open(SettingsPageId.HOME_ASSISTANT) }, stringResource(R.string.settings_home_assistant_sub), androidx.compose.ui.graphics.vector.ImageVector.vectorResource(R.drawable.ic_home_assistant))

        SectionHeader(stringResource(R.string.settings_group_controls))
        NavRow(stringResource(R.string.settings_controls), { open(SettingsPageId.CONTROLS) }, stringResource(R.string.settings_controls_sub), Icons.Filled.SportsEsports)
        NavRow(stringResource(R.string.settings_controllers), openControllers, stringResource(R.string.settings_controllers_sub), Icons.Filled.Gamepad)
        NavRow(stringResource(R.string.settings_quick_menu), { open(SettingsPageId.QUICK_MENU) }, stringResource(R.string.settings_quick_menu_sub), Icons.Filled.Dashboard)
        NavRow(stringResource(R.string.settings_sounds), { open(SettingsPageId.SOUNDS) }, stringResource(R.string.settings_sounds_sub), Icons.Filled.VolumeUp)

        SectionHeader(stringResource(R.string.settings_group_system))
        NavRow(stringResource(R.string.settings_launcher), { open(SettingsPageId.LAUNCHER) }, stringResource(R.string.settings_launcher_sub), Icons.Filled.Home)
        NavRow(stringResource(R.string.settings_language), { open(SettingsPageId.LANGUAGE) }, null, Icons.Filled.Language)
        NavRow(stringResource(R.string.settings_backup), { open(SettingsPageId.BACKUP) }, stringResource(R.string.settings_backup_sub), Icons.Filled.Backup)
        NavRow(
            stringResource(R.string.settings_android), {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
            },
            stringResource(R.string.settings_android_sub), Icons.Filled.Android,
        )
        NavRow(stringResource(R.string.settings_about), { open(SettingsPageId.ABOUT) }, null, Icons.Filled.Info)
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
    SettingsPage(stringResource(R.string.settings_home_screen)) {
        ChoiceRow(
            stringResource(R.string.setting_start_tab), StartTab.entries, settings.startTab,
            label = {
                stringResource(
                    when (it) {
                        StartTab.HOME -> R.string.tab_home
                        StartTab.LIBRARY -> R.string.tab_library
                        StartTab.APPS -> R.string.tab_apps
                    },
                )
            },
            onSelect = { v -> update { it.copy(startTab = v) } },
            subtitle = stringResource(R.string.setting_start_tab_sub),
            initialFocus = true,
        )
        SectionHeader(stringResource(R.string.home_rows_section))
        Text(stringResource(R.string.home_rows_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        NavRow(stringResource(R.string.settings_filters), openFilters, stringResource(R.string.settings_filters_sub), Icons.Filled.FilterAlt)
        // Game filters first, then app filters (their rows show landscape app tiles).
        val isApps = { f: io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity -> f.scope == io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.APPS }
        val gameFilters = filters.sortedWith(compareBy({ isApps(it) }, { it.sortOrder }))
        val byId = gameFilters.associateBy { it.id }
        val appsPrefix = stringResource(R.string.tab_apps)
        val HA = io.github.thecodingdad.moonlightlauncher.data.library.HomeRowOrder.HA
        ReorderableList(
            items = io.github.thecodingdad.moonlightlauncher.data.library.HomeRowOrder.ids(gameFilters.map { it.id }, settings.homeRowOrder),
            id = { it },
            onReorder = { ids -> update { it.copy(homeRowOrder = ids) } },
        ) { _, id ->
            if (id == HA) {
                SwitchRow(stringResource(R.string.home_row_ha), settings.showHaRowOnHome, { v -> update { it.copy(showHaRowOnHome = v) } })
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
    SettingsPage(stringResource(R.string.settings_theme)) {
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
            initialFocus = true,
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
    }
}

/** Sizes, motion and system bars. */
@Composable
fun DisplaySettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    externalDisplay: io.github.thecodingdad.moonlightlauncher.system.ExternalDisplayInfo? = null,
) {
    SettingsPage(stringResource(R.string.settings_display)) {
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
            initialFocus = true,
        )
        // Switched on again: games matched meanwhile get their animated artwork looked up.
        SwitchRow(stringResource(R.string.setting_animated_artwork), settings.animatedArtwork, { v -> update { it.copy(animatedArtwork = v, animatedBackfillDone = it.animatedBackfillDone && v) } })
        if (settings.animatedArtwork) {
            ChoiceRow(
                stringResource(R.string.setting_animated_tiles), listOf(false, true), settings.animatedTilesAlways,
                label = { stringResource(if (it) R.string.setting_animated_tiles_always else R.string.setting_animated_tiles_focus) },
                onSelect = { v -> update { it.copy(animatedTilesAlways = v) } },
            )
            SwitchRow(
                stringResource(R.string.setting_animated_mobile), settings.animatedOnMobileData, { v -> update { it.copy(animatedOnMobileData = v) } },
                subtitle = stringResource(R.string.setting_animated_mobile_sub),
            )
        }
        SwitchRow(stringResource(R.string.setting_reduce_motion), settings.reduceMotion, { v -> update { it.copy(reduceMotion = v) } })
        SliderRow(
            stringResource(R.string.setting_ui_scale), settings.uiScale, 60..130, 5,
            { v -> update { it.copy(uiScale = v) } }, { "$it %" },
        )
        SwitchRow(
            stringResource(R.string.setting_hide_nav_bar), settings.hideNavigationBar, { v -> update { it.copy(hideNavigationBar = v) } },
            subtitle = stringResource(R.string.setting_hide_nav_bar_sub),
        )
        ChoiceRow(
            stringResource(R.string.setting_screensaver), listOf(0, 2, 5, 10, 20), settings.screensaverMinutes,
            label = { if (it == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, it) },
            onSelect = { v -> update { it.copy(screensaverMinutes = v) } },
        )
        SectionHeader(stringResource(R.string.tv_section))
        InfoRow(
            stringResource(R.string.tv_detected),
            externalDisplay?.let { "${it.name} (${it.width}×${it.height})" } ?: stringResource(R.string.tv_none),
        )
        SwitchRow(
            stringResource(R.string.setting_tv_launcher), settings.tvLauncher, { v -> update { it.copy(tvLauncher = v) } },
            subtitle = stringResource(R.string.setting_tv_launcher_sub),
        )
        if (settings.tvLauncher) {
            val modes = externalDisplay?.modes.orEmpty()
            ChoiceRow(
                stringResource(R.string.setting_tv_resolution), listOf(0) + modes.map { it.id }.filter { it != 0 },
                settings.tvModeId.takeIf { id -> id == 0 || modes.any { it.id == id } } ?: 0,
                label = { id ->
                    modes.firstOrNull { it.id == id }?.let { "${it.width}×${it.height} @ ${it.refreshRate.roundToInt()} Hz" }
                        ?: stringResource(R.string.setting_tv_resolution_auto)
                },
                onSelect = { v -> update { it.copy(tvModeId = v) } },
                subtitle = stringResource(if (externalDisplay == null) R.string.setting_tv_resolution_none else R.string.setting_tv_resolution_sub),
            )
        }
        SwitchRow(
            stringResource(R.string.setting_tv_scale_auto), settings.tvScaleAuto, { v -> update { it.copy(tvScaleAuto = v) } },
            subtitle = stringResource(R.string.setting_tv_scale_auto_sub),
        )
        SliderRow(
            stringResource(R.string.setting_tv_scale), settings.tvScale, 40..100, 5,
            { v -> update { it.copy(tvScale = v) } }, { "$it %" },
        )
    }
}

private val accentPresets = listOf(0xFF7C9CFF, 0xFF4CD787, 0xFFFF7A59, 0xFFFF5C8A, 0xFFB58CFF, 0xFFFFC14D, 0xFF3DD6D0).map { it.toInt() }
private val accentNames = listOf(R.string.color_blue, R.string.color_green, R.string.color_orange, R.string.color_pink, R.string.color_purple, R.string.color_yellow, R.string.color_teal)

@Composable
fun ControlsSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(R.string.settings_controls)) {
        ChoiceRow(
            stringResource(R.string.setting_button_layout), ButtonLayout.entries, settings.buttonLayout,
            label = { stringResource(if (it == ButtonLayout.XBOX) R.string.button_layout_xbox else R.string.button_layout_nintendo) },
            onSelect = { v -> update { it.copy(buttonLayout = v) } },
            subtitle = stringResource(R.string.setting_button_layout_sub),
            initialFocus = true,
        )
        SwitchRow(
            stringResource(R.string.setting_hint_bar), settings.showHintBar, { v -> update { it.copy(showHintBar = v) } },
            subtitle = stringResource(R.string.setting_hint_bar_sub),
        )
        SwitchRow(
            stringResource(R.string.setting_games_direct_start), settings.gamesDirectStart, { v -> update { it.copy(gamesDirectStart = v) } },
            subtitle = stringResource(R.string.setting_games_direct_start_sub),
        )
        ButtonComboRows(settings, update)
    }
}

@Composable
fun LanguageSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                io.github.thecodingdad.moonlightlauncher.util.AppLanguage.apply(context, v)
                // Before Android 13 the activity takes the language when it starts again.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) (context as? android.app.Activity)?.recreate()
            },
            initialFocus = true,
        )
    }
}

