package io.github.thecodingdad.gamelodge.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.data.emulation.CatalogPlatform
import io.github.thecodingdad.gamelodge.data.emulation.CommandExtra
import io.github.thecodingdad.gamelodge.data.emulation.CustomEmulator
import io.github.thecodingdad.gamelodge.data.emulation.RomFolder
import io.github.thecodingdad.gamelodge.data.emulation.RomScanner
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.screens.library.Chip
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

/** Platforms whose emulator needs BIOS/firmware files set up in the emulator itself. */
private val needsBios = setOf("psx", "ps2", "ps3", "xbox", "xbox360", "dreamcast", "n3ds", "switch")

/**
 * Emulation: ROM folders (storage access framework, optionally "all files" access), the emulator
 * per platform and own emulators. Start commands come from ES-DE's Android configuration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmulatorSettings(vm: LauncherViewModel) {
    val context = LocalContext.current
    val emulation = context.container.emulation
    val catalog = emulation.catalog
    val config by emulation.config.collectAsState()
    val scanning by emulation.scanning.collectAsState()
    val found by emulation.found.collectAsState()
    val unreadable by emulation.unreadable.collectAsState()
    val entries by vm.allEntries.collectAsState()
    val scope = rememberCoroutineScope()
    // Installed apps and permissions change outside the app: re-read on every resume.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val allFiles = remember(tick) { RomScanner.allFilesAccess() }

    var editingFolder by remember { mutableStateOf<RomFolder?>(null) }
    var editingCustom by remember { mutableStateOf<CustomEmulator?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val name = folderName(docId)
        // A folder named like a system ("snes") is that platform, else one subfolder per system.
        val platform = catalog.platformForFolder(name.substringAfterLast('/'))?.id
        scope.launch {
            emulation.update { c -> c.copy(folders = c.folders.filterNot { it.uri == uri.toString() } + RomFolder(uri.toString(), name, platform)) }
        }
    }

    SettingsPage(stringResource(Res.string.settings_emulators)) {
        Text(stringResource(Res.string.emu_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)

        SectionHeader(stringResource(Res.string.emu_folders))
        config.folders.forEach { folder ->
            val missing = unreadable.any { it.uri == folder.uri }
            NavRow(
                folder.name, { editingFolder = folder },
                subtitle = if (missing) stringResource(Res.string.emu_folder_no_access)
                else catalog.platform(folder.platform)?.displayName ?: stringResource(Res.string.emu_folder_auto),
                icon = Icons.Filled.Folder,
            )
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.emu_folder_add), modifier = Modifier.searchHighlight(stringResource(Res.string.emu_folder_add), androidx.compose.foundation.shape.RoundedCornerShape(26.dp)), icon = Icons.Filled.CreateNewFolder, onClick = { pickFolder.launch(null) })
            if (config.folders.isNotEmpty()) {
                LauncherButton(
                    stringResource(if (scanning) Res.string.emu_scanning else Res.string.emu_scan), icon = Icons.Filled.Refresh,
                    onClick = { if (!scanning) scope.launch { emulation.rescan() } },
                )
            }
        }
        if (found != null && config.folders.isNotEmpty()) {
            Text((found ?: 0).let { pluralStringResource(Res.plurals.emu_found, it, it) }, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SwitchRow(
                stringResource(Res.string.emu_all_files), allFiles,
                {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                },
                subtitle = stringResource(Res.string.emu_all_files_sub),
            )
        }

        // Platforms with ROMs, folders or an installed emulator: their emulator.
        val withEmulator = remember(tick, config) { catalog.platforms.filter { emulation.choicesOf(it.id).isNotEmpty() }.mapTo(HashSet()) { it.id } }
        val used = buildSet {
            entries.flatMap { it.romTargets }.mapNotNullTo(this) { it.romPlatform }
            config.folders.mapNotNullTo(this) { it.platform }
            addAll(withEmulator)
        }
        SectionHeader(stringResource(Res.string.emu_emulators))
        if (used.isEmpty()) {
            Text(stringResource(Res.string.emu_no_platforms), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        }
        catalog.platforms.filter { it.id in used }.forEach { platform ->
            PlatformEmulatorRow(platform, tick)
        }

        SectionHeader(stringResource(Res.string.emu_custom))
        Text(stringResource(Res.string.emu_custom_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        config.custom.forEach { emu ->
            NavRow(emu.name, { editingCustom = emu }, subtitle = emu.packageName, icon = Icons.Filled.SportsEsports)
        }
        LauncherButton(stringResource(Res.string.emu_custom_add), icon = Icons.Filled.Add, onClick = {
            editingCustom = CustomEmulator(id = java.util.UUID.randomUUID().toString(), name = "", packageName = "")
        })

        Text(stringResource(Res.string.emu_esde_notice), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    }

    FolderDialog(editingFolder, catalog.platforms, onDismiss = { editingFolder = null }, onSave = { updated ->
        scope.launch { emulation.update { c -> c.copy(folders = c.folders.map { if (it.uri == updated.uri) updated else it }) } }
        editingFolder = updated
    }, onRemove = { removed ->
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(removed.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        scope.launch { emulation.update { c -> c.copy(folders = c.folders.filterNot { it.uri == removed.uri }) } }
        editingFolder = null
    })

    CustomEmulatorDialog(editingCustom, catalog.platforms, onDismiss = { editingCustom = null }, onSave = { emu ->
        scope.launch { emulation.update { c -> c.copy(custom = c.custom.filterNot { it.id == emu.id } + emu) } }
        editingCustom = null
    }, onRemove = { emu ->
        scope.launch { emulation.update { c -> c.copy(custom = c.custom.filterNot { it.id == emu.id }) } }
        editingCustom = null
    })
}

/** "primary:ROMs/snes" → "ROMs/snes"; other volumes keep their id ("1234-ABCD/ROMs"). */
private fun folderName(docId: String): String {
    val volume = docId.substringBefore(':')
    val rest = docId.substringAfter(':')
    return when {
        volume == "primary" -> rest.ifEmpty { "/" }
        rest.isEmpty() -> volume
        else -> "$volume/$rest"
    }
}

/** Default emulator of one platform among the installed ones. */
@Composable
private fun PlatformEmulatorRow(platform: CatalogPlatform, tick: Int) {
    val emulation = LocalContext.current.container.emulation
    val config by emulation.config.collectAsState()
    val scope = rememberCoroutineScope()
    val choices = remember(tick, config) { emulation.choicesOf(platform.id) }
    val experimental = stringResource(Res.string.emu_experimental)
    val bios = if (platform.id in needsBios) stringResource(Res.string.emu_bios_hint) else null
    if (choices.isEmpty()) {
        NavRow(
            platform.displayName, {},
            subtitle = listOfNotNull(stringResource(Res.string.emu_none_installed), bios).joinToString(" · "),
            icon = Icons.Filled.SportsEsports,
        )
        return
    }
    val current = emulation.resolve(platform.id, null)?.key ?: choices.first().key
    ChoiceRow(
        platform.displayName, choices.map { it.key }, current,
        label = { key -> choices.first { it.key == key }.let { if (it.experimental) "${it.label} ($experimental)" else it.label } },
        onSelect = { key -> scope.launch { emulation.update { it.copy(defaults = it.defaults + (platform.id to key)) } } },
        subtitle = bios,
    )
}

@Composable
private fun FolderDialog(
    folder: RomFolder?,
    platforms: List<CatalogPlatform>,
    onDismiss: () -> Unit,
    onSave: (RomFolder) -> Unit,
    onRemove: (RomFolder) -> Unit,
) {
    Modal(visible = folder != null, onDismiss = onDismiss, title = folder?.name, maxWidth = 640.dp) {
        val f = folder ?: return@Modal
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // null = one subfolder per system, recognised by name.
            ChoiceRow(
                stringResource(Res.string.emu_folder_platform), listOf<String?>(null) + platforms.map { it.id }, f.platform,
                label = { id -> platforms.firstOrNull { it.id == id }?.displayName ?: stringResource(Res.string.emu_folder_auto) },
                onSelect = { onSave(f.copy(platform = it)) },
                subtitle = stringResource(Res.string.emu_folder_platform_sub),
                initialFocus = true,
            )
            HoldToConfirmButton(stringResource(Res.string.emu_folder_remove), icon = Icons.Filled.Delete, onConfirmed = { onRemove(f) })
        }
    }
}

/**
 * Own emulator: package, activity, action, data and extras with ES-DE's placeholders. Extras
 * separated by ";" as "name=value" (prefix "bool:", "int:" … for other types).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomEmulatorDialog(
    emulator: CustomEmulator?,
    platforms: List<CatalogPlatform>,
    onDismiss: () -> Unit,
    onSave: (CustomEmulator) -> Unit,
    onRemove: (CustomEmulator) -> Unit,
) {
    Modal(visible = emulator != null, onDismiss = onDismiss, title = stringResource(Res.string.emu_custom_title), maxWidth = 760.dp) {
        val e = emulator ?: return@Modal
        var name by remember(e.id) { mutableStateOf(e.name) }
        var pkg by remember(e.id) { mutableStateOf(e.packageName) }
        var activity by remember(e.id) { mutableStateOf(e.activity.orEmpty()) }
        var action by remember(e.id) { mutableStateOf(e.action.orEmpty()) }
        var data by remember(e.id) { mutableStateOf(e.data.orEmpty()) }
        var extras by remember(e.id) {
            mutableStateOf(e.extras.joinToString("; ") { x -> (if (x.type == "string") "" else "${x.type}:") + "${x.name}=${x.value}" })
        }
        var chosen by remember(e.id) { mutableStateOf(e.platforms.toSet()) }
        Column(Modifier.heightIn(max = 600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherTextField(stringResource(Res.string.emu_custom_name), name, { name = it })
            LauncherTextField(stringResource(Res.string.emu_custom_package), pkg, { pkg = it.trim() }, placeholder = "org.example.emulator")
            LauncherTextField(stringResource(Res.string.emu_custom_activity), activity, { activity = it.trim() }, placeholder = ".EmulationActivity")
            LauncherTextField(stringResource(Res.string.emu_custom_action), action, { action = it.trim() }, placeholder = "android.intent.action.VIEW")
            LauncherTextField(stringResource(Res.string.emu_custom_data), data, { data = it.trim() }, placeholder = "%ROMSAF%")
            LauncherTextField(stringResource(Res.string.emu_custom_extras), extras, { extras = it }, placeholder = "bootPath=%ROMSAF%; bool:resumeState=false")
            Text(stringResource(Res.string.emu_custom_placeholders), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            Text(stringResource(Res.string.emu_custom_platforms), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                platforms.forEach { p ->
                    Chip(p.displayName, selected = p.id in chosen, onClick = { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id })
                }
            }
            ButtonRow {
                LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
                    if (name.isBlank() || pkg.isBlank()) return@LauncherButton
                    onSave(
                        e.copy(
                            name = name.trim(), packageName = pkg, activity = activity.ifBlank { null }, action = action.ifBlank { null },
                            data = data.ifBlank { null }, extras = parseExtras(extras), platforms = chosen.toList(),
                        ),
                    )
                })
                if (e.name.isNotBlank()) HoldToConfirmButton(stringResource(Res.string.emu_custom_remove), icon = Icons.Filled.Delete, onConfirmed = { onRemove(e) })
            }
        }
    }
}

private fun parseExtras(text: String): List<CommandExtra> = text.split('\n', ';').mapNotNull { line ->
    val l = line.trim().takeIf { '=' in it } ?: return@mapNotNull null
    val type = l.substringBefore(':', "").takeIf { it in setOf("bool", "int", "long", "array") && l.indexOf(':') < l.indexOf('=') }
    val rest = if (type != null) l.substringAfter(':') else l
    CommandExtra(type ?: "string", rest.substringBefore('=').trim(), rest.substringAfter('=').trim())
}
