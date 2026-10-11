package io.github.thecodingdad.moonlightlauncher.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class HomeLayout { HERO, GRID, CAROUSEL }
enum class ThemeMode { DARK, BLACK, LIGHT }
enum class AccentMode { FIXED, COVER }
enum class CardSize { SMALL, MEDIUM, LARGE }
enum class ButtonLayout { XBOX, NINTENDO }
enum class NetworkCheckMode { OFF, OUTSIDE_HOME, ALWAYS }
enum class AutoVpnTrigger { HOST_UNREACHABLE, OUTSIDE_HOME }

@kotlinx.serialization.Serializable
data class LauncherSettings(
    // Appearance
    val homeLayout: HomeLayout = HomeLayout.HERO,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentMode: AccentMode = AccentMode.COVER,
    val accentColor: Int = 0xFF7C9CFF.toInt(),
    val cardSize: CardSize = CardSize.MEDIUM,
    val reduceMotion: Boolean = false,
    val animatedArtwork: Boolean = true,
    val showHaRowOnHome: Boolean = true,
    // Input & feedback
    val buttonLayout: ButtonLayout = ButtonLayout.XBOX,
    val uiSounds: Boolean = true,
    val uiSoundVolume: Int = 60,
    val haptics: Boolean = true,
    val soundStyle: String = "SOFT",
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
    /** Hide Android's navigation bar / gesture handle while the launcher is shown. */
    val hideNavigationBar: Boolean = false,
    /** Show the button hint bar (only while a controller is connected). */
    val showHintBar: Boolean = true,
    /** Tiles in the quick menu, in order (see QuickTile ids; apps as "app:<package>"). */
    val quickTiles: List<String> = listOf("controllers", "hosts", "vpn", "wifi", "bluetooth", "android"),
    /** Controller button combinations (key codes) for Android Home and the quick menu; empty = off. */
    val comboHome: Set<Int> = emptySet(),
    val comboQuickMenu: Set<Int> = emptySet(),
    /** Shrink the UI while a TV/monitor is attached (the layout is sized for the handheld). */
    val tvScaleAuto: Boolean = true,
    /** UI size on an external display, percent of the handheld size. */
    val tvScale: Int = 70,
    /** UI size on the device's own display, percent. */
    val uiScale: Int = 100,
    /** Separate app filters were created (and shared filters turned into game filters). */
    val appFiltersSeeded: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("launcher_settings")

class SettingsRepository(context: Context) {
    private val store = context.dataStore

    private object K {
        val homeLayout = stringPreferencesKey("home_layout")
        val themeMode = stringPreferencesKey("theme_mode")
        val accentMode = stringPreferencesKey("accent_mode")
        val accentColor = intPreferencesKey("accent_color")
        val cardSize = stringPreferencesKey("card_size")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val animatedArtwork = booleanPreferencesKey("animated_artwork")
        val showHaRowOnHome = booleanPreferencesKey("show_ha_row_on_home")
        val buttonLayout = stringPreferencesKey("button_layout")
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
        val hideNavigationBar = booleanPreferencesKey("hide_navigation_bar")
        val showHintBar = booleanPreferencesKey("show_hint_bar")
        val quickTiles = stringPreferencesKey("quick_tiles")
        val comboHome = stringPreferencesKey("combo_home")
        val comboQuickMenu = stringPreferencesKey("combo_quick_menu")
        val tvScaleAuto = booleanPreferencesKey("tv_scale_auto")
        val tvScale = intPreferencesKey("tv_scale")
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
            homeLayout = enumOr(this[K.homeLayout], d.homeLayout),
            themeMode = enumOr(this[K.themeMode], d.themeMode),
            accentMode = enumOr(this[K.accentMode], d.accentMode),
            accentColor = this[K.accentColor] ?: d.accentColor,
            cardSize = enumOr(this[K.cardSize], d.cardSize),
            reduceMotion = this[K.reduceMotion] ?: d.reduceMotion,
            animatedArtwork = this[K.animatedArtwork] ?: d.animatedArtwork,
            showHaRowOnHome = this[K.showHaRowOnHome] ?: d.showHaRowOnHome,
            buttonLayout = enumOr(this[K.buttonLayout], d.buttonLayout),
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
            hideNavigationBar = this[K.hideNavigationBar] ?: d.hideNavigationBar,
            showHintBar = this[K.showHintBar] ?: d.showHintBar,
            quickTiles = this[K.quickTiles]?.split(',')?.filter { it.isNotBlank() } ?: d.quickTiles,
            comboHome = this[K.comboHome]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.comboHome,
            comboQuickMenu = this[K.comboQuickMenu]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.comboQuickMenu,
            tvScaleAuto = this[K.tvScaleAuto] ?: d.tvScaleAuto,
            tvScale = this[K.tvScale] ?: d.tvScale,
            uiScale = this[K.uiScale] ?: d.uiScale,
            appFiltersSeeded = this[K.appFiltersSeeded] ?: d.appFiltersSeeded,
        )
    }

    private fun MutablePreferences.write(s: LauncherSettings) {
        this[K.homeLayout] = s.homeLayout.name
        this[K.themeMode] = s.themeMode.name
        this[K.accentMode] = s.accentMode.name
        this[K.accentColor] = s.accentColor
        this[K.cardSize] = s.cardSize.name
        this[K.reduceMotion] = s.reduceMotion
        this[K.animatedArtwork] = s.animatedArtwork
        this[K.showHaRowOnHome] = s.showHaRowOnHome
        this[K.buttonLayout] = s.buttonLayout.name
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
        this[K.hideNavigationBar] = s.hideNavigationBar
        this[K.showHintBar] = s.showHintBar
        this[K.quickTiles] = s.quickTiles.joinToString(",")
        this[K.comboHome] = s.comboHome.joinToString(",")
        this[K.comboQuickMenu] = s.comboQuickMenu.joinToString(",")
        this[K.tvScaleAuto] = s.tvScaleAuto
        this[K.tvScale] = s.tvScale
        this[K.uiScale] = s.uiScale
        this[K.appFiltersSeeded] = s.appFiltersSeeded
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default
}
