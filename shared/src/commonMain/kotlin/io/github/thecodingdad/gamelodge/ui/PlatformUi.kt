package io.github.thecodingdad.gamelodge.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.runtime.Composable
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo
import io.github.thecodingdad.gamelodge.ui.shell.SystemStatus
import io.github.thecodingdad.gamelodge.ui.shell.TileModel
import io.github.thecodingdad.gamelodge.ui.shell.TopCutout

/**
 * The parts of the UI that only the platform can provide: device state, controllers, system
 * tiles and screens of platform features. Set once at start.
 */
interface PlatformUi {
    /** Badge of entries that run on this device (phone on Android, monitor on the PC). */
    val localIcon: androidx.compose.ui.graphics.vector.ImageVector

    /** Battery and network state of the device, updated on changes. */
    @Composable
    fun rememberSystemStatus(): SystemStatus

    /** Camera notch/hole on the top edge of the window. */
    @Composable
    fun rememberTopCutout(): TopCutout

    /** Time of day in the user's format (12/24 h). */
    fun formatTime(millis: Long): String

    /** Connected game controllers, updated when one connects, disconnects or changes. */
    @Composable
    fun rememberControllers(): List<ControllerInfo>

    /** Short rumble on a controller (test in the controller list). */
    fun vibrateController(id: Int)

    /** Settings of one controller (hidden while [controller] is null). */
    @Composable
    fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit)

    /** Opens a web page in the browser. */
    fun openUrl(url: String)

    /** Brings the launcher window to the front (e.g. after a login in the browser). */
    fun bringToFront()

    /** The device's own quick tiles (Wi-Fi, Bluetooth …) for [ids]; [revision] changes to refresh them. */
    @Composable
    fun rememberSystemTiles(ids: List<String>, revision: Int): Map<String, List<TileModel>>

    /** Whether the device has what a quick tile needs (e.g. a flashlight). */
    fun quickTileAvailable(id: String): Boolean

    /** Tiles the platform always shows at the end of the quick menu. */
    val fixedQuickTiles: List<String> get() = emptyList()

    /** The device has controls like volume and brightness for the quick menu ([SystemControls]). */
    val hasSystemControls: Boolean get() = false

    /** Volume, brightness and similar device controls in the quick menu. */
    @Composable
    fun SystemControls(visible: Boolean)

    /** Blurs the launcher behind popups and panels ([active] while one is open, [radius] animates). */
    @Composable
    fun backdropBlur(active: Boolean, radius: androidx.compose.ui.unit.Dp): Modifier = Modifier.blur(radius)

    /** The quick menu has a settings tab ([QuickMenuSettingsTab]): how to open it and the like. */
    val hasQuickMenuSettings: Boolean get() = false

    /** Content of the quick menu's settings tab. */
    @Composable
    fun QuickMenuSettingsTab() = Unit

    /** The quick access tab has an edit button that opens [QuickAccessEditor]. */
    val hasQuickAccessEditor: Boolean get() = false

    /** Popup to arrange the quick access tab (tiles, which device controls show). */
    @Composable
    fun QuickAccessEditor(visible: Boolean, onDismiss: () -> Unit) = Unit

    /** Extra buttons at the right end of the status bar (desktop: full screen, close). */
    @Composable
    fun StatusBarActions() = Unit

    /** Lets the user pick an image file; [onPicked] gets its address (null: cancelled). Returns what opens the picker. */
    @Composable
    fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit

    /** Copies an image (picked address, file path …) to [dir]/[baseName].<ext>; null when that fails. */
    suspend fun copyImage(address: String, dir: java.io.File, baseName: String): java.io.File?

    /** Name of an installed app ([packageName]: the platform's id for it). */
    fun appLabel(packageName: String): String

    /** The window runs on a TV (Odin: launcher moved to the TV), laid out for a big screen. */
    @Composable
    fun rememberOnTv(): Boolean

    /** Whether the screensaver may start (desktop: only in full screen; a window shares the screen). */
    @Composable
    fun rememberScreensaverAllowed(): Boolean = true

    /** Keeps the display awake while true (screensaver instead of the system's screen timeout). */
    @Composable
    fun rememberScreenKeeper(): (Boolean) -> Unit

    /** Whether the start command of [entry] can be changed here (PC games on the desktop). */
    fun canEditLaunch(entry: io.github.thecodingdad.gamelodge.data.db.LibraryEntry): Boolean = false

    /** Start command of one game: program, arguments, folder, process names (hidden while [entry] is null). */
    @Composable
    fun LaunchSettingsDialog(entry: io.github.thecodingdad.gamelodge.data.db.LibraryEntry?, onDismiss: () -> Unit) = Unit

    /** The system's information page of an installed app. */
    fun openAppInfo(packageName: String)

    /** Handheld display options (navigation bar, TV output, mobile data); not on a PC. */
    val deviceDisplayOptions: Boolean get() = true

    /** Settings pages only some platforms have (VPN, backup, emulators …). */
    fun hasPage(id: io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId): Boolean

    /** Current state of a platform page for its row in the settings list (null: its description). */
    @Composable
    fun pageStatus(id: io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId): String? = null

    @Composable
    fun PlatformSettingsPage(
        id: io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId,
        vm: LauncherViewModel,
        openProfile: (java.util.UUID?) -> Unit,
        openStats: () -> Unit,
    )

    /** Controller list and settings (Settings → Controllers). */
    @Composable
    fun ControllersScreen()

    /** Editor of one stream profile. */
    @Composable
    fun ProfileEditor(uuid: java.util.UUID?, name: String, onDeleted: () -> Unit)

    /** Button combinations (Home, quick menu) exist here (Android). */
    val hasButtonCombos: Boolean get() = false

    /** Row on the settings overview that opens the device's own settings; nothing where there are none. */
    @Composable
    fun SystemSettingsRow()

    /**
     * Deletes all data and settings of the launcher (pairings and credentials too) and ends or
     * restarts it. Asked for by the user in Settings › Data.
     */
    fun resetAllData()

    /** The setup wizard's steps on this platform, in order (new setup, ends with FINISH). */
    val wizardSteps: List<io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep>

    /** Steps a backup can't carry (permissions, home app …), shown after restoring one. */
    val wizardDeviceSteps: List<io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep>

    /** Content of a wizard step only this platform has (inside the wizard's frame). */
    @Composable
    fun WizardStepContent(step: io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep)

    /** Switches the UI language ("system", "en", "de"). */
    fun applyLanguage(tag: String)

    companion object {
        lateinit var current: PlatformUi
    }
}
