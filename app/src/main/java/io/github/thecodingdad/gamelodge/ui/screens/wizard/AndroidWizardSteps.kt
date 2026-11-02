package io.github.thecodingdad.gamelodge.ui.screens.wizard

import io.github.thecodingdad.gamelodge.data.settings.combo
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.data.emulation.RomFolder
import io.github.thecodingdad.gamelodge.data.emulation.RomScanner
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.input.ButtonCombos
import io.github.thecodingdad.gamelodge.input.ComboAccessibilityService
import io.github.thecodingdad.gamelodge.input.ComboAction
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.system.LauncherMode
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.screens.settings.ComboDialog
import io.github.thecodingdad.gamelodge.ui.screens.settings.DefaultCombos
import io.github.thecodingdad.gamelodge.ui.screens.settings.PlatformEmulatorRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.comboLabel
import io.github.thecodingdad.gamelodge.ui.screens.settings.folderName
import io.github.thecodingdad.gamelodge.ui.screens.settings.needsBios
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The setup wizard's steps on Android. */
object AndroidWizard {
    val steps = listOf(
        WizardStep.WELCOME, WizardStep.BACKUP, WizardStep.CONTROLLER, WizardStep.GUIDE, WizardStep.APPEARANCE,
        WizardStep.LAUNCHER_MODE, WizardStep.PERMISSIONS, WizardStep.EMULATORS, WizardStep.HOSTS, WizardStep.COMPANION,
        WizardStep.METADATA, WizardStep.HOME_ASSISTANT, WizardStep.VPN, WizardStep.FINISH,
    )

    /** What a backup doesn't bring to a new device. */
    val deviceSteps = listOf(WizardStep.GUIDE, WizardStep.LAUNCHER_MODE, WizardStep.PERMISSIONS)

    @Composable
    fun Content(step: WizardStep) {
        val settings by LocalContext.current.container.settings.settings.collectAsState(LauncherSettings())
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            when (step) {
                WizardStep.GUIDE -> GuideStep(settings)
                WizardStep.LAUNCHER_MODE -> LauncherModeStep()
                WizardStep.PERMISSIONS -> PermissionsStep()
                WizardStep.EMULATORS -> EmulatorsStep()
                WizardStep.VPN -> VpnStep(settings)
                else -> Unit
            }
        }
    }
}

/** Re-read on every return to the app (system pages change permissions and roles). */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    return tick
}

private fun android.content.Context.open(intent: Intent, fallback: Intent? = null) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { fallback?.let { f -> runCatching { startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
}

// ---- Guide & accessibility service ----

@Composable
private fun ColumnScope.GuideStep(settings: LauncherSettings) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val serviceOn = remember(tick) { ComboAccessibilityService.isEnabled(context) }
    var editing by remember { mutableStateOf<ComboAction?>(null) }
    // The combinations belong to the controller in use; without one (touch) there is nothing to set.
    val pad = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberActiveController()
    val store = context.container.controllerSettings
    val all by store.all.collectAsState()
    val own = pad?.let { all[it.descriptor] } ?: io.github.thecodingdad.gamelodge.stream.ControllerSettings()
    fun ownCombo(a: ComboAction) = own.combo(a)?.toSet()
    fun effective(a: ComboAction) = ownCombo(a) ?: DefaultCombos.combo(a)

    if (pad != null) {
        WizardStatusRow(
            stringResource(Res.string.wizard_guide_press), comboLabel(effective(ComboAction.HOME)), done = false,
            icon = Icons.Filled.Home, action = stringResource(Res.string.wizard_change), onClick = { editing = ComboAction.HOME },
            initialFocus = true,
        )
        WizardStatusRow(
            stringResource(Res.string.wizard_guide_hold), comboLabel(effective(ComboAction.QUICK_MENU)), done = false,
            icon = Icons.Filled.Tune, action = stringResource(Res.string.wizard_change), onClick = { editing = ComboAction.QUICK_MENU },
        )
    }
    SectionHeader(stringResource(Res.string.wizard_guide_other_apps))
    // Only the general list could be opened: say what to look for there.
    var generalList by remember { mutableStateOf(false) }
    WizardStatusRow(
        stringResource(Res.string.combo_service),
        stringResource(
            when {
                serviceOn -> Res.string.combo_service_on
                generalList -> Res.string.combo_service_where
                else -> Res.string.combo_service_hint
            },
        ),
        done = serviceOn, icon = Icons.Filled.Accessibility,
        action = stringResource(Res.string.combo_service_enable),
        onClick = { if (!ComboAccessibilityService.openSettings(context)) generalList = true },
    )
    // Installed outside an app store: Android greys the service out until restricted settings are allowed.
    val restricted = remember(tick) { ComboAccessibilityService.isRestricted(context) }
    if (!serviceOn && restricted) {
        WizardStatusRow(
            stringResource(Res.string.combo_service_restricted_title), stringResource(Res.string.combo_service_restricted),
            done = false, icon = Icons.Filled.Info,
            action = stringResource(Res.string.combo_service_app_info),
            onClick = { ComboAccessibilityService.openAppInfo(context) },
        )
    }
    val action = editing
    if (pad != null) {
        ComboDialog(
            action = action,
            current = action?.let { ownCombo(it) },
            others = ComboAction.entries.filter { it != action }.map { effective(it) },
            descriptor = pad.descriptor,
            default = action?.let { DefaultCombos.combo(it) }.orEmpty(),
            onSave = { keys -> action?.let { a -> store.set(pad.descriptor, own.withCombo(a, keys?.toList())) } },
            onDismiss = { editing = null },
        )
    }
}

// ---- Home app ----

@Composable
private fun ColumnScope.LauncherModeStep() {
    val nav = LocalWizardNav.current!!
    val context = LocalContext.current
    val mode = remember { LauncherMode(context) }
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val isDefault = remember(tick, refresh) { mode.isDefaultHome }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val held = mode.isDefaultHome
        if (!held) mode.setAvailable(false)
        nav.update { it.copy(launcherMode = held) }
        refresh++
    }
    WizardStatusRow(
        stringResource(if (isDefault) Res.string.wizard_launcher_done else Res.string.wizard_launcher_set),
        stringResource(Res.string.wizard_launcher_sub), done = isDefault, icon = Icons.Filled.Home,
        action = stringResource(Res.string.wizard_launcher_set_short),
        onClick = if (isDefault) null else ({
            mode.setAvailable(true)
            val request = mode.requestDefaultHomeIntent()
            if (request != null) roleRequest.launch(request) else context.open(mode.homeSettingsIntent())
        }),
        initialFocus = !isDefault,
    )
}

// ---- Permissions ----

@Composable
private fun ColumnScope.PermissionsStep() {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val usage = remember(tick, refresh) { context.container.usage.granted }
    val overlay = remember(tick, refresh) { Settings.canDrawOverlays(context) }
    val write = remember(tick, refresh) { Settings.System.canWrite(context) }
    val runtime = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
    val tiles = remember(tick, refresh) {
        runtime.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } &&
            notificationManager.isNotificationPolicyAccessGranted
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        // Do-not-disturb has its own system page.
        if (!notificationManager.isNotificationPolicyAccessGranted) context.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }
    val pkg = Uri.parse("package:${context.packageName}")
    val allow = stringResource(Res.string.wizard_allow)

    WizardStatusRow(
        stringResource(Res.string.usage_access), stringResource(Res.string.wizard_perm_usage_sub), done = usage,
        icon = Icons.Filled.QueryStats, action = allow, initialFocus = true,
        onClick = { context.open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
    )
    WizardStatusRow(
        stringResource(Res.string.wizard_perm_overlay), stringResource(Res.string.wizard_perm_overlay_sub), done = overlay,
        icon = Icons.Filled.Layers, action = allow,
        onClick = { context.open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) },
    )
    WizardStatusRow(
        stringResource(Res.string.wizard_perm_write), stringResource(Res.string.wizard_perm_write_sub), done = write,
        icon = Icons.Filled.Brightness6, action = allow,
        onClick = { context.open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)) },
    )
    WizardStatusRow(
        stringResource(Res.string.wizard_perm_tiles), stringResource(Res.string.wizard_perm_tiles_sub), done = tiles,
        icon = Icons.Filled.GridView, action = allow,
        onClick = {
            val missing = runtime.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) request.launch(missing.toTypedArray())
            else context.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        },
    )
}

// ---- Emulators / ROMs ----

@Composable
private fun ColumnScope.EmulatorsStep() {
    val context = LocalContext.current
    val emulation = context.container.emulation
    val catalog = emulation.catalog
    val config by emulation.config.collectAsState()
    val scanning by emulation.scanning.collectAsState()
    val entries by context.container.library.allEntries.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val tick = rememberResumeTick()
    val allFiles = remember(tick) { RomScanner.allFilesAccess() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val name = folderName(DocumentsContract.getTreeDocumentId(uri))
        val platform = catalog.platformForFolder(name.substringAfterLast('/'))?.id
        scope.launch {
            emulation.update { c -> c.copy(folders = c.folders.filterNot { it.uri == uri.toString() } + RomFolder(uri.toString(), name, platform)) }
            emulation.rescan()
        }
    }

    SectionHeader(stringResource(Res.string.emu_folders))
    config.folders.forEach { f ->
        WizardStatusRow(f.name, catalog.platform(f.platform)?.displayName ?: stringResource(Res.string.emu_folder_auto), done = true, icon = Icons.Filled.Folder)
    }
    WizardStatusRow(
        stringResource(Res.string.emu_folder_add), stringResource(Res.string.wizard_emu_folder_sub), done = false,
        icon = Icons.Filled.CreateNewFolder, action = stringResource(Res.string.wizard_choose),
        onClick = { pickFolder.launch(null) }, initialFocus = config.folders.isEmpty(),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WizardStatusRow(
            stringResource(Res.string.emu_all_files), stringResource(Res.string.emu_all_files_sub), done = allFiles,
            icon = Icons.Filled.FolderOpen, action = stringResource(Res.string.wizard_allow),
            onClick = { context.open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) },
        )
    }
    if (config.folders.isNotEmpty()) {
        LauncherButton(
            stringResource(if (scanning) Res.string.emu_scanning else Res.string.emu_scan), icon = Icons.Filled.Refresh,
            onClick = { if (!scanning) scope.launch { emulation.rescan() } },
        )
    }
    // Games found per platform.
    val perPlatform = entries.flatMap { e -> e.romTargets.mapNotNull { it.romPlatform } }.groupingBy { it }.eachCount()
    if (perPlatform.isNotEmpty()) {
        SectionHeader(stringResource(Res.string.wizard_emu_found))
        perPlatform.entries.sortedByDescending { it.value }.forEach { (id, count) ->
            val bios = if (id in needsBios) stringResource(Res.string.emu_bios_hint) else null
            WizardStatusRow(
                catalog.platform(id)?.displayName ?: id,
                listOfNotNull(pluralStringResource(Res.plurals.emu_found, count, count), bios).joinToString(" · "),
                done = bios == null, icon = Icons.Filled.VideogameAsset,
            )
        }
    }
    // Detected emulators per platform (choice when several).
    val withEmulator = remember(tick, config) { catalog.platforms.filter { emulation.choicesOf(it.id).isNotEmpty() } }
    if (withEmulator.isNotEmpty()) {
        SectionHeader(stringResource(Res.string.emu_emulators))
        withEmulator.forEach { PlatformEmulatorRow(it, tick) }
    } else {
        WizardNote(stringResource(Res.string.emu_no_platforms))
    }
}

// ---- VPN ----

@Composable
private fun ColumnScope.VpnStep(settings: LauncherSettings) {
    val nav = LocalWizardNav.current!!
    val context = LocalContext.current
    val vpn = context.container.vpn
    val tunnels by vpn.tunnels.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val importedMsg = stringResource(Res.string.vpn_imported)
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.') ?: "wg0"
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        message = vpn.import(name, text) ?: importedMsg
        if (settings.activeTunnel.isEmpty()) nav.update { it.copy(activeTunnel = vpn.tunnels.value.firstOrNull().orEmpty()) }
    }
    tunnels.forEach { WizardStatusRow(it, stringResource(Res.string.vpn_tunnels), done = true, icon = Icons.Filled.VpnKey) }
    WizardStatusRow(
        stringResource(Res.string.vpn_add_tunnel), stringResource(Res.string.wizard_vpn_import_sub), done = false,
        icon = Icons.Filled.FileOpen, action = stringResource(Res.string.wizard_choose),
        onClick = { openFile.launch(arrayOf("*/*")) }, initialFocus = true,
    )
    message?.let { WizardNote(it) }
    if (tunnels.isEmpty()) return
    SectionHeader(stringResource(Res.string.vpn_auto))
    SwitchRow(
        stringResource(Res.string.vpn_on_stream), settings.vpnOnStreamStart, { v -> nav.update { it.copy(vpnOnStreamStart = v) } },
        subtitle = stringResource(Res.string.vpn_on_stream_sub), icon = Icons.Filled.VpnLock,
    )
    SwitchRow(
        stringResource(Res.string.vpn_disconnect_after), settings.vpnDisconnectAfterStream, { v -> nav.update { it.copy(vpnDisconnectAfterStream = v) } },
        subtitle = stringResource(Res.string.vpn_disconnect_after_sub),
    )
    // Only outside home: switching on takes the network this device is in now as home.
    val subnets = remember { vpn.localAddresses().mapNotNull { (ip, bits) -> io.github.thecodingdad.gamelodge.vpn.HomeNetworks.subnetOf(ip, bits) } }
    SwitchRow(
        stringResource(Res.string.vpn_only_outside), settings.vpnOnlyOutsideHome,
        { on ->
            nav.update { s -> if (on) s.copy(vpnOnlyOutsideHome = true, homeNetworks = s.homeNetworks + subnets) else s.copy(vpnOnlyOutsideHome = false) }
        },
        subtitle = if (settings.vpnOnlyOutsideHome && settings.homeNetworks.isNotEmpty()) {
            stringResource(Res.string.vpn_home_networks) + ": " + settings.homeNetworks.sorted().joinToString(", ")
        } else stringResource(Res.string.wizard_vpn_only_outside_sub),
        icon = Icons.Filled.Home,
    )
}
