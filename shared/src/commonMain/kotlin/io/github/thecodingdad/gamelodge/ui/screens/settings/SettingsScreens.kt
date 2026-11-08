package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Visibility
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.AppsStyle
import io.github.thecodingdad.gamelodge.data.settings.FocusZoom
import io.github.thecodingdad.gamelodge.data.settings.TabLayout
import io.github.thecodingdad.gamelodge.data.settings.TileBadge
import io.github.thecodingdad.gamelodge.data.settings.TileCorners
import io.github.thecodingdad.gamelodge.data.settings.TileFormat
import io.github.thecodingdad.gamelodge.data.settings.TileInfo
import io.github.thecodingdad.gamelodge.data.settings.TileTitle
import io.github.thecodingdad.gamelodge.ui.components.aspectRatio
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.math.roundToInt
import io.github.thecodingdad.gamelodge.data.settings.AccentMode
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.data.settings.CardSize
import io.github.thecodingdad.gamelodge.data.settings.StartTab
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.ThemeMode
import io.github.thecodingdad.gamelodge.resources.*

enum class SettingsPageId { INPUT, GAME_SOURCES, HOST_ROLE, STREAMING_HOST, STARTUP, HOSTS, PROFILES, HOME_SCREEN, LIBRARY_VIEW, APPS_VIEW, THEME, DISPLAY, FILTERS, APP_FILTERS, SOUNDS, METADATA, USAGE, EMULATORS, HOME_ASSISTANT, VPN, LAUNCHER, DATA, LANGUAGE, ABOUT, LICENSES }

@Composable
fun SettingsHome(
    open: (SettingsPageId) -> Unit,
    isFirstVisit: Boolean,
    openControllers: () -> Unit = {},
    openWizard: () -> Unit = {},
    openStats: (() -> Unit)? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
) {
    val platform = io.github.thecodingdad.gamelodge.ui.PlatformUi.current
    // Back (gesture, B, Esc) ends a search and shows all settings again.
    io.github.thecodingdad.gamelodge.ui.components.BackHandler(enabled = query.isNotEmpty()) { onQueryChange("") }
    val keyboard = io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController.current
    val navigate = LocalSettingsNavigator.current
    val searchTitle = stringResource(Res.string.settings_search)
    // START: search with the controller keyboard (the field itself works by touch and A too).
    io.github.thecodingdad.gamelodge.ui.input.PadAction(io.github.thecodingdad.gamelodge.ui.input.PadButton.START) {
        keyboard.open(searchTitle, query, controller = true, onDone = onQueryChange)
    }
    SettingsPage(stringResource(Res.string.tab_settings)) {
        io.github.thecodingdad.gamelodge.ui.components.LauncherTextField(searchTitle, query, onQueryChange)
        if (query.isNotBlank()) {
            SettingsSearchResults(query) { target, title ->
                SearchHighlight.request(title)
                when (target) {
                    is SettingsTarget.Page -> open(target.id)
                    SettingsTarget.Controllers -> openControllers()
                    is SettingsTarget.ControllerPage -> navigate(target, title)
                    SettingsTarget.Wizard -> openWizard()
                    is SettingsTarget.Host -> Unit
                    SettingsTarget.Overview -> onQueryChange("")
                    SettingsTarget.Review -> Unit
                }
            }
            return@SettingsPage
        }
        SectionHeader(stringResource(Res.string.settings_group_display))
        StartTabRow(initialFocus = isFirstVisit)
        NavRow(stringResource(Res.string.settings_home_screen), { open(SettingsPageId.HOME_SCREEN) }, stringResource(Res.string.settings_home_screen_sub), Icons.Filled.Home)
        NavRow(stringResource(Res.string.settings_library_view), { open(SettingsPageId.LIBRARY_VIEW) }, stringResource(Res.string.settings_library_view_sub), Icons.Filled.GridView)
        NavRow(stringResource(Res.string.settings_apps_view), { open(SettingsPageId.APPS_VIEW) }, stringResource(Res.string.settings_apps_view_sub), Icons.Filled.Apps)
        NavRow(stringResource(Res.string.settings_theme), { open(SettingsPageId.THEME) }, stringResource(Res.string.settings_theme_sub), Icons.Filled.Palette)
        NavRow(stringResource(Res.string.settings_display), { open(SettingsPageId.DISPLAY) }, stringResource(if (platform.deviceDisplayOptions) Res.string.settings_display_sub else Res.string.settings_display_sub_desktop), Icons.Filled.Monitor)

        SectionHeader(stringResource(Res.string.settings_group_library))
        if (platform.hasPage(SettingsPageId.GAME_SOURCES)) NavRow(stringResource(Res.string.settings_game_sources), { open(SettingsPageId.GAME_SOURCES) }, stringResource(Res.string.settings_game_sources_sub), Icons.Filled.SportsEsports)
        if (platform.hasPage(SettingsPageId.EMULATORS)) NavRow(stringResource(Res.string.settings_emulators), { open(SettingsPageId.EMULATORS) }, stringResource(Res.string.settings_emulators_sub), Icons.Filled.SportsEsports)
        NavRow(stringResource(Res.string.settings_metadata), { open(SettingsPageId.METADATA) }, stringResource(Res.string.settings_metadata_sub), Icons.Filled.Image)
        NavRow(stringResource(Res.string.settings_filters), { open(SettingsPageId.FILTERS) }, stringResource(Res.string.settings_filters_sub), Icons.Filled.FilterAlt)
        // Play time and statistics last: Android's usage page (usage access + statistics), else the statistics directly.
        if (platform.hasPage(SettingsPageId.USAGE)) {
            NavRow(stringResource(Res.string.settings_usage), { open(SettingsPageId.USAGE) }, stringResource(Res.string.settings_usage_sub), Icons.Filled.QueryStats)
        } else if (openStats != null) {
            NavRow(stringResource(Res.string.stats_title), openStats, stringResource(Res.string.usage_stats_sub), Icons.Filled.QueryStats)
        }

        SectionHeader(stringResource(Res.string.settings_group_streaming))
        NavRow(stringResource(Res.string.settings_hosts), { open(SettingsPageId.HOSTS) }, stringResource(Res.string.settings_hosts_sub), Icons.Filled.Computer)
        if (platform.hasPage(SettingsPageId.VPN)) NavRow(stringResource(Res.string.settings_vpn), { open(SettingsPageId.VPN) }, stringResource(Res.string.settings_vpn_sub), Icons.Filled.VpnLock)
        if (platform.hasPage(SettingsPageId.STREAMING_HOST)) {
            NavRow(
                stringResource(Res.string.settings_streaming_host), { open(SettingsPageId.STREAMING_HOST) },
                platform.pageStatus(SettingsPageId.STREAMING_HOST) ?: stringResource(Res.string.settings_streaming_host_sub), Icons.Filled.Cast,
            )
        }
        // Devices & pairing is part of the streaming host page (only with a configured host).
        NavRow(stringResource(Res.string.settings_home_assistant), { open(SettingsPageId.HOME_ASSISTANT) }, stringResource(Res.string.settings_home_assistant_sub), vectorResource(Res.drawable.ic_home_assistant))

        SectionHeader(stringResource(Res.string.settings_group_controls))
        NavRow(stringResource(Res.string.settings_controllers), openControllers, stringResource(Res.string.settings_controllers_sub), Icons.Filled.Gamepad)
        NavRow(stringResource(Res.string.settings_input), { open(SettingsPageId.INPUT) }, stringResource(Res.string.settings_input_sub), Icons.Filled.Keyboard)
        NavRow(stringResource(Res.string.settings_sounds), { open(SettingsPageId.SOUNDS) }, stringResource(Res.string.settings_sounds_sub), Icons.Filled.VolumeUp)

        SectionHeader(stringResource(Res.string.settings_group_system))
        NavRow(stringResource(Res.string.wizard_settings_row), openWizard, stringResource(Res.string.wizard_settings_row_sub), Icons.Filled.AutoFixHigh)
        if (platform.hasPage(SettingsPageId.LAUNCHER)) NavRow(stringResource(Res.string.settings_launcher), { open(SettingsPageId.LAUNCHER) }, stringResource(Res.string.settings_launcher_sub), Icons.Filled.Home)
        if (platform.hasPage(SettingsPageId.STARTUP)) NavRow(stringResource(Res.string.settings_startup), { open(SettingsPageId.STARTUP) }, stringResource(Res.string.settings_startup_sub), Icons.Filled.PowerSettingsNew)
        NavRow(stringResource(Res.string.settings_language), { open(SettingsPageId.LANGUAGE) }, null, Icons.Filled.Language)
        NavRow(stringResource(Res.string.settings_data), { open(SettingsPageId.DATA) }, stringResource(Res.string.settings_data_sub), Icons.Filled.Storage)
        // The device's own settings (Android settings app).
        platform.SystemSettingsRow()
        NavRow(stringResource(Res.string.settings_about), { open(SettingsPageId.ABOUT) }, null, Icons.Filled.Info)
    }
}

/** What the launcher shows after starting and when idle, and which rows the start tab has. */
@Composable
fun HomeScreenSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    vm: io.github.thecodingdad.gamelodge.ui.LauncherViewModel,
) {
    val filters by vm.filters.collectAsState()
    SettingsPage(stringResource(Res.string.settings_home_screen)) {
        TileSettings(settings.homeLayout, { l -> update { it.copy(homeLayout = l) } }, startTab = true)
        DirectStartRow(settings.homeDirectStart) { v -> update { it.copy(homeDirectStart = v) } }
        ChoiceRow(
            stringResource(Res.string.setting_home_rows_position), listOf(false, true), settings.homeRowsTop,
            label = { top -> stringResource(if (top) Res.string.setting_home_rows_top else Res.string.setting_home_rows_bottom) },
            onSelect = { v -> update { it.copy(homeRowsTop = v) } },
            subtitle = stringResource(Res.string.setting_home_rows_position_sub),
        )
        SectionHeader(stringResource(Res.string.home_rows_section))
        Text(stringResource(Res.string.home_rows_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        // Game filters first, then app filters (their rows show landscape app tiles).
        val isApps = { f: io.github.thecodingdad.gamelodge.data.db.FilterEntity -> f.scope == io.github.thecodingdad.gamelodge.data.db.FilterScope.APPS }
        val gameFilters = filters.sortedWith(compareBy({ isApps(it) }, { it.sortOrder }))
        val byId = gameFilters.associateBy { it.id }
        val appsPrefix = stringResource(Res.string.tab_apps)
        val HA = io.github.thecodingdad.gamelodge.data.library.HomeRowOrder.HA
        // The Home Assistant row needs a Home Assistant sign-in; without one it is greyed out (still movable).
        val haSignedIn = io.github.thecodingdad.gamelodge.LocalContainer.current.ha.auth.isLoggedIn
        // Only the rows shown: each can be moved (handle) or removed (✕); more are added below.
        val all = io.github.thecodingdad.gamelodge.data.library.HomeRowOrder.ids(gameFilters.map { it.id }, settings.homeRowOrder)
        fun shown(id: String) = if (id == HA) settings.showHaRowOnHome else byId[id]?.showOnHome == true
        @Composable fun label(id: String) = byId[id]?.let { f -> if (isApps(f)) "$appsPrefix · ${filterEntityLabel(f)}" else filterEntityLabel(f) }
        val haLabel = stringResource(Res.string.home_row_ha)
        val visible = all.filter(::shown)
        if (visible.isEmpty()) {
            Text(stringResource(Res.string.home_rows_empty), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        }
        ReorderableList(
            items = visible,
            id = { it },
            // The hidden rows keep their place after the shown ones.
            onReorder = { ids -> update { it.copy(homeRowOrder = ids + all.filter { id -> id !in ids }) } },
        ) { _, id ->
            val text = if (id == HA) haLabel else label(id) ?: return@ReorderableList
            RemovableRow(
                text,
                subtitle = if (id == HA && !haSignedIn) stringResource(Res.string.home_row_ha_needs_ha) else null,
                icon = if (id == HA) Icons.Filled.Home else if (byId[id]?.let(isApps) == true) Icons.Filled.Apps else Icons.Filled.GridView,
                onRemove = {
                    if (id == HA) update { it.copy(showHaRowOnHome = false) }
                    else byId[id]?.let { f -> vm.saveFilter(f.copy(showOnHome = false)) }
                },
            )
        }
        var adding by androidx.compose.runtime.remember { mutableStateOf(false) }
        val hidden = all.filter { !shown(it) && (it == HA || byId[it] != null) }
        io.github.thecodingdad.gamelodge.ui.components.LauncherButton(
            stringResource(Res.string.home_row_add), onClick = { adding = true }, icon = Icons.Filled.Add,
            primary = visible.isEmpty(), enabled = hidden.isNotEmpty(),
        )
        io.github.thecodingdad.gamelodge.ui.components.Modal(
            visible = adding && hidden.isNotEmpty(), onDismiss = { adding = false }, title = stringResource(Res.string.home_row_add),
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f, fill = false).popupHeightIn(480.dp).controllerScroll(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                hidden.forEachIndexed { index, id ->
                    val text = if (id == HA) haLabel else label(id) ?: return@forEachIndexed
                    NavRow(
                        text,
                        {
                            // New rows go after the shown ones.
                            val rest = all - id
                            val at = rest.indexOfLast(::shown) + 1
                            update { it.copy(homeRowOrder = rest.toMutableList().apply { add(at, id) }, showHaRowOnHome = if (id == HA) true else it.showHaRowOnHome) }
                            if (id != HA) byId[id]?.let { f -> vm.saveFilter(f.copy(showOnHome = true)) }
                            adding = false
                        },
                        if (id == HA && !haSignedIn) stringResource(Res.string.home_row_ha_needs_ha) else null,
                        if (id == HA) Icons.Filled.Home else if (byId[id]?.let(isApps) == true) Icons.Filled.Apps else Icons.Filled.GridView,
                        initialFocus = index == 0,
                    )
                }
            }
        }
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_filters), SettingsTarget.Page(SettingsPageId.FILTERS)),
            SeeAlsoLink(stringResource(Res.string.settings_home_assistant), SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT), subtitle = stringResource(Res.string.home_row_ha)),
        )
    }
}

/** The tile settings of one tab, below a live preview of them. */
@Composable
private fun TileSettings(layout: TabLayout, onChange: (TabLayout) -> Unit, apps: Boolean = false, startTab: Boolean = false) {
    // One tile in its real size, on request (row or Y): the page keeps its room for the settings.
    var preview by androidx.compose.runtime.remember { mutableStateOf(false) }
    io.github.thecodingdad.gamelodge.ui.input.PadAction(io.github.thecodingdad.gamelodge.ui.input.PadButton.Y) { preview = true }
    io.github.thecodingdad.gamelodge.ui.shell.ScreenHints(
        listOf(
            io.github.thecodingdad.gamelodge.ui.components.ButtonHint(io.github.thecodingdad.gamelodge.ui.input.PadButton.A, stringResource(Res.string.hint_select)),
            io.github.thecodingdad.gamelodge.ui.components.ButtonHint(io.github.thecodingdad.gamelodge.ui.input.PadButton.Y, stringResource(Res.string.hint_preview), onClick = { preview = true }),
            io.github.thecodingdad.gamelodge.ui.components.ButtonHint(io.github.thecodingdad.gamelodge.ui.input.PadButton.B, stringResource(Res.string.hint_back)),
        ),
    )
    SectionHeader(stringResource(Res.string.section_tile_look))
    if (preview) TilePreviewDialog(layout, apps = apps, startTab = startTab) { preview = false }
    if (apps) {
        ChoiceRow(
            stringResource(Res.string.setting_apps_style), AppsStyle.entries, layout.appsStyle,
            label = { stringResource(if (it == AppsStyle.ICONS) Res.string.apps_style_icons else Res.string.apps_style_banners) },
            onSelect = { onChange(layout.copy(appsStyle = it)) },
            optionIcon = { ShapeMark(if (it == AppsStyle.ICONS) 1f else 16f / 9f) },
            initialFocus = true,
        )
    }
    ChoiceRow(
        stringResource(Res.string.setting_card_size), CardSize.entries, layout.cardSize,
        label = {
            stringResource(
                when (it) {
                    CardSize.SMALL -> Res.string.size_small
                    CardSize.MEDIUM -> Res.string.size_medium
                    CardSize.LARGE -> Res.string.size_large
                },
            )
        },
        onSelect = { onChange(layout.copy(cardSize = it)) },
        initialFocus = !apps,
    )
    if (!apps) {
        ChoiceRow(
            stringResource(Res.string.setting_tile_format), TileFormat.entries, layout.format,
            label = {
                stringResource(
                    when (it) {
                        TileFormat.PORTRAIT -> Res.string.tile_portrait
                        TileFormat.LANDSCAPE -> Res.string.tile_landscape
                        TileFormat.SQUARE -> Res.string.tile_square
                    },
                )
            },
            onSelect = { onChange(layout.copy(format = it)) },
            optionIcon = { ShapeMark(it.aspectRatio()) },
            subtitle = if (startTab) stringResource(Res.string.setting_tile_format_home_sub) else null,
        )
    }
    ChoiceRow(
        stringResource(Res.string.setting_tile_corners), TileCorners.entries, layout.corners,
        label = {
            stringResource(
                when (it) {
                    TileCorners.SHARP -> Res.string.corners_sharp
                    TileCorners.SLIGHT -> Res.string.corners_slight
                    TileCorners.ROUND -> Res.string.corners_round
                },
            )
        },
        onSelect = { onChange(layout.copy(corners = it)) },
        optionIcon = { c ->
            Box(
                Modifier.size(16.dp).border(
                    2.dp, androidx.compose.material3.LocalContentColor.current,
                    RoundedCornerShape(
                        when (c) {
                            TileCorners.SHARP -> 1.dp
                            TileCorners.SLIGHT -> 4.dp
                            TileCorners.ROUND -> 8.dp
                        },
                    ),
                ),
            )
        },
    )
    ChoiceRow(
        stringResource(Res.string.setting_tile_title), TileTitle.entries, layout.title,
        label = {
            stringResource(
                when (it) {
                    TileTitle.OFF -> Res.string.tile_title_off
                    TileTitle.BELOW -> Res.string.tile_title_below
                    TileTitle.ON_TILE -> Res.string.tile_title_on
                },
            )
        },
        onSelect = { onChange(layout.copy(title = it)) },
    )
    // Icon tiles have no room for more than the name.
    if (!(apps && layout.appsStyle == AppsStyle.ICONS)) {
        ChoiceRow(
            stringResource(Res.string.setting_tile_info), if (apps) listOf(TileInfo.NONE, TileInfo.PLAYTIME, TileInfo.LAST_PLAYED) else TileInfo.entries, layout.info,
            label = {
                stringResource(
                    when (it) {
                        TileInfo.NONE -> Res.string.tile_info_none
                        TileInfo.PLAYTIME -> Res.string.tile_info_playtime
                        TileInfo.LAST_PLAYED -> Res.string.tile_info_last
                        TileInfo.PROGRESS -> Res.string.tile_info_progress
                    },
                )
            },
            onSelect = { onChange(layout.copy(info = it)) },
            subtitle = if (apps) null else stringResource(Res.string.setting_tile_info_sub),
        )
    }
    // Where a game runs (this device, emulator, host) is one badge: shown or not.
    val sources = setOf(TileBadge.LOCAL, TileBadge.ROM, TileBadge.HOST)
    ChipToggleRow(
        stringResource(Res.string.setting_tile_badges),
        if (apps) listOf(TileBadge.FAVORITE, TileBadge.LOCAL) else listOf(TileBadge.FAVORITE, TileBadge.LOCAL, TileBadge.NOT_INSTALLED),
        layout.badges,
        label = {
            when (it) {
                TileBadge.FAVORITE -> stringResource(Res.string.badge_favorite)
                TileBadge.NOT_INSTALLED -> stringResource(Res.string.badge_not_installed)
                else -> stringResource(Res.string.badge_source)
            }
        },
        icon = {
            when (it) {
                TileBadge.FAVORITE -> Icons.Filled.Favorite
                TileBadge.NOT_INSTALLED -> Icons.Filled.Download
                else -> Icons.Filled.Cloud
            }
        },
        onToggle = { badge, on ->
            val group = if (badge in sources) sources else setOf(badge)
            onChange(layout.copy(badges = if (on) layout.badges + group else layout.badges - group))
        },
        subtitle = stringResource(Res.string.setting_tile_badges_sub),
    )
    // The settings above on one tile in its real size.
    io.github.thecodingdad.gamelodge.ui.components.LauncherButton(stringResource(Res.string.tile_preview_show), { preview = true }, icon = Icons.Filled.Visibility)
}

@Composable
fun LibraryViewSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(Res.string.settings_library_view)) {
        TileSettings(settings.libraryLayout, { l -> update { it.copy(libraryLayout = l) } })
        DirectStartRow(settings.libraryDirectStart) { v -> update { it.copy(libraryDirectStart = v) } }
        SeeAlso(SeeAlsoLink(stringResource(Res.string.settings_filters), SettingsTarget.Page(SettingsPageId.FILTERS)))
    }
}

@Composable
fun AppsViewSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(Res.string.settings_apps_view)) {
        TileSettings(settings.appsLayout, { l -> update { it.copy(appsLayout = l) } }, apps = true)
        SeeAlso(SeeAlsoLink(stringResource(Res.string.settings_filters), SettingsTarget.Page(SettingsPageId.APP_FILTERS)))
    }
}

/** Which tab the launcher opens with (first row of the settings overview). */
@Composable
fun StartTabRow(initialFocus: Boolean = false) {
    val repo = io.github.thecodingdad.gamelodge.LocalContainer.current.settings
    val settings by repo.settings.collectAsState(LauncherSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Apps only while there are apps (the tab is hidden otherwise).
    val appsShown = io.github.thecodingdad.gamelodge.ui.MainTab.APPS !in io.github.thecodingdad.gamelodge.ui.LocalHiddenTabs.current
    val startTabs = StartTab.entries.filter { it != StartTab.APPS || appsShown }
    ChoiceRow(
        stringResource(Res.string.setting_start_tab), startTabs, settings.startTab.takeIf { it in startTabs } ?: StartTab.HOME,
        label = {
            stringResource(
                when (it) {
                    StartTab.HOME -> Res.string.tab_home
                    StartTab.LIBRARY -> Res.string.tab_library
                    StartTab.APPS -> Res.string.tab_apps
                },
            )
        },
        onSelect = { v -> scope.launch { repo.update { it.copy(startTab = v) } } },
        subtitle = stringResource(Res.string.setting_start_tab_sub),
        initialFocus = initialFocus,
    )
}

/** Theme, accent colours, how tiles look in every tab, motion and scale. */
/** A simple list entry with a ✕ that removes it right away (start tab rows, quick tiles, exceptions …). */
@Composable
fun RemovableRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onRemove: () -> Unit,
    removeLabel: String = stringResource(Res.string.home_row_remove),
    leading: (@Composable () -> Unit)? = null,
    initialFocus: Boolean = false,
    /** Set: the row (left of ✕) opens the entry's own settings. */
    onClick: (() -> Unit)? = null,
) {
    val colors = LauncherTheme.colors
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(16.dp))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: colors.surface)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> androidx.compose.material3.Icon(icon, null, tint = colors.accent, modifier = Modifier.size(26.dp))
        }
        val texts: @Composable () -> Unit = {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = if (leading != null || icon != null) 16.dp else 0.dp, top = 10.dp, bottom = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted) }
            }
        }
        if (onClick != null) {
            io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent, focusScale = 1f, glow = false,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) { texts() }
        } else {
            Box(Modifier.weight(1f)) { texts() }
        }
        io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
            onClick = onRemove, shape = androidx.compose.foundation.shape.CircleShape, color = colors.surfaceRaised,
            modifier = Modifier.size(48.dp).restoreFocusIf(initialFocus),
        ) {
            androidx.compose.material3.Icon(
                Icons.Filled.Close, removeLabel, tint = colors.onSurface,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center).size(22.dp),
            )
        }
    }
}

@Composable
fun ThemeSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
) {
    val device = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.deviceDisplayOptions
    SettingsPage(stringResource(Res.string.settings_theme)) {
        PictureChoiceRow(
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
            picture = { ThemePicture(it) },
            initialFocus = true,
        )
        ChoiceRow(
            stringResource(Res.string.setting_accent), AccentMode.entries, settings.accentMode,
            label = { stringResource(if (it == AccentMode.COVER) Res.string.accent_cover else Res.string.accent_fixed) },
            onSelect = { v -> update { it.copy(accentMode = v) } },
        )
        var picker by androidx.compose.runtime.remember { mutableStateOf(false) }
        AccentSwatchRow(
            stringResource(Res.string.setting_accent_color), settings.accentColor,
            onPick = { c -> update { it.copy(accentColor = c) } }, onMore = { picker = true },
            subtitle = if (settings.accentMode == AccentMode.COVER) stringResource(Res.string.setting_accent_color_cover_sub) else null,
        )
        if (picker) AccentColorPicker(settings.accentColor, { c -> update { it.copy(accentColor = c) } }, { picker = false })
        // Accented texts (group headers): derived from the accent unless chosen.
        var textPicker by androidx.compose.runtime.remember { mutableStateOf(false) }
        val textArgb = LauncherTheme.colors.accentText.let { c ->
            ((c.alpha * 255).toInt() shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        }
        NavRow(
            stringResource(Res.string.setting_accent_text_color), { textPicker = true },
            subtitle = if (settings.accentTextColor == null) stringResource(Res.string.accent_text_auto) else null,
            trailingContent = { ColorDot(textArgb) },
        )
        if (textPicker) {
            AccentColorPicker(
                settings.accentTextColor ?: textArgb, { c -> update { it.copy(accentTextColor = c) } }, { textPicker = false },
                title = stringResource(Res.string.setting_accent_text_color),
                onAutomatic = { update { it.copy(accentTextColor = null) } }, automatic = settings.accentTextColor == null,
            )
        }

        SectionHeader(stringResource(Res.string.section_tiles))
        ChoiceRow(
            stringResource(Res.string.setting_focus_zoom), FocusZoom.entries, settings.focusZoom,
            label = {
                stringResource(
                    when (it) {
                        FocusZoom.NONE -> Res.string.zoom_none
                        FocusZoom.LIGHT -> Res.string.zoom_light
                        FocusZoom.STRONG -> Res.string.zoom_strong
                    },
                )
            },
            onSelect = { v -> update { it.copy(focusZoom = v) } },
            subtitle = stringResource(Res.string.setting_focus_zoom_sub),
        )
        SwitchRow(stringResource(Res.string.setting_focus_glow), settings.focusGlow, { v -> update { it.copy(focusGlow = v) } })
        SwitchRow(
            stringResource(Res.string.setting_dim_not_installed), settings.dimNotInstalled, { v -> update { it.copy(dimNotInstalled = v) } },
            subtitle = stringResource(Res.string.setting_dim_not_installed_sub),
        )

        SectionHeader(stringResource(Res.string.section_motion))
        // Switched on again (from neither): games matched meanwhile get their animated artwork looked up.
        fun animated(tiles: Boolean, hero: Boolean) = update {
            val wasOn = it.animatedArtwork
            it.copy(animatedTiles = tiles, animatedHero = hero, animatedBackfillDone = it.animatedBackfillDone && (wasOn || !(tiles || hero)))
        }
        // Off, on the focused tile, or on every tile shown.
        ChoiceRow(
            stringResource(Res.string.setting_animated_tiles), listOf(0, 1, 2),
            if (!settings.animatedTiles) 0 else if (settings.animatedTilesAlways) 2 else 1,
            label = {
                stringResource(
                    when (it) {
                        0 -> Res.string.off
                        1 -> Res.string.setting_animated_tiles_focus
                        else -> Res.string.setting_animated_tiles_always
                    },
                )
            },
            onSelect = { v ->
                animated(v > 0, settings.animatedHero)
                if (v > 0) update { it.copy(animatedTilesAlways = v == 2) }
            },
            subtitle = stringResource(Res.string.setting_animated_tiles_sub),
        )
        SwitchRow(
            stringResource(Res.string.setting_animated_hero), settings.animatedHero, { v -> animated(settings.animatedTiles, v) },
            subtitle = stringResource(Res.string.setting_animated_hero_sub),
        )
        if (settings.animatedArtwork && device) {
            SwitchRow(
                stringResource(Res.string.setting_animated_mobile), settings.animatedOnMobileData, { v -> update { it.copy(animatedOnMobileData = v) } },
                subtitle = stringResource(Res.string.setting_animated_mobile_sub),
            )
        }
        SwitchRow(
            stringResource(Res.string.setting_reduce_motion), settings.reduceMotion, { v -> update { it.copy(reduceMotion = v) } },
            subtitle = stringResource(Res.string.setting_reduce_motion_sub),
        )
        SliderRow(
            stringResource(Res.string.setting_ui_scale), settings.uiScale, 60..130, 5,
            { v -> update { it.copy(uiScale = v) } }, { "$it %" },
        )
    }
}

/** Screen saver, system bars and the TV output. */
@Composable
fun DisplaySettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    externalDisplay: io.github.thecodingdad.gamelodge.system.ExternalDisplayInfo? = null,
) {
    // Navigation bar, TV output and mobile data only exist on a handheld.
    val device = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.deviceDisplayOptions
    SettingsPage(stringResource(Res.string.settings_display)) {
        if (device) {
            SectionHeader(stringResource(Res.string.section_system_bars))
            SwitchRow(
                stringResource(Res.string.setting_hide_nav_bar), settings.hideNavigationBar, { v -> update { it.copy(hideNavigationBar = v) } },
                subtitle = stringResource(Res.string.setting_hide_nav_bar_sub), initialFocus = true,
            )
        }
        SectionHeader(stringResource(Res.string.section_screensaver))
        ChoiceRow(
            stringResource(Res.string.setting_screensaver), listOf(0, 2, 5, 10, 20), settings.screensaverMinutes,
            label = { if (it == 0) stringResource(Res.string.off) else stringResource(Res.string.minutes_short, it) },
            onSelect = { v -> update { it.copy(screensaverMinutes = v) } },
            initialFocus = !device,
        )
        if (settings.screensaverMinutes > 0) {
            SwitchRow(
                stringResource(Res.string.setting_ken_burns), settings.screensaverKenBurns, { v -> update { it.copy(screensaverKenBurns = v) } },
                subtitle = stringResource(Res.string.setting_ken_burns_sub),
            )
            io.github.thecodingdad.gamelodge.ui.components.LauncherButton(
                stringResource(Res.string.screensaver_show), { io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.show() },
                icon = androidx.compose.material.icons.Icons.Filled.Visibility,
            )
        }
        if (!device) return@SettingsPage
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
            fun text(m: io.github.thecodingdad.gamelodge.system.TvMode) = "${m.width}×${m.height} @ ${m.refreshRate.roundToInt()} Hz"
            val autoMode = io.github.thecodingdad.gamelodge.system.TvModes.auto(modes)
            val autoLabel = autoMode?.let { stringResource(Res.string.setting_tv_resolution_auto_mode, text(it)) } ?: stringResource(Res.string.setting_tv_resolution_auto)
            ChoiceRow(
                stringResource(Res.string.setting_tv_resolution), listOf("") + modes.map { it.key }.distinct(),
                settings.tvMode.takeIf { k -> k.isEmpty() || modes.any { it.key == k } } ?: "",
                label = { k -> modes.firstOrNull { it.key == k }?.let(::text) ?: autoLabel },
                onSelect = { v -> update { it.copy(tvMode = v) } },
                subtitle = stringResource(if (externalDisplay == null) Res.string.setting_tv_resolution_none else Res.string.setting_tv_resolution_sub),
            )
            // The TV keeps another mode (some devices only switch it in their own settings).
            val wanted = io.github.thecodingdad.gamelodge.system.TvModes.resolve(settings.tvMode, modes)
            val tv = externalDisplay
            if (tv != null && wanted != null && (wanted.width != tv.width || wanted.height != tv.height || wanted.refreshRate.roundToInt() != tv.refreshRate.roundToInt())) {
                val running = io.github.thecodingdad.gamelodge.system.TvMode(-1, tv.width, tv.height, tv.refreshRate)
                Text(
                    stringResource(Res.string.tv_mode_hint_text, text(wanted), text(running)),
                    style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.accentText,
                )
                io.github.thecodingdad.gamelodge.ui.PlatformUi.current.openTvOutputSettings?.let { open ->
                    NavRow(stringResource(Res.string.tv_mode_hint_open), open, subtitle = stringResource(Res.string.tv_mode_hint_odin), icon = Icons.Filled.Monitor)
                }
            }
            SwitchRow(
                stringResource(Res.string.setting_tv_render_1080), settings.tvRender1080, { v -> update { it.copy(tvRender1080 = v) } },
                subtitle = stringResource(Res.string.setting_tv_render_1080_sub),
            )
        }
        SwitchRow(
            stringResource(Res.string.setting_tv_scale_auto), settings.tvScaleAuto, { v -> update { it.copy(tvScaleAuto = v) } },
            subtitle = stringResource(Res.string.setting_tv_scale_auto_sub),
        )
        if (settings.tvScaleAuto) {
            SliderRow(
                stringResource(Res.string.setting_tv_scale), settings.tvScale, 40..100, 5,
                { v -> update { it.copy(tvScale = v) } }, { "$it %" },
            )
        }
    }
}


/** The languages offered in the launcher; "system" follows the device. Used by the settings and the wizard. */
val LanguageTags = listOf("system", "en", "de", "es", "fr", "it", "pt-BR")

/** Display name of a language tag ("system" localised, the rest in their own language). */
@Composable
fun languageLabel(tag: String): String = when (tag) {
    "en" -> "English"
    "de" -> "Deutsch"
    "es" -> "Español"
    "fr" -> "Français"
    "it" -> "Italiano"
    "pt-BR" -> "Português (Brasil)"
    else -> stringResource(Res.string.language_system)
}

@Composable
fun LanguageSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    SettingsPage(stringResource(Res.string.settings_language)) {
        ChoiceRow(
            stringResource(Res.string.settings_language), LanguageTags, settings.language,
            label = { languageLabel(it) },
            onSelect = { v ->
                update { it.copy(language = v) }
                io.github.thecodingdad.gamelodge.ui.PlatformUi.current.applyLanguage(v)
            },
            initialFocus = true,
        )
    }
}

/** A on a game in this tab starts it right away (its details then via the menu). */
@Composable
private fun DirectStartRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(
        stringResource(Res.string.setting_games_direct_start), checked, onChange,
        subtitle = stringResource(Res.string.setting_games_direct_start_sub),
    )
}
