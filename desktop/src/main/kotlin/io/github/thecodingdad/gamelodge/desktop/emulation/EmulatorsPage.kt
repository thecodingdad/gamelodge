package io.github.thecodingdad.gamelodge.desktop.emulation

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso
import io.github.thecodingdad.gamelodge.ui.screens.settings.searchHighlight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.desktop.FilePickers
import io.github.thecodingdad.gamelodge.desktop.games.CustomGame
import io.github.thecodingdad.gamelodge.desktop.games.PcLibrary
import io.github.thecodingdad.gamelodge.desktop.games.playnite.PlayniteImport
import io.github.thecodingdad.gamelodge.desktop.games.playnite.PlayniteLibrary
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.screens.library.Chip
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.UUID

/**
 * Settings → Emulators on the PC: the emulators set up here (added from the known ones or by
 * hand), the ROM folders, the emulator per console and the one-time takeover from Playnite.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmulatorsPage(
    library: PcLibrary,
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    /** Where cover images of games taken over from Playnite go. */
    coverDir: File,
) {
    val config = remember(settings.pcEmulation) { PcEmulation.decode(settings.pcEmulation) }
    fun save(change: (PcEmulation) -> PcEmulation) = update { it.copy(pcEmulation = change(PcEmulation.decode(it.pcEmulation)).encode()) }
    val counts by library.counts.collectAsState()
    val scanning by library.scanning.collectAsState()
    val scope = rememberCoroutineScope()

    var choosingPreset by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PcEmulator?>(null) }
    var editingFolder by remember { mutableStateOf<PcRomFolder?>(null) }
    var playniteResult by remember { mutableStateOf<String?>(null) }
    val playnite = remember { playniteInstalled() }
    // Network drives can take long or hang: existence is checked off the UI thread (unknown = fine).
    val present by androidx.compose.runtime.produceState(emptyMap<String, Boolean>(), config) {
        val paths = config.emulators.map { it.executable } + config.folders.map { it.path }
        value = withContext(Dispatchers.IO) { paths.associateWith { File(it).exists() } }
    }

    SettingsPage(stringResource(Res.string.settings_emulators)) {
        Text(stringResource(Res.string.emu_pc_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)

        SectionHeader(stringResource(Res.string.emu_pc_list))
        if (config.emulators.isEmpty()) {
            Text(stringResource(Res.string.emu_pc_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        }
        val missing = stringResource(Res.string.emu_pc_missing)
        config.emulators.forEachIndexed { index, emu ->
            val consoles = emu.platforms.joinToString { PcPlatforms.byName(it.platform)?.displayName ?: it.platform }
            NavRow(
                emu.name, { editing = emu },
                subtitle = if (present[emu.executable] != false) consoles else "$missing · $consoles",
                icon = Icons.Filled.SportsEsports, initialFocus = index == 0,
                image = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberAppIcon(emu.executable),
            )
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.emu_pc_add), icon = Icons.Filled.Add, onClick = { choosingPreset = true })
            if (playnite != null) {
                LauncherButton(stringResource(Res.string.emu_pc_playnite), icon = Icons.Filled.SystemUpdateAlt, onClick = {
                    scope.launch { playniteResult = importFromPlaynite(playnite, coverDir, update) }
                })
            }
        }
        if (playnite != null) {
            Text(
                playniteResult ?: stringResource(Res.string.emu_pc_playnite_sub), style = MaterialTheme.typography.bodySmall,
                color = if (playniteResult != null) LauncherTheme.colors.accentText else LauncherTheme.colors.onSurfaceMuted,
            )
        }

        SectionHeader(stringResource(Res.string.emu_folders))
        val auto = stringResource(Res.string.emu_folder_auto)
        val folderMissing = stringResource(Res.string.emu_pc_folder_missing)
        config.folders.forEach { folder ->
            val platform = folder.platform?.let { PcPlatforms.byName(it)?.displayName ?: it } ?: auto
            NavRow(
                folder.path, { editingFolder = folder },
                subtitle = if (present[folder.path] != false) platform else "$folderMissing · $platform",
                icon = Icons.Filled.Folder,
            )
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.emu_folder_add), modifier = Modifier.searchHighlight(stringResource(Res.string.emu_folder_add), androidx.compose.foundation.shape.RoundedCornerShape(26.dp)), icon = Icons.Filled.CreateNewFolder, onClick = {
                FilePickers.pickFolder()?.let { path ->
                    // A folder named like a console ("snes") is that console, else one subfolder per console.
                    val folder = PcRomFolder(path, PcPlatforms.forFolder(File(path).name)?.name)
                    save { c -> c.copy(folders = c.folders.filterNot { it.path.equals(path, true) } + folder) }
                    editingFolder = folder
                }
            })
            if (config.folders.isNotEmpty()) {
                LauncherButton(
                    stringResource(if (scanning != null) Res.string.emu_scanning else Res.string.emu_scan), icon = Icons.Filled.Refresh,
                    onClick = { library.rescan() },
                )
            }
        }
        counts["emulator"]?.let { (all, _) ->
            if (config.folders.isNotEmpty()) Text(stringResource(Res.string.emu_pc_found, all), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        }

        // Consoles several emulators run: which one starts their games.
        val shared = config.platforms().filter { p -> config.emulators.count { it.supports(p) } > 1 }
        if (shared.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.emu_pc_per_platform))
            shared.forEach { platform ->
                val choices = config.emulators.filter { it.supports(platform) }
                ChoiceRow(
                    PcPlatforms.byName(platform)?.displayName ?: platform, choices.map { it.id },
                    config.emulatorFor(platform)?.id ?: choices.first().id,
                    label = { id -> choices.first { it.id == id }.name },
                    image = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberAppIcon((config.emulatorFor(platform) ?: choices.first()).executable),
                    onSelect = { id -> save { it.copy(defaults = it.defaults + (platform to id)) } },
                )
            }
        }
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_game_sources), SettingsTarget.Page(SettingsPageId.GAME_SOURCES)),
        )
    }

    PresetDialog(choosingPreset, onDismiss = { choosingPreset = false }) { preset ->
        choosingPreset = false
        editing = PcEmulator(
            id = "", name = preset?.name.orEmpty(), executable = preset?.detect().orEmpty(),
            arguments = preset?.arguments ?: "\"{rom}\"",
            platforms = preset?.platforms?.map { PcEmulatorPlatform(it.platform, emptyList(), it.core) }.orEmpty(),
            presetId = preset?.id, folderGameFile = preset?.folderGameFile,
        )
    }
    EmulatorDialog(editing, onDismiss = { editing = null }, onSave = { emu ->
        val saved = if (emu.id.isEmpty()) emu.copy(id = UUID.randomUUID().toString()) else emu
        save { c -> c.copy(emulators = c.emulators.filterNot { it.id == saved.id } + saved) }
        editing = null
    }, onRemove = { emu ->
        save { c -> c.copy(emulators = c.emulators.filterNot { it.id == emu.id }, defaults = c.defaults.filterValues { it != emu.id }) }
        editing = null
    })
    FolderDialog(editingFolder, config, onDismiss = { editingFolder = null }, onSave = { old, updated ->
        save { c -> c.copy(folders = c.folders.map { if (it.path == old.path) updated else it }) }
        editingFolder = updated
    }, onRemove = { folder ->
        save { c -> c.copy(folders = c.folders.filterNot { it.path == folder.path }) }
        editingFolder = null
    })
}

/** The known emulators (with the consoles they run) and "other emulator". */
@Composable
private fun PresetDialog(visible: Boolean, onDismiss: () -> Unit, onPick: (EmulatorPreset?) -> Unit) {
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(Res.string.emu_pc_add_title), maxWidth = 680.dp) {
        Column(Modifier.weight(1f, fill = false).popupHeightIn(620.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EmulatorPresets.all.forEachIndexed { i, preset ->
                NavRow(
                    preset.name, { onPick(preset) },
                    subtitle = preset.platforms.joinToString { PcPlatforms.byName(it.platform)?.displayName ?: it.platform },
                    icon = Icons.Filled.SportsEsports, initialFocus = i == 0,
                )
            }
            NavRow(stringResource(Res.string.emu_pc_custom), { onPick(null) }, subtitle = stringResource(Res.string.emu_pc_custom_sub), icon = Icons.Filled.Add)
        }
    }
}

/** Name, program, arguments and consoles of one emulator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmulatorDialog(emulator: PcEmulator?, onDismiss: () -> Unit, onSave: (PcEmulator) -> Unit, onRemove: (PcEmulator) -> Unit) {
    Modal(visible = emulator != null, onDismiss = onDismiss, title = stringResource(Res.string.emu_pc_edit_title), maxWidth = 780.dp) {
        val e = emulator ?: return@Modal
        var name by remember(e) { mutableStateOf(e.name) }
        var exe by remember(e) { mutableStateOf(e.executable) }
        var args by remember(e) { mutableStateOf(e.arguments) }
        var chosen by remember(e) { mutableStateOf(e.platforms.map { it.platform }.toSet()) }
        Column(Modifier.weight(1f, fill = false).popupHeightIn(640.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherTextField(stringResource(Res.string.emu_pc_name), name, { name = it })
            LauncherTextField(stringResource(Res.string.emu_pc_program), exe, { exe = it.trim().removeSurrounding("\"") }, placeholder = "C:\\Emulators\\Dolphin\\Dolphin.exe")
            val exeFound by androidx.compose.runtime.produceState<Boolean?>(null, exe) {
                value = if (exe.isBlank()) null else withContext(Dispatchers.IO) { File(exe).isFile }
            }
            if (exeFound == false) {
                Text(stringResource(Res.string.emu_pc_missing), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.danger)
            } else if (exe.isBlank() && e.presetId != null) {
                Text(stringResource(Res.string.emu_pc_not_detected), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            }
            LauncherButton(stringResource(Res.string.emu_pc_browse), icon = Icons.Filled.FolderOpen, onClick = {
                FilePickers.pickFile(exe.ifBlank { null })?.let { exe = it }
            })
            LauncherTextField(stringResource(Res.string.emu_pc_arguments), args, { args = it })
            Text(stringResource(Res.string.emu_pc_arguments_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            Text(stringResource(Res.string.emu_pc_platforms), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PcPlatforms.all.forEach { p ->
                    Chip(p.displayName, selected = p.name in chosen, onClick = { chosen = if (p.name in chosen) chosen - p.name else chosen + p.name })
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        // Below the scroll area: always in view.
        ButtonRow {
            LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
                if (name.isBlank() || exe.isBlank() || chosen.isEmpty()) return@LauncherButton
                // Consoles kept keep their settings (extensions, RetroArch core).
                val platforms = chosen.map { p -> e.platforms.firstOrNull { it.platform == p } ?: PcEmulatorPlatform(p) }
                onSave(e.copy(name = name.trim(), executable = exe, arguments = args.trim(), platforms = platforms))
            })
            if (e.id.isNotEmpty()) HoldToConfirmButton(stringResource(Res.string.emu_pc_remove), icon = Icons.Filled.Delete, onConfirmed = { onRemove(e) })
        }
    }
}

/** Path, console and emulator of one ROM folder. */
@Composable
private fun FolderDialog(
    folder: PcRomFolder?,
    config: PcEmulation,
    onDismiss: () -> Unit,
    onSave: (PcRomFolder, PcRomFolder) -> Unit,
    onRemove: (PcRomFolder) -> Unit,
) {
    Modal(visible = folder != null, onDismiss = onDismiss, title = folder?.path?.let { File(it).name.ifEmpty { it } }, maxWidth = 680.dp) {
        val f = folder ?: return@Modal
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LauncherTextField(stringResource(Res.string.emu_pc_folder_path), f.path, { onSave(f, f.copy(path = it.trim().removeSurrounding("\""))) })
            // null = one subfolder per console, recognised by name.
            val auto = stringResource(Res.string.emu_folder_auto)
            ChoiceRow(
                stringResource(Res.string.emu_folder_platform), listOf<String?>(null) + PcPlatforms.all.map { it.name }, f.platform,
                label = { id -> PcPlatforms.byName(id)?.displayName ?: auto },
                onSelect = { onSave(f, f.copy(platform = it, emulator = null)) },
                subtitle = stringResource(Res.string.emu_folder_platform_sub),
                initialFocus = true,
            )
            val platform = f.platform
            if (platform != null) {
                val choices = config.emulators.filter { it.supports(platform) }
                if (choices.size > 1) {
                    val default = stringResource(Res.string.emu_pc_platform_default)
                    ChoiceRow(
                        stringResource(Res.string.emu_pc_folder_emulator), listOf<String?>(null) + choices.map { it.id }, f.emulator,
                        label = { id -> choices.firstOrNull { it.id == id }?.name ?: default },
                        onSelect = { onSave(f, f.copy(emulator = it)) },
                    )
                }
            }
            HoldToConfirmButton(stringResource(Res.string.emu_folder_remove), icon = Icons.Filled.Delete, onConfirmed = { onRemove(f) })
        }
    }
}

/** Playnite's library and installation; null unless Playnite is installed (its library alone may be left over). */
internal fun playniteInstalled(): Pair<File, File>? =
    PlayniteImport.locate().let { (library, install) -> if (library != null && install != null) library to install else null }

/**
 * Takes the emulators, ROM folders and games added by hand over from Playnite ([found] from
 * [playniteInstalled]); known ones stay. Returns what was taken over, for the user.
 */
internal suspend fun importFromPlaynite(
    found: Pair<File, File>,
    coverDir: File,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
): String {
    val (library, install) = found
    val result = withContext(Dispatchers.IO) { PlayniteImport(PlayniteLibrary.read(library), library, install, coverDir).run() }
    update { s ->
        val (emulation, games) = PlayniteImport.merge(PcEmulation.decode(s.pcEmulation), CustomGame.decode(s.pcCustomGames), result)
        s.copy(pcEmulation = emulation.encode(), pcCustomGames = CustomGame.encode(games))
    }
    return org.jetbrains.compose.resources.getString(
        Res.string.emu_pc_playnite_done, result.emulators.size, result.folders.size, result.games.size,
    ) + if (result.skippedEmulators.isEmpty()) "" else "\n" +
        org.jetbrains.compose.resources.getString(Res.string.emu_pc_playnite_skipped, result.skippedEmulators.joinToString())
}
