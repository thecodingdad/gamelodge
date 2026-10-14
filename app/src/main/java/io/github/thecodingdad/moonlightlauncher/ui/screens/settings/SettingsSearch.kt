package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Where a search hit leads: a settings page or the controllers screen. */
sealed interface SettingsTarget {
    data class Page(val id: SettingsPageId) : SettingsTarget
    data object Controllers : SettingsTarget
}

/** One searchable setting: its title, the page it is on and (optionally) the section there. */
private class SearchEntry(@StringRes val title: Int, val target: SettingsTarget, @StringRes val section: Int? = null)

private fun page(id: SettingsPageId, vararg titles: Int, section: Int? = null) =
    titles.map { SearchEntry(it, SettingsTarget.Page(id), section) }

private val pageTitles: List<Pair<SettingsTarget, Int>> = listOf(
    SettingsTarget.Page(SettingsPageId.HOME_SCREEN) to R.string.settings_home_screen,
    SettingsTarget.Page(SettingsPageId.THEME) to R.string.settings_theme,
    SettingsTarget.Page(SettingsPageId.DISPLAY) to R.string.settings_display,
    SettingsTarget.Page(SettingsPageId.FILTERS) to R.string.settings_filters,
    SettingsTarget.Page(SettingsPageId.METADATA) to R.string.settings_metadata,
    SettingsTarget.Page(SettingsPageId.USAGE) to R.string.settings_usage,
    SettingsTarget.Page(SettingsPageId.EMULATORS) to R.string.settings_emulators,
    SettingsTarget.Page(SettingsPageId.HOSTS) to R.string.settings_hosts,
    SettingsTarget.Page(SettingsPageId.PROFILES) to R.string.settings_profiles,
    SettingsTarget.Page(SettingsPageId.VPN) to R.string.settings_vpn,
    SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT) to R.string.settings_home_assistant,
    SettingsTarget.Page(SettingsPageId.CONTROLS) to R.string.settings_controls,
    SettingsTarget.Controllers to R.string.settings_controllers,
    SettingsTarget.Page(SettingsPageId.QUICK_MENU) to R.string.settings_quick_menu,
    SettingsTarget.Page(SettingsPageId.SOUNDS) to R.string.settings_sounds,
    SettingsTarget.Page(SettingsPageId.LAUNCHER) to R.string.settings_launcher,
    SettingsTarget.Page(SettingsPageId.LANGUAGE) to R.string.settings_language,
    SettingsTarget.Page(SettingsPageId.BACKUP) to R.string.settings_backup,
    SettingsTarget.Page(SettingsPageId.ABOUT) to R.string.settings_about,
    SettingsTarget.Page(SettingsPageId.LICENSES) to R.string.about_open_source,
)

/** Every visible setting by page (dialog-internal and per-item settings are left out). */
private val settingsIndex: List<SearchEntry> = buildList {
    // The pages themselves.
    pageTitles.forEach { (target, title) -> add(SearchEntry(title, target)) }
    addAll(page(SettingsPageId.HOME_SCREEN, R.string.setting_start_tab, R.string.home_rows_section, R.string.home_row_ha))
    addAll(page(SettingsPageId.THEME, R.string.setting_theme, R.string.setting_accent, R.string.setting_accent_color))
    addAll(page(
        SettingsPageId.DISPLAY, R.string.setting_card_size, R.string.setting_animated_artwork, R.string.setting_reduce_motion,
        R.string.setting_ui_scale, R.string.setting_hide_nav_bar, R.string.setting_screensaver,
    ))
    addAll(page(
        SettingsPageId.DISPLAY, R.string.tv_section, R.string.setting_tv_launcher, R.string.setting_tv_resolution,
        R.string.setting_tv_scale_auto, R.string.setting_tv_scale, section = R.string.tv_section,
    ))
    addAll(page(SettingsPageId.METADATA, R.string.metadata_auto, R.string.metadata_review_list))
    addAll(page(SettingsPageId.USAGE, R.string.usage_access, R.string.stats_title))
    addAll(page(
        SettingsPageId.EMULATORS, R.string.emu_folders, R.string.emu_folder_add, R.string.emu_all_files, R.string.emu_emulators, R.string.emu_custom,
    ))
    addAll(page(
        SettingsPageId.VPN, R.string.vpn_tunnels, R.string.vpn_default_tunnel, R.string.vpn_auto, R.string.vpn_auto_unreachable,
        R.string.vpn_auto_outside, R.string.vpn_disconnect_home,
    ))
    addAll(page(SettingsPageId.HOME_ASSISTANT, R.string.ha_tiles, R.string.ha_show_home_row))
    addAll(page(SettingsPageId.CONTROLS, R.string.setting_button_layout, R.string.setting_hint_bar, R.string.setting_games_direct_start))
    addAll(page(
        SettingsPageId.CONTROLS, R.string.combo_section, R.string.combo_home, R.string.combo_quick_menu, R.string.combo_service,
        section = R.string.combo_section,
    ))
    listOf(R.string.controllers_connected, R.string.controllers_stream_settings, R.string.odin_mapping_section)
        .forEach { add(SearchEntry(it, SettingsTarget.Controllers)) }
    addAll(page(SettingsPageId.QUICK_MENU, R.string.qt_add))
    addAll(page(
        SettingsPageId.QUICK_MENU, R.string.qm_touch_section, R.string.qm_edge_handle, R.string.qm_tile_add,
        section = R.string.qm_touch_section,
    ))
    addAll(page(
        SettingsPageId.SOUNDS, R.string.setting_ui_sounds, R.string.setting_ui_volume, R.string.setting_sound_style,
        R.string.setting_sound_pitch, R.string.sounds_events, R.string.setting_haptics, R.string.setting_haptic_strength,
        R.string.setting_haptic_focus,
    ))
    addAll(page(SettingsPageId.LAUNCHER, R.string.launcher_enable, R.string.launcher_system_settings))
    addAll(page(SettingsPageId.ABOUT, R.string.about_license, R.string.about_source, R.string.about_data_sources))
    addAll(page(SettingsPageId.BACKUP, R.string.backup_folder, R.string.backup_auto, R.string.backup_keep, R.string.backup_with_credentials))
}

/**
 * Search hits for [query]: settings whose title, page or section contains it (case and accents
 * ignored). Pages come first, then their settings, in index order.
 */
@Composable
fun SettingsSearchResults(query: String, onOpen: (SettingsTarget) -> Unit) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val q = TitleNormalizer.normalize(query)
    fun pageTitle(target: SettingsTarget) = pageTitles.first { it.first == target }.second
    val hits = settingsIndex.filter { e ->
        val haystack = listOfNotNull(e.title, pageTitle(e.target), e.section).joinToString(" ") { resources.getString(it) }
        TitleNormalizer.normalize(haystack).contains(q)
    }.distinctBy { it.title to it.target }
    if (hits.isEmpty()) {
        Text(resources.getString(R.string.settings_search_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        return
    }
    hits.forEach { e ->
        val isPage = pageTitles.any { it.first == e.target && it.second == e.title }
        val path = if (isPage) null else listOfNotNull(pageTitle(e.target), e.section?.takeIf { it != e.title })
            .joinToString("  ›  ") { resources.getString(it) }
        NavRow(
            resources.getString(e.title), { onOpen(e.target) },
            subtitle = path, icon = if (isPage) null else Icons.Filled.Search,
        )
    }
}
