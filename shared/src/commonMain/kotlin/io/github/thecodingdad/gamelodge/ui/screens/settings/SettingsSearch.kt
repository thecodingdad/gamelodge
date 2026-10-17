package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.thecodingdad.gamelodge.data.library.TitleNormalizer
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

/** Where a search hit leads: a settings page or the controllers screen. */
sealed interface SettingsTarget {
    data class Page(val id: SettingsPageId) : SettingsTarget
    data object Controllers : SettingsTarget
}

/** One searchable setting: its title, the page it is on and (optionally) the section there. */
private class SearchEntry(val title: StringResource, val target: SettingsTarget, val section: StringResource? = null, val combos: Boolean = false)

private fun page(id: SettingsPageId, vararg titles: StringResource, section: StringResource? = null) =
    titles.map { SearchEntry(it, SettingsTarget.Page(id), section) }

private val pageTitles: List<Pair<SettingsTarget, StringResource>> = listOf(
    SettingsTarget.Page(SettingsPageId.HOME_SCREEN) to Res.string.settings_home_screen,
    SettingsTarget.Page(SettingsPageId.THEME) to Res.string.settings_theme,
    SettingsTarget.Page(SettingsPageId.DISPLAY) to Res.string.settings_display,
    SettingsTarget.Page(SettingsPageId.FILTERS) to Res.string.settings_filters,
    SettingsTarget.Page(SettingsPageId.METADATA) to Res.string.settings_metadata,
    SettingsTarget.Page(SettingsPageId.USAGE) to Res.string.settings_usage,
    SettingsTarget.Page(SettingsPageId.EMULATORS) to Res.string.settings_emulators,
    SettingsTarget.Page(SettingsPageId.GAME_SOURCES) to Res.string.settings_game_sources,
    SettingsTarget.Page(SettingsPageId.HOST_ROLE) to Res.string.settings_host_role,
    SettingsTarget.Page(SettingsPageId.STARTUP) to Res.string.settings_startup,
    SettingsTarget.Page(SettingsPageId.HOSTS) to Res.string.settings_hosts,
    SettingsTarget.Page(SettingsPageId.PROFILES) to Res.string.settings_profiles,
    SettingsTarget.Page(SettingsPageId.VPN) to Res.string.settings_vpn,
    SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT) to Res.string.settings_home_assistant,
    SettingsTarget.Page(SettingsPageId.CONTROLS) to Res.string.settings_controls,
    SettingsTarget.Controllers to Res.string.settings_controllers,
    SettingsTarget.Page(SettingsPageId.QUICK_MENU) to Res.string.settings_quick_menu,
    SettingsTarget.Page(SettingsPageId.SOUNDS) to Res.string.settings_sounds,
    SettingsTarget.Page(SettingsPageId.LAUNCHER) to Res.string.settings_launcher,
    SettingsTarget.Page(SettingsPageId.LANGUAGE) to Res.string.settings_language,
    SettingsTarget.Page(SettingsPageId.DATA) to Res.string.settings_data,
    SettingsTarget.Page(SettingsPageId.ABOUT) to Res.string.settings_about,
    SettingsTarget.Page(SettingsPageId.LICENSES) to Res.string.about_open_source,
)

/** Pages only some platforms have (shown in the search only where they exist). */
private val platformPages = setOf(
    SettingsPageId.USAGE, SettingsPageId.EMULATORS, SettingsPageId.PROFILES, SettingsPageId.VPN, SettingsPageId.QUICK_MENU,
    SettingsPageId.LAUNCHER, SettingsPageId.GAME_SOURCES, SettingsPageId.HOST_ROLE, SettingsPageId.STARTUP,
)

/** Every visible setting by page (dialog-internal and per-item settings are left out). */
private val settingsIndex: List<SearchEntry> = buildList {
    // The pages themselves.
    pageTitles.forEach { (target, title) -> add(SearchEntry(title, target)) }
    addAll(page(SettingsPageId.HOME_SCREEN, Res.string.setting_start_tab, Res.string.home_rows_section, Res.string.home_row_ha))
    addAll(page(SettingsPageId.THEME, Res.string.setting_theme, Res.string.setting_accent, Res.string.setting_accent_color))
    addAll(page(
        SettingsPageId.DISPLAY, Res.string.setting_card_size, Res.string.setting_animated_artwork, Res.string.setting_reduce_motion,
        Res.string.setting_ui_scale, Res.string.setting_hide_nav_bar, Res.string.setting_screensaver,
    ))
    addAll(page(
        SettingsPageId.DISPLAY, Res.string.tv_section, Res.string.setting_tv_launcher, Res.string.setting_tv_resolution,
        Res.string.setting_tv_scale_auto, Res.string.setting_tv_scale, section = Res.string.tv_section,
    ))
    addAll(page(SettingsPageId.METADATA, Res.string.metadata_auto, Res.string.metadata_review_list, Res.string.metadata_sources, Res.string.metadata_source_add, Res.string.metadata_source_companion, Res.string.metadata_source_server))
    addAll(page(SettingsPageId.USAGE, Res.string.usage_access, Res.string.stats_title))
    addAll(page(
        SettingsPageId.EMULATORS, Res.string.emu_folders, Res.string.emu_folder_add, Res.string.emu_all_files, Res.string.emu_emulators, Res.string.emu_custom,
    ))
    addAll(page(
        SettingsPageId.VPN, Res.string.vpn_tunnels, Res.string.vpn_default_tunnel, Res.string.vpn_auto, Res.string.vpn_auto_unreachable,
        Res.string.vpn_auto_outside, Res.string.vpn_disconnect_home,
    ))
    addAll(page(SettingsPageId.HOME_ASSISTANT, Res.string.ha_tiles, Res.string.ha_show_home_row))
    addAll(page(SettingsPageId.CONTROLS, Res.string.setting_hint_bar, Res.string.setting_games_direct_start))
    listOf(Res.string.controllers_connected, Res.string.controllers_defaults, Res.string.setting_button_layout, Res.string.odin_mapping_section)
        .forEach { add(SearchEntry(it, SettingsTarget.Controllers)) }
    listOf(Res.string.combo_section, Res.string.combo_home, Res.string.combo_quick_menu, Res.string.combo_service)
        .forEach { add(SearchEntry(it, SettingsTarget.Controllers, Res.string.combo_section, combos = true)) }
    addAll(page(SettingsPageId.QUICK_MENU, Res.string.qt_add))
    addAll(page(
        SettingsPageId.QUICK_MENU, Res.string.qm_touch_section, Res.string.qm_edge_handle, Res.string.qm_tile_add,
        section = Res.string.qm_touch_section,
    ))
    addAll(page(
        SettingsPageId.SOUNDS, Res.string.setting_ui_sounds, Res.string.setting_ui_volume, Res.string.setting_sound_style,
        Res.string.setting_sound_pitch, Res.string.sounds_events, Res.string.setting_haptics, Res.string.setting_haptic_strength,
        Res.string.setting_haptic_focus,
    ))
    addAll(page(SettingsPageId.LAUNCHER, Res.string.launcher_enable, Res.string.launcher_system_settings))
    addAll(page(SettingsPageId.ABOUT, Res.string.about_license, Res.string.about_source, Res.string.about_data_sources))
    addAll(page(
        SettingsPageId.DATA, Res.string.data_storage, Res.string.data_clear_cache, Res.string.data_orphans, Res.string.data_redownload,
        Res.string.settings_backup, Res.string.backup_folder, Res.string.backup_auto, Res.string.backup_keep, Res.string.backup_with_credentials,
        Res.string.backup_now, Res.string.backup_restore, Res.string.data_reset,
    ))
}

/**
 * Search hits for [query]: settings whose title, page or section contains it (case and accents
 * ignored). Pages come first, then their settings, in index order.
 */
@Composable
fun SettingsSearchResults(query: String, onOpen: (SettingsTarget, String?) -> Unit) {
    // Every title resolved once in the current language; the search itself runs on plain text.
    val texts = (settingsIndex.flatMap { listOfNotNull(it.title, it.section) } + pageTitles.map { it.second }).distinct()
        .associateWith { stringResource(it) }
    val q = TitleNormalizer.normalize(query)
    fun pageTitle(target: SettingsTarget) = pageTitles.first { it.first == target }.second
    val platform = io.github.thecodingdad.gamelodge.ui.PlatformUi.current
    val hits = settingsIndex.filter { e ->
        if (e.target is SettingsTarget.Page && e.target.id in platformPages && !platform.hasPage(e.target.id)) return@filter false
        if (e.combos && !platform.hasButtonCombos) return@filter false
        val haystack = listOfNotNull(e.title, pageTitle(e.target), e.section).joinToString(" ") { texts.getValue(it) }
        TitleNormalizer.normalize(haystack).contains(q)
    }.distinctBy { it.title to it.target }
    if (hits.isEmpty()) {
        Text(stringResource(Res.string.settings_search_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        return
    }
    hits.forEach { e ->
        val isPage = pageTitles.any { it.first == e.target && it.second == e.title }
        val path = if (isPage) null else listOfNotNull(pageTitle(e.target), e.section?.takeIf { it != e.title })
            .joinToString("  ›  ") { texts.getValue(it) }
        NavRow(
            texts.getValue(e.title), { onOpen(e.target, if (isPage) null else texts.getValue(e.title)) },
            subtitle = path, icon = if (isPage) null else Icons.Filled.Search,
            highlightable = false,
        )
    }
}
