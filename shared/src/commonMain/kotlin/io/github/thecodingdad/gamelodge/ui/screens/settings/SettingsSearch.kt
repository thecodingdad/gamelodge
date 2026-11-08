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
/** Pages of the controller settings; [PlatformUi.controllerSections] says which a platform has. */
enum class ControllerSection { MOUSE }

sealed interface SettingsTarget {
    data class Page(val id: SettingsPageId) : SettingsTarget
    data object Controllers : SettingsTarget
    /** One page of the controller settings (Settings › Controllers › …). */
    data class ControllerPage(val section: ControllerSection) : SettingsTarget
    /** The setup wizard (Settings › System). */
    data object Wizard : SettingsTarget
    /** Settings of one streaming host. */
    data class Host(val uuid: String) : SettingsTarget
    /** The settings overview itself (rows on it, e.g. the start tab). */
    data object Overview : SettingsTarget
    /** The list of metadata matches to check ("please check"). */
    data object Review : SettingsTarget
}

/** Whether [target] exists on this platform (platform-only pages). */
fun settingsTargetAvailable(target: SettingsTarget): Boolean = when (target) {
    is SettingsTarget.Page -> target.id !in platformPages || io.github.thecodingdad.gamelodge.ui.PlatformUi.current.hasPage(target.id)
    is SettingsTarget.ControllerPage -> target.section in io.github.thecodingdad.gamelodge.ui.PlatformUi.current.controllerSections
    else -> true
}

/** One searchable setting: its title, the page it is on and (optionally) the section there. */
private class SearchEntry(val title: StringResource, val target: SettingsTarget, val section: StringResource? = null, val combos: Boolean = false, val device: Boolean = false)

private fun page(id: SettingsPageId, vararg titles: StringResource, section: StringResource? = null, device: Boolean = false) =
    titles.map { SearchEntry(it, SettingsTarget.Page(id), section, device = device) }

private val pageTitles: List<Pair<SettingsTarget, StringResource>> = listOf(
    SettingsTarget.Page(SettingsPageId.HOME_SCREEN) to Res.string.settings_home_screen,
    SettingsTarget.Page(SettingsPageId.LIBRARY_VIEW) to Res.string.settings_library_view,
    SettingsTarget.Page(SettingsPageId.APPS_VIEW) to Res.string.settings_apps_view,
    SettingsTarget.Page(SettingsPageId.THEME) to Res.string.settings_theme,
    SettingsTarget.Page(SettingsPageId.DISPLAY) to Res.string.settings_display,
    SettingsTarget.Page(SettingsPageId.FILTERS) to Res.string.settings_filters,
    SettingsTarget.Page(SettingsPageId.METADATA) to Res.string.settings_metadata,
    SettingsTarget.Page(SettingsPageId.USAGE) to Res.string.settings_usage,
    SettingsTarget.Page(SettingsPageId.EMULATORS) to Res.string.settings_emulators,
    SettingsTarget.Page(SettingsPageId.GAME_SOURCES) to Res.string.settings_game_sources,
    SettingsTarget.Page(SettingsPageId.HOST_ROLE) to Res.string.settings_host_role,
    SettingsTarget.Page(SettingsPageId.STREAMING_HOST) to Res.string.settings_streaming_host,
    SettingsTarget.Page(SettingsPageId.STARTUP) to Res.string.settings_startup,
    SettingsTarget.Page(SettingsPageId.HOSTS) to Res.string.settings_hosts,
    SettingsTarget.Page(SettingsPageId.HOSTS) to Res.string.settings_profiles,
    SettingsTarget.Page(SettingsPageId.VPN) to Res.string.settings_vpn,
    SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT) to Res.string.settings_home_assistant,
    SettingsTarget.Controllers to Res.string.settings_controllers,
    SettingsTarget.Page(SettingsPageId.INPUT) to Res.string.settings_input,
    SettingsTarget.Wizard to Res.string.settings_group_system,
    SettingsTarget.Page(SettingsPageId.SOUNDS) to Res.string.settings_sounds,
    SettingsTarget.Page(SettingsPageId.LAUNCHER) to Res.string.settings_launcher,
    SettingsTarget.Page(SettingsPageId.LANGUAGE) to Res.string.settings_language,
    SettingsTarget.Page(SettingsPageId.DATA) to Res.string.settings_data,
    SettingsTarget.Page(SettingsPageId.ABOUT) to Res.string.settings_about,
    SettingsTarget.Page(SettingsPageId.LICENSES) to Res.string.about_open_source,
)

/** Further words a page is found by (synonyms, brands, what its settings are about). */
private val pageKeywords: Map<SettingsTarget, StringResource> = mapOf(
    SettingsTarget.Page(SettingsPageId.HOME_SCREEN) to Res.string.search_kw_home_screen,
    SettingsTarget.Page(SettingsPageId.THEME) to Res.string.search_kw_theme,
    SettingsTarget.Page(SettingsPageId.DISPLAY) to Res.string.search_kw_display,
    SettingsTarget.Page(SettingsPageId.LIBRARY_VIEW) to Res.string.search_kw_library_view,
    SettingsTarget.Page(SettingsPageId.APPS_VIEW) to Res.string.search_kw_apps_view,
    SettingsTarget.Page(SettingsPageId.FILTERS) to Res.string.search_kw_filters,
    SettingsTarget.Page(SettingsPageId.METADATA) to Res.string.search_kw_metadata,
    SettingsTarget.Page(SettingsPageId.USAGE) to Res.string.search_kw_usage,
    SettingsTarget.Page(SettingsPageId.GAME_SOURCES) to Res.string.search_kw_game_sources,
    SettingsTarget.Page(SettingsPageId.EMULATORS) to Res.string.search_kw_emulators,
    SettingsTarget.Page(SettingsPageId.HOSTS) to Res.string.search_kw_hosts,
    SettingsTarget.Page(SettingsPageId.HOSTS) to Res.string.search_kw_profiles,
    SettingsTarget.Page(SettingsPageId.VPN) to Res.string.search_kw_vpn,
    SettingsTarget.Page(SettingsPageId.STREAMING_HOST) to Res.string.search_kw_streaming_host,
    SettingsTarget.Page(SettingsPageId.HOST_ROLE) to Res.string.search_kw_host_role,
    SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT) to Res.string.search_kw_home_assistant,
    SettingsTarget.Controllers to Res.string.search_kw_controllers,
    SettingsTarget.Page(SettingsPageId.INPUT) to Res.string.search_kw_input,
    SettingsTarget.Page(SettingsPageId.SOUNDS) to Res.string.search_kw_sounds,
    SettingsTarget.Page(SettingsPageId.LAUNCHER) to Res.string.search_kw_launcher,
    SettingsTarget.Page(SettingsPageId.STARTUP) to Res.string.search_kw_startup,
    SettingsTarget.Page(SettingsPageId.LANGUAGE) to Res.string.search_kw_language,
    SettingsTarget.Page(SettingsPageId.DATA) to Res.string.search_kw_data,
    SettingsTarget.Page(SettingsPageId.ABOUT) to Res.string.search_kw_about,
)

/**
 * How well [q] (normalised) matches: the title counts most (whole, start, start of a word,
 * anywhere), then the page's keywords, then page and section names; 0 = no match.
 */
internal fun searchScore(q: String, title: String, keywords: List<String>, context: String, isPage: Boolean): Int {
    if (q.isEmpty()) return 0
    val words = title.split(' ')
    val titleScore = when {
        title == q -> 100
        title.startsWith(q) -> 80
        words.any { it.startsWith(q) } -> 60
        title.contains(q) -> 40
        else -> 0
    }
    val keywordScore = when {
        keywords.any { it == q } -> 35
        keywords.any { k -> k.split(' ').any { it.startsWith(q) } } -> 25
        keywords.any { it.contains(q) } -> 15
        else -> 0
    }
    val contextScore = if (context.contains(q)) 8 else 0
    val best = maxOf(titleScore, keywordScore, contextScore)
    // Pages slightly ahead of their own settings with the same match.
    return if (best == 0) 0 else best + if (isPage) 3 else 0
}

/** Pages only some platforms have (shown in the search only where they exist). */
private val platformPages = setOf(
    SettingsPageId.USAGE, SettingsPageId.EMULATORS, SettingsPageId.PROFILES, SettingsPageId.VPN,
    SettingsPageId.LAUNCHER, SettingsPageId.GAME_SOURCES, SettingsPageId.HOST_ROLE, SettingsPageId.STREAMING_HOST, SettingsPageId.STARTUP,
)

/** Every visible setting by page (dialog-internal and per-item settings are left out). */
private val settingsIndex: List<SearchEntry> = buildList {
    // The pages themselves.
    pageTitles.forEach { (target, title) -> add(SearchEntry(title, target)) }
    // Tile settings of the three tabs (the same titles on each page; the section names the tab).
    for ((id, tab) in listOf(SettingsPageId.HOME_SCREEN to Res.string.settings_home_screen, SettingsPageId.LIBRARY_VIEW to Res.string.settings_library_view, SettingsPageId.APPS_VIEW to Res.string.settings_apps_view)) {
        addAll(page(id, Res.string.setting_card_size, Res.string.setting_tile_corners, Res.string.setting_tile_title, Res.string.setting_tile_badges, section = tab))
    }
    addAll(page(SettingsPageId.HOME_SCREEN, Res.string.setting_tile_format, Res.string.setting_tile_info, section = Res.string.settings_home_screen))
    addAll(page(SettingsPageId.LIBRARY_VIEW, Res.string.setting_tile_format, Res.string.setting_tile_info, section = Res.string.settings_library_view))
    addAll(page(SettingsPageId.APPS_VIEW, Res.string.setting_apps_style, section = Res.string.settings_apps_view))
    addAll(page(SettingsPageId.HOME_SCREEN, Res.string.home_rows_section, Res.string.home_row_ha, Res.string.setting_home_rows_position))
    add(SearchEntry(Res.string.setting_start_tab, SettingsTarget.Overview, Res.string.settings_group_display))
    addAll(page(
        SettingsPageId.THEME, Res.string.setting_theme, Res.string.setting_accent, Res.string.setting_accent_color,
        Res.string.setting_focus_zoom, Res.string.setting_focus_glow, Res.string.setting_dim_not_installed, Res.string.setting_animated_tiles, Res.string.setting_animated_hero,
        Res.string.setting_reduce_motion, Res.string.setting_ui_scale,
    ))
    addAll(page(SettingsPageId.DISPLAY, Res.string.setting_screensaver, Res.string.section_screensaver, Res.string.setting_ken_burns))
    addAll(page(SettingsPageId.DISPLAY, Res.string.setting_hide_nav_bar, Res.string.section_system_bars, device = true))
    addAll(page(
        SettingsPageId.DISPLAY, Res.string.tv_section, Res.string.setting_tv_launcher, Res.string.setting_tv_resolution,
        Res.string.setting_tv_scale_auto, Res.string.setting_tv_scale, section = Res.string.tv_section, device = true,
    ))
    addAll(page(SettingsPageId.METADATA, Res.string.metadata_auto, Res.string.metadata_review_list, Res.string.metadata_sources, Res.string.metadata_source_add, Res.string.metadata_source_companion, Res.string.metadata_source_server, Res.string.metadata_send_keys))
    addAll(page(SettingsPageId.USAGE, Res.string.usage_access, Res.string.stats_title))
    addAll(page(
        SettingsPageId.EMULATORS, Res.string.emu_folders, Res.string.emu_folder_add, Res.string.emu_all_files, Res.string.emu_emulators, Res.string.emu_custom,
    ))
    addAll(page(
        SettingsPageId.VPN, Res.string.vpn_tunnels, Res.string.vpn_default_tunnel, Res.string.vpn_auto, Res.string.vpn_on_stream,
        Res.string.vpn_only_outside, Res.string.vpn_home_networks, Res.string.vpn_disconnect_after,
    ))
    addAll(page(SettingsPageId.HOSTS, Res.string.conn_warnings))
    addAll(page(SettingsPageId.HOME_ASSISTANT, Res.string.ha_tiles, Res.string.ha_add_tile, Res.string.ha_host_actions, Res.string.mqtt_title))
    addAll(page(SettingsPageId.HOME_SCREEN, Res.string.setting_games_direct_start))
    addAll(page(SettingsPageId.LIBRARY_VIEW, Res.string.setting_games_direct_start))
    add(SearchEntry(Res.string.controllers_connected, SettingsTarget.Controllers))
    // Layout and combinations are set per controller (its dialog on the controllers page).
    listOf(Res.string.setting_button_layout, Res.string.setting_hint_bar)
        .forEach { add(SearchEntry(it, SettingsTarget.Controllers)) }
    listOf(Res.string.combo_section, Res.string.combo_home, Res.string.combo_quick_menu, Res.string.combo_forward, Res.string.combo_service)
        .forEach { add(SearchEntry(it, SettingsTarget.Controllers, combos = true)) }
    add(SearchEntry(Res.string.controller_section_mouse, SettingsTarget.ControllerPage(ControllerSection.MOUSE)))
    listOf(Res.string.setting_controller_attach, Res.string.odin_mapping_section, Res.string.odin_mapping_all)
        .forEach { add(SearchEntry(it, SettingsTarget.Controllers)) }
    addAll(page(
        SettingsPageId.INPUT, Res.string.input_mouse_side, Res.string.input_shortcut, Res.string.input_keyboard_section,
    ))
    addAll(page(
        SettingsPageId.SOUNDS, Res.string.setting_ui_sounds, Res.string.setting_ui_volume, Res.string.setting_sound_style,
        Res.string.setting_sound_pitch, Res.string.sounds_events, Res.string.setting_haptics, Res.string.setting_haptic_strength,
        Res.string.setting_haptic_focus,
    ))
    addAll(page(SettingsPageId.LAUNCHER, Res.string.launcher_enable))
    addAll(page(SettingsPageId.ABOUT, Res.string.about_license, Res.string.about_source, Res.string.about_data_sources))
    addAll(page(
        SettingsPageId.DATA, Res.string.data_storage, Res.string.data_clear_cache, Res.string.data_orphans, Res.string.data_redownload,
        Res.string.settings_backup, Res.string.backup_folder, Res.string.backup_auto, Res.string.backup_keep, Res.string.backup_with_credentials,
        Res.string.backup_now, Res.string.backup_restore, Res.string.data_reset,
    ))
    add(SearchEntry(Res.string.wizard_settings_row, SettingsTarget.Wizard))
}

/**
 * Search hits for [query] (case and accents ignored), best match first: title, then the page's
 * keywords, then page and section names.
 */
@Composable
fun SettingsSearchResults(query: String, onOpen: (SettingsTarget, String?) -> Unit) {
    // Every title resolved once in the current language; the search itself runs on plain text.
    val texts = (settingsIndex.flatMap { listOfNotNull(it.title, it.section) } + pageTitles.map { it.second } + pageKeywords.values).distinct()
        .associateWith { stringResource(it) }
    val q = TitleNormalizer.normalize(query)
    // Rows on the overview itself have no page in their path, only their section.
    fun pageTitle(target: SettingsTarget) = pageTitles.firstOrNull { it.first == target }?.second
    val platform = io.github.thecodingdad.gamelodge.ui.PlatformUi.current
    val hits = settingsIndex.withIndex().mapNotNull { (index, e) ->
        if (!settingsTargetAvailable(e.target)) return@mapNotNull null
        if (e.combos && !platform.hasButtonCombos) return@mapNotNull null
        if (e.device && !platform.deviceDisplayOptions) return@mapNotNull null
        val isPage = pageTitles.any { it.first == e.target && it.second == e.title }
        // A page's keywords find the page itself; its settings only by their own words.
        val keywords = if (isPage) pageKeywords[e.target]?.let { texts.getValue(it).split(',').map { k -> TitleNormalizer.normalize(k) } }.orEmpty() else emptyList()
        val context = listOfNotNull(pageTitle(e.target), e.section).joinToString(" ") { TitleNormalizer.normalize(texts.getValue(it)) }
        val score = searchScore(q, TitleNormalizer.normalize(texts.getValue(e.title)), keywords, context, isPage)
        if (score == 0) null else Triple(e, score, index)
    }.sortedWith(compareByDescending<Triple<SearchEntry, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
        .distinctBy { it.title to it.target }
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
