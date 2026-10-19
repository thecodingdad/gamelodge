package io.github.thecodingdad.gamelodge.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Tab shown first after start and on the second Home press. */
enum class StartTab { HOME, LIBRARY, APPS }
enum class ThemeMode { DARK, BLACK, LIGHT }
enum class AccentMode { FIXED, COVER }
enum class CardSize { SMALL, MEDIUM, LARGE }
/** Shape of game tiles: cover (2:3), landscape (16:9) or square. */
enum class TileFormat { PORTRAIT, LANDSCAPE, SQUARE }
enum class ButtonLayout { XBOX, NINTENDO }
enum class NetworkCheckMode { OFF, OUTSIDE_HOME, ALWAYS }
enum class AutoVpnTrigger { HOST_UNREACHABLE, OUTSIDE_HOME }

@kotlinx.serialization.Serializable
data class LauncherSettings(
    // Appearance
    val startTab: StartTab = StartTab.HOME,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentMode: AccentMode = AccentMode.FIXED,
    val accentColor: Int = 0xFF7C9CFF.toInt(),
    /** Colour of accented texts (group headers); null: derived from the accent, readable on the background. */
    val accentTextColor: Int? = null,
    /** Hosts removed with their games kept (hidden; back with the next pairing of the host). */
    val detachedHosts: Set<String> = emptySet(),
    val cardSize: CardSize = CardSize.MEDIUM,
    /** Game tile shape on the start tab and in the library (apps on the start tab stay landscape). */
    val homeTileFormat: TileFormat = TileFormat.PORTRAIT,
    val libraryTileFormat: TileFormat = TileFormat.PORTRAIT,
    /** Landscape / square tiles were looked up once for the games matched before the format was chosen. */
    val landscapeBackfillDone: Boolean = false,
    val squareBackfillDone: Boolean = false,
    val reduceMotion: Boolean = false,
    val animatedArtwork: Boolean = true,
    /** Animated covers on tiles: only the focused one (controller) or all visible ones. */
    val animatedTilesAlways: Boolean = false,
    /** Animated artwork is large: over metered connections (mobile data) only when allowed. */
    val animatedOnMobileData: Boolean = false,
    /** Animated artwork of games matched before it was switched on has been looked up. */
    val animatedBackfillDone: Boolean = false,
    val showHaRowOnHome: Boolean = true,
    /** Order of the start tab rows (filter ids, "ha" for the smart home row); empty = filter order. */
    val homeRowOrder: List<String> = emptyList(),
    // Input & feedback
    val buttonLayout: ButtonLayout = ButtonLayout.XBOX,
    /** Controllers with their own layout ([io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo.key] → layout). */
    val controllerLayouts: Map<String, ButtonLayout> = emptyMap(),
    val uiSounds: Boolean = true,
    val uiSoundVolume: Int = 60,
    val haptics: Boolean = true,
    val soundStyle: String = "MUFFLED",
    val soundPitch: Int = 0,
    /** UiSound names that stay silent. */
    val mutedSounds: Set<String> = emptySet(),
    val hapticStrength: String = "MEDIUM",
    val hapticOnFocus: Boolean = true,
    // System
    val launcherMode: Boolean = false,
    val screensaverMinutes: Int = 5,
    val language: String = "system",
    // Library & metadata
    val autoMetadata: Boolean = true,
    val showHiddenApps: Boolean = false,
    // Streaming
    val networkCheck: NetworkCheckMode = NetworkCheckMode.OUTSIDE_HOME,
    // Home Assistant (tokens live in SecretStore)
    val haUrl: String = "",
    // VPN
    val autoVpnTriggers: Set<AutoVpnTrigger> = emptySet(),
    val homeSsids: Set<String> = emptySet(),
    val vpnDisconnectAtHome: Boolean = true,
    val activeTunnel: String = "",
    // Backup
    val backupFolderUri: String = "",
    val autoBackup: Boolean = false,
    val backupKeepCount: Int = 7,
    // Onboarding
    val onboardingDone: Boolean = false,
    /** Default library filters were created (they may be deleted afterwards). */
    val filtersSeeded: Boolean = false,
    /** The "not installed" default filter (added later) was created for existing installs. */
    val notInstalledFilterSeeded: Boolean = false,
    val emulationFilterSeeded: Boolean = false,
    /** Hide Android's navigation bar / gesture handle while the launcher is shown. */
    val hideNavigationBar: Boolean = false,
    /** Show the button hint bar (only while a controller is connected). */
    val showHintBar: Boolean = true,
    /** A / tap on a game starts (or installs) it instead of opening its detail page. */
    val gamesDirectStart: Boolean = false,
    /** Narrow handle at the right screen edge that opens the quick menu over other apps. */
    val quickMenuEdgeHandle: Boolean = false,
    /** Emulators and ROM folders ([io.github.thecodingdad.gamelodge.data.emulation.EmulationConfig] as JSON). */
    val emulation: String = "",
    /** PC: emulators and ROM folders (desktop's own format, JSON). */
    val pcEmulation: String = "",
    /** PC: games and apps added by hand (JSON). */
    val pcCustomGames: String = "",
    /** PC: changed start commands of scanned games (JSON, entry id → override). */
    val pcLaunchOverrides: String = "",
    /** Home Assistant actions before/after games ([io.github.thecodingdad.gamelodge.ha.GameActionSettings] as JSON). */
    val gameActions: String = "",
    /** Version of the automatic platform rules the library was last re-evaluated with. */
    val platformRule: Int = 0,
    /** AYN Odin: put every external controller on Odin Mapping's blacklist (keeps battery/rumble). */
    val odinKeepControllers: Boolean = true,
    /** Tiles in the quick menu, in order (see QuickTile ids; apps as "app:<package>"). */
    val quickTiles: List<String> = listOf("controllers", "hosts", "vpn", "wifi", "bluetooth", "airplane"),
    /** Order of the metadata sources and which are off (see MetadataSources). */
    val metadataSources: List<String> = emptyList(),
    /** Desktop: stores whose games are left out of the library ("steam", "epic" …). */
    val disabledGameSources: Set<String> = emptySet(),
    /** Controller button combinations (key codes) for Android Home and the quick menu; empty = off. */
    val comboHome: Set<Int> = emptySet(),
    val comboQuickMenu: Set<Int> = emptySet(),
    /** Shrink the UI while a TV/monitor is attached (the layout is sized for the handheld). */
    val tvScaleAuto: Boolean = true,
    /** UI size on an external display, percent of the handheld size. */
    val tvScale: Int = 70,
    /** Run the launcher on the TV itself (native resolution) instead of mirroring the handheld. */
    val tvLauncher: Boolean = true,
    /** Display mode requested on the TV; 0 = automatic (the TV's native mode). */
    val tvModeId: Int = 0,
    /** UI size on the device's own display, percent. */
    val uiScale: Int = 100,
    /** Separate app filters were created (and shared filters turned into game filters). */
    val appFiltersSeeded: Boolean = false,
)

/** [store]: the "launcher_settings" preferences file of the platform. */
class SettingsRepository(private val store: DataStore<Preferences>) {

    private object K {
        val startTab = stringPreferencesKey("start_tab")
        val themeMode = stringPreferencesKey("theme_mode")
        val accentMode = stringPreferencesKey("accent_mode")
        val accentColor = intPreferencesKey("accent_color")
        val accentTextColor = intPreferencesKey("accent_text_color")
        val detachedHosts = stringSetPreferencesKey("detached_hosts")
        val cardSize = stringPreferencesKey("card_size")
        val homeTileFormat = stringPreferencesKey("home_tile_format")
        val libraryTileFormat = stringPreferencesKey("library_tile_format")
        val landscapeBackfillDone = booleanPreferencesKey("landscape_backfill_done")
        val squareBackfillDone = booleanPreferencesKey("square_backfill_done")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val animatedArtwork = booleanPreferencesKey("animated_artwork")
        val animatedTilesAlways = booleanPreferencesKey("animated_tiles_always")
        val animatedOnMobileData = booleanPreferencesKey("animated_on_mobile_data")
        val animatedBackfillDone = booleanPreferencesKey("animated_backfill_done")
        val showHaRowOnHome = booleanPreferencesKey("show_ha_row_on_home")
        val homeRowOrder = stringPreferencesKey("home_row_order")
        val buttonLayout = stringPreferencesKey("button_layout")
        val controllerLayouts = stringPreferencesKey("controller_layouts")
        val uiSounds = booleanPreferencesKey("ui_sounds")
        val uiSoundVolume = intPreferencesKey("ui_sound_volume")
        val haptics = booleanPreferencesKey("haptics")
        val soundStyle = stringPreferencesKey("sound_style")
        val soundPitch = intPreferencesKey("sound_pitch")
        val mutedSounds = stringPreferencesKey("muted_sounds")
        val hapticStrength = stringPreferencesKey("haptic_strength")
        val hapticOnFocus = booleanPreferencesKey("haptic_on_focus")
        val launcherMode = booleanPreferencesKey("launcher_mode")
        val screensaverMinutes = intPreferencesKey("screensaver_minutes")
        val language = stringPreferencesKey("language")
        val autoMetadata = booleanPreferencesKey("auto_metadata")
        val showHiddenApps = booleanPreferencesKey("show_hidden_apps")
        val networkCheck = stringPreferencesKey("network_check")
        val haUrl = stringPreferencesKey("ha_url")
        val autoVpnTriggers = stringSetPreferencesKey("auto_vpn_triggers")
        val homeSsids = stringSetPreferencesKey("home_ssids")
        val vpnDisconnectAtHome = booleanPreferencesKey("vpn_disconnect_at_home")
        val activeTunnel = stringPreferencesKey("active_tunnel")
        val backupFolderUri = stringPreferencesKey("backup_folder_uri")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val backupKeepCount = intPreferencesKey("backup_keep_count")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val filtersSeeded = booleanPreferencesKey("filters_seeded")
        val notInstalledFilterSeeded = booleanPreferencesKey("not_installed_filter_seeded")
        val emulationFilterSeeded = booleanPreferencesKey("emulation_filter_seeded")
        val hideNavigationBar = booleanPreferencesKey("hide_navigation_bar")
        val showHintBar = booleanPreferencesKey("show_hint_bar")
        val gamesDirectStart = booleanPreferencesKey("games_direct_start")
        val quickMenuEdgeHandle = booleanPreferencesKey("quick_menu_edge_handle")
        val emulation = stringPreferencesKey("emulation")
        val pcEmulation = stringPreferencesKey("pc_emulation")
        val pcCustomGames = stringPreferencesKey("pc_custom_games")
        val pcLaunchOverrides = stringPreferencesKey("pc_launch_overrides")
        val gameActions = stringPreferencesKey("game_actions")
        val platformRule = intPreferencesKey("platform_rule")
        val odinKeepControllers = booleanPreferencesKey("odin_keep_controllers")
        val quickTiles = stringPreferencesKey("quick_tiles")
        val metadataSources = stringPreferencesKey("metadata_sources")
        val disabledGameSources = stringSetPreferencesKey("disabled_game_sources")
        val comboHome = stringPreferencesKey("combo_home")
        val comboQuickMenu = stringPreferencesKey("combo_quick_menu")
        val tvScaleAuto = booleanPreferencesKey("tv_scale_auto")
        val tvScale = intPreferencesKey("tv_scale")
        val tvLauncher = booleanPreferencesKey("tv_launcher")
        val tvModeId = intPreferencesKey("tv_mode_id")
        val uiScale = intPreferencesKey("ui_scale")
        val appFiltersSeeded = booleanPreferencesKey("app_filters_seeded")
    }

    val settings: Flow<LauncherSettings> = store.data.map { it.toSettings() }

    suspend fun current(): LauncherSettings = settings.first()

    suspend fun update(transform: (LauncherSettings) -> LauncherSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings(): LauncherSettings {
        val d = LauncherSettings()
        return LauncherSettings(
            startTab = enumOr(this[K.startTab], d.startTab),
            themeMode = enumOr(this[K.themeMode], d.themeMode),
            accentMode = enumOr(this[K.accentMode], d.accentMode),
            accentColor = this[K.accentColor] ?: d.accentColor,
            accentTextColor = this[K.accentTextColor],
            detachedHosts = this[K.detachedHosts] ?: d.detachedHosts,
            cardSize = enumOr(this[K.cardSize], d.cardSize),
            homeTileFormat = enumOr(this[K.homeTileFormat], d.homeTileFormat),
            libraryTileFormat = enumOr(this[K.libraryTileFormat], d.libraryTileFormat),
            landscapeBackfillDone = this[K.landscapeBackfillDone] ?: d.landscapeBackfillDone,
            squareBackfillDone = this[K.squareBackfillDone] ?: d.squareBackfillDone,
            reduceMotion = this[K.reduceMotion] ?: d.reduceMotion,
            animatedArtwork = this[K.animatedArtwork] ?: d.animatedArtwork,
            animatedTilesAlways = this[K.animatedTilesAlways] ?: d.animatedTilesAlways,
            animatedOnMobileData = this[K.animatedOnMobileData] ?: d.animatedOnMobileData,
            animatedBackfillDone = this[K.animatedBackfillDone] ?: d.animatedBackfillDone,
            showHaRowOnHome = this[K.showHaRowOnHome] ?: d.showHaRowOnHome,
            homeRowOrder = this[K.homeRowOrder]?.split(',')?.filter { it.isNotEmpty() } ?: d.homeRowOrder,
            buttonLayout = enumOr(this[K.buttonLayout], d.buttonLayout),
            // "key\tLAYOUT" lines (controller names may hold any character but tabs and line breaks).
            controllerLayouts = this[K.controllerLayouts]?.lines()?.mapNotNull { line ->
                val key = line.substringBeforeLast('\t', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                runCatching { ButtonLayout.valueOf(line.substringAfterLast('\t')) }.getOrNull()?.let { key to it }
            }?.toMap() ?: d.controllerLayouts,
            uiSounds = this[K.uiSounds] ?: d.uiSounds,
            uiSoundVolume = this[K.uiSoundVolume] ?: d.uiSoundVolume,
            haptics = this[K.haptics] ?: d.haptics,
            soundStyle = this[K.soundStyle] ?: d.soundStyle,
            soundPitch = this[K.soundPitch] ?: d.soundPitch,
            mutedSounds = this[K.mutedSounds]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: d.mutedSounds,
            hapticStrength = this[K.hapticStrength] ?: d.hapticStrength,
            hapticOnFocus = this[K.hapticOnFocus] ?: d.hapticOnFocus,
            launcherMode = this[K.launcherMode] ?: d.launcherMode,
            screensaverMinutes = this[K.screensaverMinutes] ?: d.screensaverMinutes,
            language = this[K.language] ?: d.language,
            autoMetadata = this[K.autoMetadata] ?: d.autoMetadata,
            showHiddenApps = this[K.showHiddenApps] ?: d.showHiddenApps,
            networkCheck = enumOr(this[K.networkCheck], d.networkCheck),
            haUrl = this[K.haUrl] ?: d.haUrl,
            autoVpnTriggers = this[K.autoVpnTriggers]
                ?.mapNotNullTo(HashSet()) { name -> AutoVpnTrigger.entries.firstOrNull { it.name == name } }
                ?: d.autoVpnTriggers,
            homeSsids = this[K.homeSsids] ?: d.homeSsids,
            vpnDisconnectAtHome = this[K.vpnDisconnectAtHome] ?: d.vpnDisconnectAtHome,
            activeTunnel = this[K.activeTunnel] ?: d.activeTunnel,
            backupFolderUri = this[K.backupFolderUri] ?: d.backupFolderUri,
            autoBackup = this[K.autoBackup] ?: d.autoBackup,
            backupKeepCount = this[K.backupKeepCount] ?: d.backupKeepCount,
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            filtersSeeded = this[K.filtersSeeded] ?: d.filtersSeeded,
            notInstalledFilterSeeded = this[K.notInstalledFilterSeeded] ?: d.notInstalledFilterSeeded,
            emulationFilterSeeded = this[K.emulationFilterSeeded] ?: d.emulationFilterSeeded,
            hideNavigationBar = this[K.hideNavigationBar] ?: d.hideNavigationBar,
            showHintBar = this[K.showHintBar] ?: d.showHintBar,
            gamesDirectStart = this[K.gamesDirectStart] ?: d.gamesDirectStart,
            quickMenuEdgeHandle = this[K.quickMenuEdgeHandle] ?: d.quickMenuEdgeHandle,
            emulation = this[K.emulation] ?: d.emulation,
            pcEmulation = this[K.pcEmulation] ?: d.pcEmulation,
            pcCustomGames = this[K.pcCustomGames] ?: d.pcCustomGames,
            pcLaunchOverrides = this[K.pcLaunchOverrides] ?: d.pcLaunchOverrides,
            gameActions = this[K.gameActions] ?: d.gameActions,
            platformRule = this[K.platformRule] ?: d.platformRule,
            odinKeepControllers = this[K.odinKeepControllers] ?: d.odinKeepControllers,
            quickTiles = this[K.quickTiles]?.split(',')?.filter { it.isNotBlank() } ?: d.quickTiles,
            metadataSources = this[K.metadataSources]?.split(',')?.filter { it.isNotBlank() } ?: d.metadataSources,
            disabledGameSources = this[K.disabledGameSources] ?: d.disabledGameSources,
            comboHome = this[K.comboHome]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.comboHome,
            comboQuickMenu = this[K.comboQuickMenu]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.comboQuickMenu,
            tvScaleAuto = this[K.tvScaleAuto] ?: d.tvScaleAuto,
            tvScale = this[K.tvScale] ?: d.tvScale,
            tvLauncher = this[K.tvLauncher] ?: d.tvLauncher,
            tvModeId = this[K.tvModeId] ?: d.tvModeId,
            uiScale = this[K.uiScale] ?: d.uiScale,
            appFiltersSeeded = this[K.appFiltersSeeded] ?: d.appFiltersSeeded,
        )
    }

    private fun MutablePreferences.write(s: LauncherSettings) {
        this[K.startTab] = s.startTab.name
        this[K.themeMode] = s.themeMode.name
        this[K.accentMode] = s.accentMode.name
        this[K.accentColor] = s.accentColor
        s.accentTextColor?.let { this[K.accentTextColor] = it } ?: remove(K.accentTextColor)
        this[K.detachedHosts] = s.detachedHosts
        this[K.cardSize] = s.cardSize.name
        this[K.homeTileFormat] = s.homeTileFormat.name
        this[K.libraryTileFormat] = s.libraryTileFormat.name
        this[K.landscapeBackfillDone] = s.landscapeBackfillDone
        this[K.squareBackfillDone] = s.squareBackfillDone
        this[K.reduceMotion] = s.reduceMotion
        this[K.animatedArtwork] = s.animatedArtwork
        this[K.animatedTilesAlways] = s.animatedTilesAlways
        this[K.animatedOnMobileData] = s.animatedOnMobileData
        this[K.animatedBackfillDone] = s.animatedBackfillDone
        this[K.showHaRowOnHome] = s.showHaRowOnHome
        this[K.homeRowOrder] = s.homeRowOrder.joinToString(",")
        this[K.buttonLayout] = s.buttonLayout.name
        this[K.controllerLayouts] = s.controllerLayouts.entries.joinToString("\n") { "${it.key}\t${it.value.name}" }
        this[K.uiSounds] = s.uiSounds
        this[K.uiSoundVolume] = s.uiSoundVolume
        this[K.haptics] = s.haptics
        this[K.soundStyle] = s.soundStyle
        this[K.soundPitch] = s.soundPitch
        this[K.mutedSounds] = s.mutedSounds.joinToString(",")
        this[K.hapticStrength] = s.hapticStrength
        this[K.hapticOnFocus] = s.hapticOnFocus
        this[K.launcherMode] = s.launcherMode
        this[K.screensaverMinutes] = s.screensaverMinutes
        this[K.language] = s.language
        this[K.autoMetadata] = s.autoMetadata
        this[K.showHiddenApps] = s.showHiddenApps
        this[K.networkCheck] = s.networkCheck.name
        this[K.haUrl] = s.haUrl
        this[K.autoVpnTriggers] = s.autoVpnTriggers.mapTo(HashSet()) { it.name }
        this[K.homeSsids] = s.homeSsids
        this[K.vpnDisconnectAtHome] = s.vpnDisconnectAtHome
        this[K.activeTunnel] = s.activeTunnel
        this[K.backupFolderUri] = s.backupFolderUri
        this[K.autoBackup] = s.autoBackup
        this[K.backupKeepCount] = s.backupKeepCount
        this[K.onboardingDone] = s.onboardingDone
        this[K.filtersSeeded] = s.filtersSeeded
        this[K.notInstalledFilterSeeded] = s.notInstalledFilterSeeded
        this[K.emulationFilterSeeded] = s.emulationFilterSeeded
        this[K.hideNavigationBar] = s.hideNavigationBar
        this[K.showHintBar] = s.showHintBar
        this[K.gamesDirectStart] = s.gamesDirectStart
        this[K.quickMenuEdgeHandle] = s.quickMenuEdgeHandle
        this[K.emulation] = s.emulation
        this[K.pcEmulation] = s.pcEmulation
        this[K.pcCustomGames] = s.pcCustomGames
        this[K.pcLaunchOverrides] = s.pcLaunchOverrides
        this[K.gameActions] = s.gameActions
        this[K.platformRule] = s.platformRule
        this[K.odinKeepControllers] = s.odinKeepControllers
        this[K.quickTiles] = s.quickTiles.joinToString(",")
        this[K.metadataSources] = s.metadataSources.joinToString(",")
        this[K.disabledGameSources] = s.disabledGameSources
        this[K.comboHome] = s.comboHome.joinToString(",")
        this[K.comboQuickMenu] = s.comboQuickMenu.joinToString(",")
        this[K.tvScaleAuto] = s.tvScaleAuto
        this[K.tvScale] = s.tvScale
        this[K.tvLauncher] = s.tvLauncher
        this[K.tvModeId] = s.tvModeId
        this[K.uiScale] = s.uiScale
        this[K.appFiltersSeeded] = s.appFiltersSeeded
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default
}
