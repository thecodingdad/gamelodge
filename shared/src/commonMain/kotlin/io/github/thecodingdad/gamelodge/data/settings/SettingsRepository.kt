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
/** Where a tile shows its title. */
enum class TileTitle { OFF, BELOW, ON_TILE }
/** Extra line on a tile. */
enum class TileInfo { NONE, PLAYTIME, LAST_PLAYED, PROGRESS }
/** Small marks on a tile: favourite, where it runs (this device, emulator, host), not installed. */
enum class TileBadge { FAVORITE, LOCAL, ROM, HOST, NOT_INSTALLED }
/** Apps tab: icon grid or landscape banners. */
enum class AppsStyle { ICONS, BANNERS }
enum class TileCorners { SHARP, SLIGHT, ROUND }
enum class FocusZoom { NONE, LIGHT, STRONG }

/** How one tab (start, library, apps) shows its tiles. */
@kotlinx.serialization.Serializable
data class TabLayout(
    val cardSize: CardSize = CardSize.MEDIUM,
    /** Games only (apps on the start tab stay landscape). */
    val format: TileFormat = TileFormat.PORTRAIT,
    val title: TileTitle = TileTitle.BELOW,
    val info: TileInfo = TileInfo.NONE,
    val badges: Set<TileBadge> = TileBadge.entries.toSet(),
    /** Apps tab only. */
    val appsStyle: AppsStyle = AppsStyle.ICONS,
    val corners: TileCorners = TileCorners.SLIGHT,
) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        /** [formerCorners]: the corners set for all tabs before; taken by layouts saved without own corners. */
        fun decode(text: String?, formerCorners: String? = null): TabLayout? = text?.let {
            runCatching { json.decodeFromString<TabLayout>(it) }.getOrNull()?.let { layout ->
                val former = formerCorners?.let { name -> TileCorners.entries.firstOrNull { c -> c.name == name } }
                if (former != null && "\"corners\"" !in text) layout.copy(corners = former) else layout
            }
        }
        fun encode(layout: TabLayout): String = json.encodeToString(layout)
    }
}
enum class NetworkCheckMode { OFF, OUTSIDE_HOME, ALWAYS }

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
    /** Tiles per tab: the start tab shows no titles (the hero above names the game). */
    val homeLayout: TabLayout = TabLayout(title = TileTitle.OFF),
    val libraryLayout: TabLayout = TabLayout(),
    val appsLayout: TabLayout = TabLayout(badges = emptySet()),
    /** Tiles in every tab: focus effect, not installed games dimmed. */
    val focusZoom: FocusZoom = FocusZoom.LIGHT,
    val focusGlow: Boolean = true,
    val dimNotInstalled: Boolean = true,
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
    /** Games whose stream uses another button layout than the controllers' (library item id). */
    val gameLayouts: Map<String, ButtonLayout> = emptyMap(),
    /** PC: button combinations of single controllers by name (Android keeps them per device). */
    val controllerCombos: Map<String, PadCombos> = emptyMap(),
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
    /** Connect the VPN when a stream starts and the host isn't reachable (before waking it). */
    val vpnOnStreamStart: Boolean = false,
    /** ... only outside [homeNetworks] (when any are set). */
    val vpnOnlyOutsideHome: Boolean = false,
    /** Home networks: addresses, subnets or ranges (see [io.github.thecodingdad.gamelodge.vpn.HomeNetworks]). */
    val homeNetworks: Set<String> = emptySet(),
    /** Disconnect after the stream when the launcher connected the VPN for it. */
    val vpnDisconnectAfterStream: Boolean = true,
    val activeTunnel: String = "",
    // Backup
    val backupFolderUri: String = "",
    val autoBackup: Boolean = false,
    val backupKeepCount: Int = 7,
    // Onboarding
    val onboardingDone: Boolean = false,
    /**
     * The setup wizard was finished (or this install had settings before it existed). A fresh
     * install starts it once; it can be run again from Settings › Data.
     */
    val setupDone: Boolean = false,
    /** Step the setup wizard is at while it runs (continues there after a restart); empty otherwise. */
    val setupStep: String = "",
    /** Backup chosen in the wizard's backup path, restored after pairing; empty otherwise. */
    val setupRestore: String = "",
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
    val quickTiles: List<String> = listOf("vpn", "wifi", "bluetooth", "airplane"),
    /** Volume slider in the quick menu's quick access tab. */
    val quickShowVolume: Boolean = true,
    /** Brightness slider in the quick menu's quick access tab. */
    val quickShowBrightness: Boolean = true,
    /** PC: audio output picker in the quick access tab. */
    val quickShowAudioOutput: Boolean = true,
    /** Order of the metadata sources and which are off (see MetadataSources). */
    val metadataSources: List<String> = emptyList(),
    /** Desktop: stores whose games are left out of the library ("steam", "epic" …). */
    val disabledGameSources: Set<String> = emptySet(),
    /**
     * Controller button combinations (Android key codes, [COMBO_HOLD] = held) to bring the launcher
     * to the front and for the quick menu; empty = off. Default: Guide pressed / Guide held.
     */
    val comboHome: Set<Int> = setOf(KEYCODE_GUIDE),
    val comboQuickMenu: Set<Int> = setOf(KEYCODE_GUIDE, COMBO_HOLD),
    /** Mouse emulation on / off (in the stream: the client's; elsewhere GameLodge's own). */
    val comboMouse: Set<Int> = setOf(KEYCODE_START, COMBO_HOLD),
    /** Performance overlay in the stream on / off. */
    val comboStats: Set<Int> = setOf(KEYCODE_SELECT, KEYCODE_L1, KEYCODE_R1, KEYCODE_X),
    /** Mouse emulation: pointer speed in percent of the default. */
    val mouseSpeed: Int = 100,
    /** Mouse emulation: how much slower the pointer is with the stick only slightly moved. */
    val mouseAcceleration: MouseAcceleration = MouseAcceleration.MEDIUM,
    /** The combinations' buttons reach the game / app as well. */
    val comboForward: Boolean = false,
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
        /** Before the per tab layouts: one card size for all, a tile format for start and library. */
        val cardSize = stringPreferencesKey("card_size")
        val homeTileFormat = stringPreferencesKey("home_tile_format")
        val libraryTileFormat = stringPreferencesKey("library_tile_format")
        val homeLayout = stringPreferencesKey("home_layout")
        val libraryLayout = stringPreferencesKey("library_layout")
        val appsLayout = stringPreferencesKey("apps_layout")
        /** Before the per tab corners: one setting for all tabs. */
        val tileCorners = stringPreferencesKey("tile_corners")
        val focusZoom = stringPreferencesKey("focus_zoom")
        val focusGlow = booleanPreferencesKey("focus_glow")
        val dimNotInstalled = booleanPreferencesKey("dim_not_installed")
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
        val gameLayouts = stringPreferencesKey("game_layouts")
        val controllerCombos = stringPreferencesKey("controller_combos")
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
        /** Before [vpnOnStreamStart]: any of its triggers switches the new setting on once. */
        val autoVpnTriggers = stringSetPreferencesKey("auto_vpn_triggers")
        val homeSsids = stringSetPreferencesKey("home_ssids")
        val vpnDisconnectAtHome = booleanPreferencesKey("vpn_disconnect_at_home")
        val vpnOnStreamStart = booleanPreferencesKey("vpn_on_stream_start")
        val vpnOnlyOutsideHome = booleanPreferencesKey("vpn_only_outside_home")
        val homeNetworks = stringSetPreferencesKey("home_networks")
        val vpnDisconnectAfterStream = booleanPreferencesKey("vpn_disconnect_after_stream")
        val activeTunnel = stringPreferencesKey("active_tunnel")
        val backupFolderUri = stringPreferencesKey("backup_folder_uri")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val backupKeepCount = intPreferencesKey("backup_keep_count")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val setupDone = booleanPreferencesKey("setup_done")
        val setupStep = stringPreferencesKey("setup_step")
        val setupRestore = stringPreferencesKey("setup_restore")
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
        val quickShowVolume = booleanPreferencesKey("quick_show_volume")
        val quickShowBrightness = booleanPreferencesKey("quick_show_brightness")
        val quickShowAudioOutput = booleanPreferencesKey("quick_show_audio_output")
        val metadataSources = stringPreferencesKey("metadata_sources")
        val disabledGameSources = stringSetPreferencesKey("disabled_game_sources")
        val comboHome = stringPreferencesKey("combo_home")
        val comboQuickMenu = stringPreferencesKey("combo_quick_menu")
        val comboForward = booleanPreferencesKey("combo_forward")
        val comboMouse = stringPreferencesKey("combo_mouse")
        val mouseSpeed = intPreferencesKey("mouse_speed")
        val mouseAcceleration = stringPreferencesKey("mouse_acceleration")
        val comboStats = stringPreferencesKey("combo_stats")
        // Set with the first save after the Guide defaults came: an empty combination stored
        // before only meant "never set" (every save writes all fields).
        val combosSet = booleanPreferencesKey("combos_set")
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
            homeLayout = TabLayout.decode(this[K.homeLayout], this[K.tileCorners])
                ?: d.homeLayout.copy(corners = enumOr(this[K.tileCorners], TileCorners.SLIGHT), cardSize = enumOr(this[K.cardSize], CardSize.MEDIUM), format = enumOr(this[K.homeTileFormat], TileFormat.PORTRAIT)),
            libraryLayout = TabLayout.decode(this[K.libraryLayout], this[K.tileCorners])
                ?: d.libraryLayout.copy(corners = enumOr(this[K.tileCorners], TileCorners.SLIGHT), cardSize = enumOr(this[K.cardSize], CardSize.MEDIUM), format = enumOr(this[K.libraryTileFormat], TileFormat.PORTRAIT)),
            appsLayout = TabLayout.decode(this[K.appsLayout], this[K.tileCorners]) ?: d.appsLayout.copy(corners = enumOr(this[K.tileCorners], TileCorners.SLIGHT), cardSize = enumOr(this[K.cardSize], CardSize.MEDIUM)),
            focusZoom = enumOr(this[K.focusZoom], d.focusZoom),
            focusGlow = this[K.focusGlow] ?: d.focusGlow,
            dimNotInstalled = this[K.dimNotInstalled] ?: d.dimNotInstalled,
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
            controllerCombos = this[K.controllerCombos]?.let { text ->
                runCatching { padCombosJson.decodeFromString<Map<String, PadCombos>>(text) }.getOrNull()
            } ?: d.controllerCombos,
            controllerLayouts = this[K.controllerLayouts]?.lines()?.mapNotNull { line ->
                val key = line.substringBeforeLast('\t', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                runCatching { ButtonLayout.valueOf(line.substringAfterLast('\t')) }.getOrNull()?.let { key to it }
            }?.toMap() ?: d.controllerLayouts,
            gameLayouts = this[K.gameLayouts]?.lines()?.mapNotNull { line ->
                val key = line.substringBeforeLast('\t', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                runCatching { ButtonLayout.valueOf(line.substringAfterLast('\t')) }.getOrNull()?.let { key to it }
            }?.toMap() ?: d.gameLayouts,
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
            vpnOnStreamStart = this[K.vpnOnStreamStart] ?: (this[K.autoVpnTriggers]?.isNotEmpty() ?: d.vpnOnStreamStart),
            vpnOnlyOutsideHome = this[K.vpnOnlyOutsideHome] ?: d.vpnOnlyOutsideHome,
            homeNetworks = this[K.homeNetworks] ?: d.homeNetworks,
            vpnDisconnectAfterStream = this[K.vpnDisconnectAfterStream] ?: d.vpnDisconnectAfterStream,
            activeTunnel = this[K.activeTunnel] ?: d.activeTunnel,
            backupFolderUri = this[K.backupFolderUri] ?: d.backupFolderUri,
            autoBackup = this[K.autoBackup] ?: d.autoBackup,
            backupKeepCount = this[K.backupKeepCount] ?: d.backupKeepCount,
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            // Not stored yet: an install with settings predates the wizard (done), an empty one is new.
            setupDone = this[K.setupDone] ?: asMap().isNotEmpty(),
            setupStep = this[K.setupStep] ?: d.setupStep,
            setupRestore = this[K.setupRestore] ?: d.setupRestore,
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
            // Controllers and hosts were tiles once; they are tabs of the quick menu now.
            quickTiles = this[K.quickTiles]?.split(',')?.filter { it.isNotBlank() && it != "controllers" && it != "hosts" } ?: d.quickTiles,
            comboForward = this[K.comboForward] ?: d.comboForward,
            quickShowVolume = this[K.quickShowVolume] ?: d.quickShowVolume,
            quickShowBrightness = this[K.quickShowBrightness] ?: d.quickShowBrightness,
            quickShowAudioOutput = this[K.quickShowAudioOutput] ?: d.quickShowAudioOutput,
            metadataSources = this[K.metadataSources]?.split(',')?.filter { it.isNotBlank() } ?: d.metadataSources,
            disabledGameSources = this[K.disabledGameSources] ?: d.disabledGameSources,
            comboHome = this[K.comboHome]?.takeIf { it.isNotEmpty() || this[K.combosSet] == true }
                ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.comboHome,
            comboQuickMenu = this[K.comboQuickMenu]?.takeIf { it.isNotEmpty() || this[K.combosSet] == true }
                ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.comboQuickMenu,
            // Empty = off; never saved = the default, unless the quick menu already took it.
            comboMouse = this[K.comboMouse]?.let(::comboKeys)
                ?: d.comboMouse.takeIf { this[K.comboQuickMenu]?.let(::comboKeys) != it }.orEmpty(),
            mouseSpeed = this[K.mouseSpeed] ?: d.mouseSpeed,
            mouseAcceleration = enumOr(this[K.mouseAcceleration], d.mouseAcceleration),
            comboStats = this[K.comboStats]?.let(::comboKeys) ?: d.comboStats,
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
        this[K.homeLayout] = TabLayout.encode(s.homeLayout)
        this[K.libraryLayout] = TabLayout.encode(s.libraryLayout)
        this[K.appsLayout] = TabLayout.encode(s.appsLayout)
        remove(K.tileCorners)
        this[K.focusZoom] = s.focusZoom.name
        this[K.focusGlow] = s.focusGlow
        this[K.dimNotInstalled] = s.dimNotInstalled
        remove(K.cardSize)
        remove(K.homeTileFormat)
        remove(K.libraryTileFormat)
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
        this[K.controllerCombos] = padCombosJson.encodeToString(s.controllerCombos)
        this[K.gameLayouts] = s.gameLayouts.entries.joinToString("\n") { "${it.key}\t${it.value.name}" }
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
        this[K.vpnOnStreamStart] = s.vpnOnStreamStart
        this[K.vpnOnlyOutsideHome] = s.vpnOnlyOutsideHome
        this[K.homeNetworks] = s.homeNetworks
        this[K.vpnDisconnectAfterStream] = s.vpnDisconnectAfterStream
        remove(K.autoVpnTriggers)
        remove(K.homeSsids)
        remove(K.vpnDisconnectAtHome)
        this[K.activeTunnel] = s.activeTunnel
        this[K.backupFolderUri] = s.backupFolderUri
        this[K.autoBackup] = s.autoBackup
        this[K.backupKeepCount] = s.backupKeepCount
        this[K.onboardingDone] = s.onboardingDone
        this[K.setupDone] = s.setupDone
        this[K.setupStep] = s.setupStep
        this[K.setupRestore] = s.setupRestore
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
        this[K.quickShowVolume] = s.quickShowVolume
        this[K.comboForward] = s.comboForward
        this[K.quickShowBrightness] = s.quickShowBrightness
        this[K.quickShowAudioOutput] = s.quickShowAudioOutput
        this[K.metadataSources] = s.metadataSources.joinToString(",")
        this[K.disabledGameSources] = s.disabledGameSources
        this[K.comboHome] = s.comboHome.joinToString(",")
        this[K.comboQuickMenu] = s.comboQuickMenu.joinToString(",")
        this[K.combosSet] = true
        this[K.comboMouse] = s.comboMouse.joinToString(",")
        this[K.comboStats] = s.comboStats.joinToString(",")
        this[K.mouseSpeed] = s.mouseSpeed
        this[K.mouseAcceleration] = s.mouseAcceleration.name
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

/** Android's KEYCODE_BUTTON_MODE: the Guide / Xbox / PS button. */
const val KEYCODE_GUIDE = 110

/** Marker in a button combination: held instead of pressed. */
const val COMBO_HOLD = -1

/** One controller's own button combinations (action name -> keys, empty = off) and pass-through. */
@kotlinx.serialization.Serializable
data class PadCombos(val combos: Map<String, List<Int>> = emptyMap(), val forward: Boolean? = null) {
    val isEmpty: Boolean get() = combos.isEmpty() && forward == null
}

private val padCombosJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** Mouse emulation curve: the exponent on the stick deflection (higher = finer near the centre). */
enum class MouseAcceleration(val exponent: Float) { LOW(1.6f), MEDIUM(2.4f), HIGH(3.2f) }

const val KEYCODE_X = 99
const val KEYCODE_L1 = 102
const val KEYCODE_R1 = 103
const val KEYCODE_START = 108
const val KEYCODE_SELECT = 109

private fun comboKeys(text: String): Set<Int> = text.split(',').mapNotNull { it.toIntOrNull() }.toSet()

/** The combination of [action] in these settings. */
fun LauncherSettings.combo(action: io.github.thecodingdad.gamelodge.input.ComboAction): Set<Int> = when (action) {
    io.github.thecodingdad.gamelodge.input.ComboAction.HOME -> comboHome
    io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU -> comboQuickMenu
    io.github.thecodingdad.gamelodge.input.ComboAction.MOUSE -> comboMouse
    io.github.thecodingdad.gamelodge.input.ComboAction.STATS -> comboStats
}

/** These settings with [keys] as the combination of [action]. */
fun LauncherSettings.withCombo(action: io.github.thecodingdad.gamelodge.input.ComboAction, keys: Set<Int>): LauncherSettings = when (action) {
    io.github.thecodingdad.gamelodge.input.ComboAction.HOME -> copy(comboHome = keys)
    io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU -> copy(comboQuickMenu = keys)
    io.github.thecodingdad.gamelodge.input.ComboAction.MOUSE -> copy(comboMouse = keys)
    io.github.thecodingdad.gamelodge.input.ComboAction.STATS -> copy(comboStats = keys)
}
