package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerLayoutOverrideRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.shell.QuickTile
import io.github.thecodingdad.gamelodge.ui.shell.SystemStatus
import io.github.thecodingdad.gamelodge.ui.shell.TileModel
import io.github.thecodingdad.gamelodge.ui.shell.TopCutout
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Frame
import java.io.File
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** The desktop's side of the shared UI. */
class DesktopPlatformUi(
    private val gamepads: Gamepads,
    private val window: () -> Frame?,
    private val pcLibrary: () -> io.github.thecodingdad.gamelodge.desktop.games.PcLibrary?,
    private val host: () -> HostServices? = { null },
    private val startup: Startup? = null,
    private val profiles: io.github.thecodingdad.gamelodge.desktop.stream.DesktopProfiles? = null,
    private val windowControls: WindowControls? = null,
) : PlatformUi {
    override val localIcon = Icons.Filled.DesktopWindows

    private val wizard = DesktopWizard(host, pcLibrary, startup)
    override val wizardSteps get() = wizard.steps
    override val wizardDeviceSteps get() = wizard.deviceSteps

    @Composable
    override fun WizardStepContent(step: io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep) = wizard.Content(step)

    /** Bumped when the language changes: the window content is rebuilt in the new language. */
    val languageRevision = mutableIntStateOf(0)

    @Composable
    override fun rememberSystemStatus(): SystemStatus {
        val status by produceState(readStatus()) {
            while (true) {
                delay(30_000)
                value = withContext(Dispatchers.IO) { readStatus() }
            }
        }
        return status
    }

    override fun battery(): io.github.thecodingdad.gamelodge.ui.Battery? =
        readStatus().let { s -> s.batteryPercent?.let { io.github.thecodingdad.gamelodge.ui.Battery(it, s.charging) } }

    private fun readStatus(): SystemStatus {
        val power = if (isWindows) runCatching { SystemPowerStatus().also { WindowsPower.kernel.GetSystemPowerStatus(it) } }.getOrNull() else null
        // 128 = no battery, 255 = unknown.
        val hasBattery = power != null && (power.BatteryFlag.toInt() and 0xFF) != 128 && (power.BatteryFlag.toInt() and 0xFF) != 255
        val percent = if (hasBattery) (power!!.BatteryLifePercent.toInt() and 0xFF).takeIf { it in 0..100 } else null
        val interfaces = runCatching { NetworkInterface.networkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        val wireless = interfaces.any { it.name.startsWith("wlan") || it.displayName.contains("wi-fi", true) || it.displayName.contains("wireless", true) }
        val vpn = interfaces.any { it.displayName.contains("wireguard", true) || it.name.startsWith("wg") || it.name.startsWith("tun") }
        return SystemStatus(
            batteryPercent = percent,
            charging = power != null && power.ACLineStatus.toInt() == 1,
            wifi = wireless,
            vpn = vpn,
            ethernet = !wireless && interfaces.isNotEmpty(),
        )
    }

    @Composable
    override fun rememberTopCutout() = TopCutout(TopCutout.Position.NONE, 0.dp)

    /** Like the Windows clock: its short time format, not the launcher's language. */
    override fun formatTime(millis: Long): String =
        systemTimeFormat().format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    @Composable
    override fun rememberControllers(): List<ControllerInfo> = gamepads.controllers.collectAsState().value

    override fun vibrateController(id: Int) = gamepads.vibrate(id)

    @Composable
    override fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) {
        Modal(visible = controller != null, onDismiss = onDismiss, title = controller?.name, maxWidth = 560.dp) {
            val c = controller ?: return@Modal
            // Scrolls when the launcher section and combinations don't fit.
            Column(
                Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                c.batteryPercent?.let { Text(stringResource(Res.string.controller_battery, it), color = LauncherTheme.colors.onSurfaceMuted) }
                // No serial number ("vendor:product" key): every controller of this model shares the settings.
                if (c.key.count { it == ':' } == 1) {
                    Text(stringResource(Res.string.controller_model_shared), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                }
                // As on Android: this controller's buttons, combinations and stream settings.
                settingsState()?.let { (settings, update) ->
                    SectionHeader(stringResource(Res.string.controller_section_buttons))
                    ControllerLayoutOverrideRow(c, settings, update)
                    // Own combinations of this controller (by name, like its layout). The game always
                    // gets the buttons on Windows, so there's no pass-through choice here.
                    val own = settings.controllerCombos[c.key] ?: io.github.thecodingdad.gamelodge.data.settings.PadCombos()
                    fun save(pad: io.github.thecodingdad.gamelodge.data.settings.PadCombos) = update { s ->
                        s.copy(controllerCombos = if (pad.isEmpty) s.controllerCombos - c.key else s.controllerCombos + (c.key to pad))
                    }
                    io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerComboRows(
                        combo = { a -> own.combos[a.name]?.toSet() },
                        forward = own.forward,
                        descriptor = c.key,
                        onCombo = { a, keys -> save(own.copy(combos = if (keys == null) own.combos - a.name else own.combos + (a.name to keys.toList()))) },
                        onForward = { v -> save(own.copy(forward = v)) },
                        showForward = false,
                        // Moonlight-Qt has no on-screen keyboard to show.
                        actions = io.github.thecodingdad.gamelodge.input.ComboAction.entries - io.github.thecodingdad.gamelodge.input.ComboAction.KEYBOARD,
                    )
                    ControllerStreamRows(c, settings, update)
                }
                if (c.canVibrate) {
                    LauncherButton(
                        stringResource(Res.string.controller_vibrate_test), onClick = { gamepads.vibrate(c.id) },
                        icon = Icons.Filled.Vibration, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    override fun canEditLaunch(entry: io.github.thecodingdad.gamelodge.data.db.LibraryEntry): Boolean =
        entry.localTarget?.packageName?.let { pcLibrary()?.original(it) } != null

    @Composable
    override fun LaunchSettingsDialog(entry: io.github.thecodingdad.gamelodge.data.db.LibraryEntry?, onDismiss: () -> Unit) {
        val state = settingsState() ?: return
        val original = entry?.localTarget?.packageName?.let { pcLibrary()?.original(it) }
        io.github.thecodingdad.gamelodge.desktop.games.LaunchSettingsDialog(entry?.item?.title, original, state.first, state.second, onDismiss)
    }

    override fun openUrl(url: String) = BrowserOpener.open(url)

    /** Marks the window as shown in the launcher's state (it was hidden in the tray). */
    var onShow: () -> Unit = {}

    override fun bringToFront() {
        java.awt.EventQueue.invokeLater {
            // Through the state that drives the window, so hiding it (X, tray) works afterwards.
            onShow()
            window()?.apply {
                isVisible = true
                if (isWindows) WindowsForeground.claim(this)
                // No requestFocus(): that makes the frame itself the focus owner, and the keys from the
                // pads would go there instead of to the content. Activating it restores the content's.
                toFront()
            }
        }
    }

    // A PC has no Wi-Fi/Bluetooth/flashlight tiles of its own here; the launcher's tiles remain.
    @Composable
    override fun rememberSystemTiles(ids: List<String>, revision: Int): Map<String, List<TileModel>> = emptyMap()

    /** Full screen and close as small icons at the end of the status bar (also in D-pad reach). */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    override fun StatusBarActions() {
        val controls = windowControls ?: return
        val fullscreen by controls.fullscreen.collectAsState()
        val toTray = startup?.settings?.collectAsState()?.value?.closeToTray == true
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            WindowIcon(
                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                stringResource(if (fullscreen) Res.string.qt_to_window else Res.string.qt_to_fullscreen),
                controls.toggleFullscreen,
            )
            WindowIcon(
                if (toTray) Icons.Filled.Close else Icons.Filled.PowerSettingsNew,
                stringResource(if (toTray) Res.string.window_to_tray else Res.string.qt_quit),
                controls.close,
            )
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun WindowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
        val colors = LauncherTheme.colors
        androidx.compose.foundation.TooltipArea(
            tooltip = {
                androidx.compose.material3.Text(
                    label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.onSurface,
                    modifier = Modifier.background(colors.surfaceRaised, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            },
        ) {
            io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                onClick = onClick,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                focusScale = 1f, glow = false,
            ) {
                androidx.compose.material3.Icon(icon, label, tint = colors.onSurfaceMuted, modifier = Modifier.padding(5.dp).size(20.dp))
            }
        }
    }

    override fun quickTileAvailable(id: String) = id in LAUNCHER_TILES

    @Composable
    override fun SystemControls(visible: Boolean) = DesktopSystemControls(visible)

    override val hasSystemControls get() = true

    override val hasQuickAccessEditor get() = true

    override val hasQuickMenuSettings get() = true

    @Composable
    override fun QuickMenuSettingsTab() {
        val (settings, update) = settingsState() ?: return
        // The combinations of the pad in use (each pad has its own, by name).
        val controllers = rememberControllers()
        val c = controllers.firstOrNull { it.key == io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.key } ?: controllers.firstOrNull()
        if (c == null) {
            Text(stringResource(Res.string.controller_combos_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        } else {
            val own = settings.controllerCombos[c.key] ?: io.github.thecodingdad.gamelodge.data.settings.PadCombos()
            fun save(pad: io.github.thecodingdad.gamelodge.data.settings.PadCombos) = update { s ->
                s.copy(controllerCombos = if (pad.isEmpty) s.controllerCombos - c.key else s.controllerCombos + (c.key to pad))
            }
            SectionHeader(stringResource(Res.string.controller_combos_of, c.displayName))
            io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerComboRows(
                combo = { a -> own.combos[a.name]?.toSet() },
                forward = own.forward,
                descriptor = c.key,
                onCombo = { a, keys -> save(own.copy(combos = if (keys == null) own.combos - a.name else own.combos + (a.name to keys.toList()))) },
                onForward = { v -> save(own.copy(forward = v)) },
                showForward = false,
                actions = listOf(io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU),
                firstFocus = true,
                subtitles = true,
            )
        }
    }

    @Composable
    override fun QuickAccessEditor(visible: Boolean, onDismiss: () -> Unit) = DesktopQuickAccessEditor(visible, onDismiss)

    @Composable
    override fun backdropBlur(active: Boolean, radius: androidx.compose.ui.unit.Dp): Modifier = snapshotBlur(active, radius, 14.dp, LauncherTheme.colors.background)

    override fun appLabel(packageName: String) = packageName

    @Composable
    override fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit = {
        onPicked(FilePickers.pickFile(types = FilePickers.Types.IMAGES)?.let { File(it).absolutePath })
    }

    override suspend fun copyImage(address: String, dir: File, baseName: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(address.removePrefix("file://"))
            dir.mkdirs()
            val ext = source.extension.lowercase().takeIf { it in IMAGE_TYPES } ?: "img"
            source.copyTo(File(dir, "$baseName.$ext"), overwrite = true)
        }.getOrNull()
    }

    @Composable
    override fun rememberOnTv() = false

    // In a window the rest of the desktop stays visible: a screensaver only disturbs there.
    @Composable
    override fun rememberScreensaverAllowed(): Boolean = windowControls?.fullscreen?.collectAsState()?.value ?: true

    @Composable
    override fun rememberScreenKeeper(): (Boolean) -> Unit = remember { { _: Boolean -> } }

    override fun openAppInfo(packageName: String) = Unit

    override val deviceDisplayOptions: Boolean get() = false

    override val hasDisconnectShortcut: Boolean get() = true

    override fun hasPage(id: SettingsPageId) = id == SettingsPageId.GAME_SOURCES || (id == SettingsPageId.EMULATORS && pcLibrary() != null) || (id == SettingsPageId.HOST_ROLE && host()?.let { hostConfigured(it) } == true) || (id == SettingsPageId.STREAMING_HOST && host()?.sync != null) ||
        (id == SettingsPageId.STARTUP && startup != null) ||
        (id == SettingsPageId.PROFILES && profiles != null)

    @Composable
    override fun pageStatus(id: SettingsPageId): String? {
        val h = host() ?: return null
        return when (id) {
            SettingsPageId.STREAMING_HOST -> {
                val sync = h.sync ?: return null
                LaunchedEffect(sync) { sync.refreshCheck() }
                val check by sync.check.collectAsState()
                val settings by h.role.settings.collectAsState()
                when (check?.state) {
                    io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck.State.CONNECTED ->
                        stringResource(Res.string.host_check_connected, hostTypeName(settings.hostType), check!!.apps)
                    io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck.State.NOT_SET_UP -> stringResource(Res.string.host_check_not_set_up)
                    io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck.State.AUTH_FAILED -> stringResource(Res.string.host_check_auth)
                    io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck.State.UNREACHABLE -> stringResource(Res.string.host_check_unreachable)
                    io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck.State.ERROR -> stringResource(Res.string.host_check_error, check!!.status)
                    else -> null
                }
            }
            SettingsPageId.HOST_ROLE -> {
                val status by h.role.status.collectAsState()
                val devices by h.role.devices.devices.collectAsState()
                if (status.serverRunning) stringResource(Res.string.host_devices_count, devices.size) else stringResource(Res.string.host_server_off)
            }
            else -> null
        }
    }

    @Composable
    override fun PlatformSettingsPage(id: SettingsPageId, vm: LauncherViewModel, openProfile: (UUID?) -> Unit, openStats: () -> Unit) {
        val settings by vm.settings.collectAsState()
        when (id) {
            SettingsPageId.GAME_SOURCES -> pcLibrary()?.let { GameSourcesPage(it, settings, vm::updateSettings) }
            SettingsPageId.EMULATORS -> pcLibrary()?.let {
                io.github.thecodingdad.gamelodge.desktop.emulation.EmulatorsPage(it, settings, vm::updateSettings, java.io.File(dataDirectory(), "custom-covers"))
            }
            SettingsPageId.HOST_ROLE -> host()?.let { HostRolePage(it) }
            SettingsPageId.STREAMING_HOST -> host()?.let { h -> h.sync?.let { StreamingHostPage(h.role, it, h.secrets) } }
            SettingsPageId.STARTUP -> startup?.let { StartupPage(it) }
            SettingsPageId.PROFILES -> profiles?.let { store ->
                val all by vm.profiles.collectAsState()
                val active by vm.activeProfile.collectAsState()
                io.github.thecodingdad.gamelodge.ui.screens.profiles.ProfilesList(store, all, active, openProfile)
            }
            else -> Unit
        }
    }

    /** The launcher settings and their update, for the controller pages. */
    @Composable
    private fun settingsState(): Pair<LauncherSettings, ((LauncherSettings) -> LauncherSettings) -> Unit>? {
        val container = io.github.thecodingdad.gamelodge.LocalContainer.current
        val settings by container.settings.settings.collectAsState(null)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        return settings?.let { s -> s to { t: (LauncherSettings) -> LauncherSettings -> scope.launch { container.settings.update(t) }; Unit } }
    }

    @Composable
    override fun ControllersScreen() {
        val controllers = rememberControllers()
        var selected by remember { mutableStateOf<ControllerInfo?>(null) }
        SettingsPage(stringResource(Res.string.settings_controllers)) {
            SectionHeader(stringResource(Res.string.controllers_connected))
            if (controllers.isEmpty()) Text(stringResource(Res.string.controllers_none), color = LauncherTheme.colors.onSurface)
            controllers.forEachIndexed { index, c ->
                NavRow(
                    c.displayName, { selected = c },
                    subtitle = c.batteryPercent?.let { stringResource(Res.string.controller_battery, it) },
                    icon = c.displayIcon, initialFocus = index == 0,
                )
            }
            // What applies to every pad (layout and combinations are each pad's own).
            SectionHeader(stringResource(Res.string.controllers_defaults))
            Text(stringResource(Res.string.controllers_defaults_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            settingsState()?.let { (settings, update) -> io.github.thecodingdad.gamelodge.ui.screens.settings.HintBarRow(settings, update) }
            // In the stream the pad that started it is player 1 (Moonlight-Qt has no more pad options).
            Text(stringResource(Res.string.controller_starter_player_one_info), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSectionRows(controllerSections)
            SeeAlso(
                SeeAlsoLink(stringResource(Res.string.settings_sounds), SettingsTarget.Page(SettingsPageId.SOUNDS)),
                SeeAlsoLink(stringResource(Res.string.settings_profiles), SettingsTarget.Page(SettingsPageId.PROFILES)),
            )
        }
        ControllerDetailDialog(selected) { selected = null }
    }

    override val controllerSections = listOf(
        io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection.MOUSE,
    )

    @Composable
    override fun ControllerSectionScreen(section: io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection) {
        val (settings, update) = settingsState() ?: return
        val title = when (section) {
            io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection.MOUSE -> Res.string.controller_section_mouse
        }
        SettingsPage(stringResource(title)) {
            when (section) {
                io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection.MOUSE ->
                    io.github.thecodingdad.gamelodge.ui.screens.settings.MouseSettingsRows(settings, update)
            }
        }
    }

    @Composable
    override fun ProfileEditor(uuid: UUID?, name: String, onDeleted: () -> Unit) {
        profiles?.let { io.github.thecodingdad.gamelodge.ui.screens.profiles.ProfileEditor(it, uuid, name, onDeleted) }
    }

    @Composable
    override fun SystemSettingsRow() = Unit

    /** Noted for the next start (files are in use now); the launcher quits. */
    override fun resetAllData() {
        DataReset.request(dataDirectory())
        windowControls?.exit?.invoke() ?: kotlin.system.exitProcess(0)
    }

    override fun applyLanguage(tag: String) {
        applyLocale(tag)
        languageRevision.intValue++
    }

    companion object {
        private val LAUNCHER_TILES = setOf(QuickTile.VPN)
        private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val systemLocale: Locale = Locale.getDefault()

        @Volatile private var timeFormat: Pair<String?, DateTimeFormatter>? = null

        /**
         * Windows' short time format ("HH:mm", "h:mm tt" … in the region settings), read again when
         * it changes; elsewhere (or unreadable) the system language's short time.
         */
        internal fun systemTimeFormat(): DateTimeFormatter {
            val pattern = if (!isWindows) null else runCatching {
                com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, "Control Panel\\International", "sShortTime",
                )
            }.getOrNull()
            timeFormat?.takeIf { it.first == pattern }?.let { return it.second }
            val formatter = pattern?.let { windowsTimePattern(it) }?.let { runCatching { DateTimeFormatter.ofPattern(it, systemLocale) }.getOrNull() }
                ?: DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(systemLocale)
            timeFormat = pattern to formatter
            return formatter
        }

        /** A Windows time pattern ("h:mm tt") as a java.time one ("h:mm a"). */
        internal fun windowsTimePattern(windows: String): String =
            windows.replace(Regex("t+"), "a")

        /** "system", "en" or "de" as the JVM's default locale (the resources follow it). */
        fun applyLocale(tag: String) {
            Locale.setDefault(if (tag == "system" || tag.isBlank()) systemLocale else Locale.forLanguageTag(tag))
        }
    }
}

/** Refreshes [content]'s language once at start (the stored setting may differ from the system). */
@Composable
fun ApplyStoredLanguage(language: String, platform: DesktopPlatformUi) {
    LaunchedEffect(language) {
        val wanted = if (language == "system" || language.isBlank()) null else Locale.forLanguageTag(language)
        if (wanted != null && Locale.getDefault().language != wanted.language) platform.applyLanguage(language)
    }
}

/** kernel32's battery state (SYSTEM_POWER_STATUS). */
@com.sun.jna.Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
class SystemPowerStatus : com.sun.jna.Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

private object WindowsPower {
    @Suppress("FunctionName")
    interface Kernel32Power : com.sun.jna.Library {
        fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean
    }

    val kernel: Kernel32Power by lazy { com.sun.jna.Native.load("kernel32", Kernel32Power::class.java) }
}

/**
 * The connection to the streaming host software is set up: only then do devices & pairing make
 * sense (paired launchers get this PC's host games, play time and stream notices).
 */
internal fun hostConfigured(h: HostServices): Boolean =
    h.sync != null && !h.secrets?.get(DesktopContainer.HOST_SECRET).isNullOrBlank()

/**
 * A controller's stream settings on the PC (Moonlight-Qt applies them per controller): sticks and
 * touchpad, vibration and motion, connection. Unset values follow its model's defaults.
 */
@Composable
private fun ControllerStreamRows(
    c: ControllerInfo,
    settings: io.github.thecodingdad.gamelodge.data.settings.LauncherSettings,
    update: ((io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> Unit,
) {
    val own = settings.controllerStream[c.key] ?: io.github.thecodingdad.gamelodge.data.settings.PadStreamSettings()
    val d = c.modelIds.let { (v, p) -> io.github.thecodingdad.gamelodge.ui.screens.settings.controllerDefaults(v, p) }
    fun save(t: (io.github.thecodingdad.gamelodge.data.settings.PadStreamSettings) -> io.github.thecodingdad.gamelodge.data.settings.PadStreamSettings) = update { s ->
        val pad = t(s.controllerStream[c.key] ?: io.github.thecodingdad.gamelodge.data.settings.PadStreamSettings())
        s.copy(controllerStream = if (pad.isEmpty) s.controllerStream - c.key else s.controllerStream + (c.key to pad))
    }
    val on = stringResource(Res.string.ha_on)
    val off = stringResource(Res.string.ha_off)
    val tri = listOf<Boolean?>(null, true, false)
    @Composable
    fun default(value: String) = stringResource(Res.string.controller_default_value, value)
    @Composable
    fun onOff(v: Boolean?, fallback: Boolean) = when (v) { null -> default(if (fallback) on else off); true -> on; false -> off }
    // Sony pads have a touchpad (SDL tells Moonlight-Qt; the launcher knows the model by name).
    val touchpad = c.hasTouchpad || c.modelIds.first == 0x054c

    SectionHeader(stringResource(if (touchpad) Res.string.controller_section_sticks_touchpad else Res.string.controller_section_sticks))
    io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
        stringResource(Res.string.controller_deadzone), listOf<Int?>(null, 0, 3, 5, 8, 10, 15, 20), own.deadzonePercent,
        label = { v -> v?.let { "$it %" } ?: default("${d.deadzonePercent} %") },
        onSelect = { v -> save { it.copy(deadzonePercent = v) } },
    )
    if (touchpad) {
        io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
            stringResource(Res.string.controller_touchpad_mouse), tri, own.touchpadMouse,
            label = { v -> onOff(v, d.touchpadAsMouse) },
            onSelect = { v -> save { it.copy(touchpadMouse = v) } },
        )
    }

    SectionHeader(stringResource(Res.string.controller_section_feedback))
    if (c.canVibrate) {
        io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
            stringResource(Res.string.controller_vibration_strength), listOf<Int?>(null, 0, 25, 50, 75, 100), own.rumblePercent,
            label = { v -> when (v) { null -> default("${d.vibrationStrength} %"); 0 -> off; else -> "$v %" } },
            onSelect = { v -> save { it.copy(rumblePercent = v) } },
        )
    }
    io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
        stringResource(Res.string.controller_motion), tri, own.motion,
        label = { v -> onOff(v, d.motion != io.github.thecodingdad.gamelodge.ui.screens.settings.MotionMode.OFF) },
        onSelect = { v -> save { it.copy(motion = v) } },
    )

    SectionHeader(stringResource(Res.string.controller_section_connection))
    io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
        stringResource(Res.string.controller_auto_attach), listOf<Boolean?>(null, true), own.attachOnUse?.takeIf { it },
        label = { v -> stringResource(if (v == true) Res.string.controller_auto_attach_off else Res.string.controller_auto_attach_auto) },
        onSelect = { v -> save { it.copy(attachOnUse = v) } },
    )
    io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
        stringResource(Res.string.controller_battery_report), tri, own.batteryReport,
        label = { v -> onOff(v, d.batteryReport) },
        onSelect = { v -> save { it.copy(batteryReport = v) } },
        subtitle = stringResource(Res.string.controller_battery_report_sub),
    )
}
