package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.desktop.emulation.EmulatorPresets
import io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulation
import io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulator
import io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulatorPlatform
import io.github.thecodingdad.gamelodge.desktop.emulation.PcPlatforms
import io.github.thecodingdad.gamelodge.desktop.emulation.PcRomFolder
import io.github.thecodingdad.gamelodge.desktop.games.PcLibrary
import io.github.thecodingdad.gamelodge.desktop.games.StoreDetection
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostType
import io.github.thecodingdad.gamelodge.metadata.MetadataSources
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.screens.metadata.KeySourceDialog
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.screens.wizard.LocalWizardNav
import io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardChoiceCard
import io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardNote
import io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStatusRow
import io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.UUID

/** The setup wizard's steps on Windows. */
internal class DesktopWizard(
    private val host: () -> HostServices?,
    private val library: () -> PcLibrary?,
    private val startup: Startup?,
) {
    val steps = listOf(
        WizardStep.WELCOME, WizardStep.BACKUP, WizardStep.HOST_ROLE, WizardStep.CONTROLLER, WizardStep.APPEARANCE,
        WizardStep.STARTUP, WizardStep.GAME_SOURCES, WizardStep.EMULATORS, WizardStep.HOSTS, WizardStep.COMPANION,
        WizardStep.METADATA, WizardStep.HOME_ASSISTANT, WizardStep.FINISH,
    )

    /** What a backup doesn't bring to a new PC (role and controller come with it). */
    val deviceSteps = listOf(WizardStep.STARTUP)

    @Composable
    fun Content(step: WizardStep) {
        val settings by LocalContainer.current.settings.settings.collectAsState(LauncherSettings())
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                WizardStep.HOST_ROLE -> host()?.let { HostRoleStep(it) }
                WizardStep.STARTUP -> startup?.let { StartupStep(it) }
                WizardStep.GAME_SOURCES -> library()?.let { GameSourcesStep(it, settings) }
                WizardStep.EMULATORS -> library()?.let { EmulatorsStep(it, settings) }
                else -> Unit
            }
        }
    }
}

// ---- Role as streaming host ----

/** Streaming host software installed on this PC: its type, or null. */
private fun detectHostSoftware(): HostType? {
    val programs = System.getenv("ProgramFiles") ?: "C:\\Program Files"
    return when {
        // Vibepollo installs into Apollo's folder and keeps its own state file there.
        File(programs, "Apollo\\config\\vibeshine_state.json").isFile -> HostType.VIBEPOLLO
        File(programs, "Apollo\\sunshine.exe").isFile -> HostType.APOLLO
        File(programs, "Sunshine\\sunshine.exe").isFile -> HostType.SUNSHINE
        else -> null
    }
}

@Composable
private fun ColumnScope.HostRoleStep(services: HostServices) {
    val role = services.role
    val sync = services.sync
    val settings by role.settings.collectAsState()
    val status by role.status.collectAsState()
    val check by (sync?.check ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val container = LocalContainer.current
    val isHost = settings.serverEnabled || settings.syncEnabled
    val software by produceState<HostType?>(null) { value = withContext(Dispatchers.IO) { detectHostSoftware() } }
    var connection by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var keysFor by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Found software: its type preset (the address stays the default unless changed).
    LaunchedEffect(software) {
        val found = software ?: return@LaunchedEffect
        if (role.settings.value.hostType != found && container.secrets.get(DesktopContainer.HOST_SECRET) == null) role.update { it.copy(hostType = found) }
    }
    LaunchedEffect(isHost) { if (isHost) sync?.refreshCheck() }

    WizardChoiceCard(
        stringResource(Res.string.wizard_host_role_yes), stringResource(Res.string.wizard_host_role_yes_sub), Icons.Filled.Dns,
        onClick = { role.update { it.copy(serverEnabled = true, syncEnabled = true) } }, selected = isHost, initialFocus = true,
    )
    WizardChoiceCard(
        stringResource(Res.string.wizard_host_role_no), stringResource(Res.string.wizard_host_role_no_sub), Icons.Filled.SportsEsports,
        onClick = { role.update { it.copy(serverEnabled = false, syncEnabled = false) } }, selected = !isHost,
    )
    if (!isHost) return

    SectionHeader(stringResource(Res.string.wizard_host_software))
    val found = software
    if (found == null) WizardNote(stringResource(Res.string.wizard_host_software_none), LauncherTheme.colors.warning)
    else WizardStatusRow(stringResource(Res.string.wizard_host_software_found, hostTypeName(found)), settings.hostUrl, done = true, icon = Icons.Filled.Cast)
    if (sync != null) {
        val connected = check?.state == HostCheck.State.CONNECTED
        WizardStatusRow(
            stringResource(Res.string.wizard_host_connection),
            if (connected) stringResource(Res.string.host_check_connected) else stringResource(Res.string.wizard_host_connection_sub),
            done = connected, icon = Icons.Filled.Link, action = stringResource(Res.string.wizard_set_up),
            onClick = { connection = true },
        )
    }
    SectionHeader(stringResource(Res.string.wizard_host_devices))
    WizardStatusRow(
        stringResource(if (status.serverRunning) Res.string.host_server_on else Res.string.host_server_off),
        stringResource(Res.string.wizard_host_devices_sub), done = status.serverRunning, icon = Icons.Filled.Devices,
        action = stringResource(Res.string.wizard_switch_on),
        onClick = if (status.serverRunning) null else ({ role.update { it.copy(serverEnabled = true) } }),
    )
    if (status.serverRunning) {
        LauncherButton(stringResource(Res.string.host_pair_start), onClick = { role.pairing.open(); pairing = true }, icon = Icons.Filled.Link)
    }
    SectionHeader(stringResource(Res.string.wizard_host_keys))
    WizardNote(stringResource(Res.string.wizard_host_keys_sub))
    val secrets = container.secrets
    val hasSgdb = remember(revision) { !secrets.get(SecretStore.Keys.SGDB_API_KEY).isNullOrBlank() }
    val hasIgdb = remember(revision) { !secrets.get(SecretStore.Keys.IGDB_CLIENT_ID).isNullOrBlank() && !secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET).isNullOrBlank() }
    WizardStatusRow("SteamGridDB", stringResource(Res.string.wizard_metadata_sgdb_sub), hasSgdb, Icons.Filled.Key, stringResource(Res.string.wizard_enter), { keysFor = MetadataSources.SGDB })
    WizardStatusRow("IGDB (Twitch)", stringResource(Res.string.wizard_metadata_igdb_sub), hasIgdb, Icons.Filled.Key, stringResource(Res.string.wizard_enter), { keysFor = MetadataSources.IGDB })

    if (connection && sync != null) ConnectionDialog(role, sync, services.secrets, onDismiss = { connection = false }, onSaved = { scope.launch { sync.refreshCheck() } })
    if (pairing) PairingDialog(role, role.devices.devices.value.size) { role.pairing.close(); pairing = false }
    keysFor?.let { id -> KeySourceDialog(id, null, onChanged = { revision++ }, onDismiss = { keysFor = null; revision++ }) }
}

// ---- Startup & window ----

/** The first visit of a new setup switches everything on (as decided); later visits keep the user's choice. */
private var startupPreset = false

@Composable
private fun ColumnScope.StartupStep(startup: Startup) {
    val nav = LocalWizardNav.current!!
    val settings by startup.settings.collectAsState()
    LaunchedEffect(Unit) {
        if (!nav.manual && !startupPreset) {
            startupPreset = true
            startup.update { it.copy(startWithWindows = true, closeToTray = true, startFullscreen = true) }
        }
    }
    SwitchRow(
        stringResource(Res.string.startup_with_windows), settings.startWithWindows, { on -> startup.update { it.copy(startWithWindows = on) } },
        subtitle = stringResource(Res.string.startup_with_windows_sub), initialFocus = true,
    )
    SwitchRow(
        stringResource(Res.string.startup_tray), settings.closeToTray, { on -> startup.update { it.copy(closeToTray = on) } },
        subtitle = stringResource(Res.string.startup_tray_sub),
    )
    SwitchRow(
        stringResource(Res.string.startup_fullscreen), settings.startFullscreen, { on -> startup.update { it.copy(startFullscreen = on) } },
        subtitle = stringResource(Res.string.startup_fullscreen_sub),
    )
}

// ---- Game sources ----

@Composable
private fun ColumnScope.GameSourcesStep(library: PcLibrary, settings: LauncherSettings) {
    val nav = LocalWizardNav.current!!
    val counts by library.counts.collectAsState()
    val scanning by library.scanning.collectAsState()
    val steam by library.steam.status.collectAsState()
    var qr by remember { mutableStateOf(false) }
    val installed by produceState<Map<String, Boolean?>>(emptyMap()) {
        value = withContext(Dispatchers.IO) { library.sources.associateWith { StoreDetection.installed(it) } }
    }
    // Nothing scanned yet: start, so the counter fills while the user looks.
    LaunchedEffect(Unit) { if (counts.isEmpty() && library.scanning.value == null) library.rescan() }
    val active = library.sources.filter { installed[it] != false && it !in settings.disabledGameSources }
    val total = active.sumOf { counts[it]?.first ?: 0 }
    val colors = LauncherTheme.colors
    WizardStatusRow(
        if (scanning != null) stringResource(Res.string.game_sources_scanning) else stringResource(Res.string.game_sources_status, total, active.size),
        scanning?.pending?.joinToString { storeNames[it] ?: it }, done = scanning == null && total > 0, icon = Icons.Filled.SportsEsports,
    )
    scanning?.let { p ->
        LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
    }
    library.sources.filter { installed[it] != false }.forEachIndexed { index, source ->
        val on = source !in settings.disabledGameSources
        val count = counts[source]
        SwitchRow(
            storeLabel(source), on,
            onChange = { enable -> nav.update { s -> s.copy(disabledGameSources = if (enable) s.disabledGameSources - source else s.disabledGameSources + source) } },
            subtitle = when {
                !on -> null
                count == null || count.first == 0 -> stringResource(Res.string.game_source_none)
                else -> stringResource(Res.string.game_source_count, count.first, count.second)
            },
            initialFocus = index == 0,
        )
    }
    SectionHeader(stringResource(Res.string.store_accounts))
    WizardStatusRow(
        if (steam.signedIn) stringResource(Res.string.steam_signed_in, steam.accountName ?: "Steam") else stringResource(Res.string.steam_sign_in),
        stringResource(Res.string.wizard_sources_steam_sub), done = steam.signedIn, icon = Icons.Filled.QrCode2,
        action = stringResource(Res.string.steam_sign_in), onClick = if (steam.signedIn) null else ({ qr = true }),
    )
    if (qr) SteamQrDialog(library) { qr = false }
}

// ---- Emulators / ROMs ----

/** Consoles whose emulators need BIOS / firmware files (as on Android). */
private val needsBios = setOf("psx", "ps2", "ps3", "xbox", "xbox360", "dreamcast", "n3ds", "switch")
    .mapNotNull { PcPlatforms.forFolder(it)?.name }.toSet()

@Composable
private fun ColumnScope.EmulatorsStep(library: PcLibrary, settings: LauncherSettings) {
    val nav = LocalWizardNav.current!!
    val config = remember(settings.pcEmulation) { PcEmulation.decode(settings.pcEmulation) }
    fun save(change: (PcEmulation) -> PcEmulation) = nav.update { it.copy(pcEmulation = change(PcEmulation.decode(it.pcEmulation)).encode()) }
    val scanning by library.scanning.collectAsState()
    val entries by LocalContainer.current.library.allEntries.collectAsState(emptyList())
    // Installed emulators the launcher knows but that aren't set up yet.
    val detected by produceState(emptyList<Pair<io.github.thecodingdad.gamelodge.desktop.emulation.EmulatorPreset, String>>(), config) {
        value = withContext(Dispatchers.IO) {
            EmulatorPresets.all.filter { p -> config.emulators.none { it.presetId == p.id } }.mapNotNull { p -> p.detect()?.let { p to it } }
        }
    }

    SectionHeader(stringResource(Res.string.emu_folders))
    config.folders.forEach { f ->
        WizardStatusRow(f.path, f.platform?.let { PcPlatforms.byName(it)?.displayName ?: it } ?: stringResource(Res.string.emu_folder_auto), done = true, icon = Icons.Filled.Folder)
    }
    WizardStatusRow(
        stringResource(Res.string.emu_folder_add), stringResource(Res.string.wizard_emu_folder_sub), done = false,
        icon = Icons.Filled.CreateNewFolder, action = stringResource(Res.string.wizard_choose), initialFocus = config.folders.isEmpty(),
        onClick = {
            FilePickers.pickFolder()?.let { path ->
                val folder = PcRomFolder(path, PcPlatforms.forFolder(File(path).name)?.name)
                save { c -> c.copy(folders = c.folders.filterNot { it.path.equals(path, true) } + folder) }
                library.rescan()
            }
        },
    )

    SectionHeader(stringResource(Res.string.wizard_emu_detected))
    config.emulators.forEach { emu ->
        WizardStatusRow(emu.name, emu.platforms.joinToString { PcPlatforms.byName(it.platform)?.displayName ?: it.platform }, done = true, icon = Icons.Filled.VideogameAsset)
    }
    detected.forEach { (preset, exe) ->
        WizardStatusRow(
            preset.name, exe, done = false, icon = Icons.Filled.VideogameAsset, action = stringResource(Res.string.wizard_add),
            onClick = {
                val emu = PcEmulator(
                    id = UUID.randomUUID().toString(), name = preset.name, executable = exe, arguments = preset.arguments,
                    platforms = preset.platforms.map { PcEmulatorPlatform(it.platform, emptyList(), it.core) },
                    presetId = preset.id, folderGameFile = preset.folderGameFile,
                )
                save { c -> c.copy(emulators = c.emulators + emu) }
                library.rescan()
            },
        )
    }
    if (config.emulators.isEmpty() && detected.isEmpty()) WizardNote(stringResource(Res.string.emu_pc_none))

    if (config.folders.isNotEmpty()) {
        LauncherButton(
            stringResource(if (scanning != null) Res.string.emu_scanning else Res.string.emu_scan), icon = Icons.Filled.Refresh,
            onClick = { library.rescan() },
        )
    }
    // ROM games per console ("rom:<console>:<game>" entries).
    val perPlatform = entries.filter { e -> e.localTarget?.packageName?.startsWith("rom:") == true }
        .groupingBy { it.item.platform ?: "?" }.eachCount()
    if (perPlatform.isNotEmpty()) {
        SectionHeader(stringResource(Res.string.wizard_emu_found))
        perPlatform.entries.sortedByDescending { it.value }.forEach { (platform, count) ->
            val bios = if (needsBios.any { PcPlatforms.byName(it)?.displayName == platform || it == platform }) stringResource(Res.string.emu_bios_hint) else null
            WizardStatusRow(platform, listOfNotNull(stringResource(Res.string.emu_pc_found, count), bios).joinToString(" · "), done = bios == null, icon = Icons.Filled.VideogameAsset)
        }
    }
}
