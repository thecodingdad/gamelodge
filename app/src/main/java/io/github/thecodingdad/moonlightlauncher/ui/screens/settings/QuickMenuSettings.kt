package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
import io.github.thecodingdad.moonlightlauncher.ui.shell.QuickTile
import io.github.thecodingdad.moonlightlauncher.ui.shell.quickTileIcon
import io.github.thecodingdad.moonlightlauncher.ui.shell.quickTileName
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Which tiles the quick menu shows and in which order; app shortcuts included. */
@Composable
fun QuickMenuSettings(vm: LauncherViewModel, settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val tiles = settings.quickTiles
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun save(list: List<String>) = update { it.copy(quickTiles = list) }

    SettingsPage(stringResource(R.string.settings_quick_menu)) {
        Text(stringResource(R.string.qt_settings_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        val tileContext = androidx.compose.ui.platform.LocalContext.current
        val unavailable = remember(tiles) { tiles.filterNot { QuickTile.isAvailable(tileContext, it) }.toSet() }
        tiles.forEachIndexed { index, id ->
            NavRow(
                quickTileName(id), { selected = id },
                subtitle = if (id in unavailable) stringResource(R.string.qt_unavailable) else null,
                icon = quickTileIcon(id) ?: Icons.Filled.Apps, initialFocus = index == 0,
            )
        }
        ButtonRow {
            LauncherButton(stringResource(R.string.qt_add), icon = Icons.Filled.Add, onClick = { adding = true })
            LauncherButton(stringResource(R.string.qt_reset), icon = Icons.Filled.Restore, onClick = { save(QuickTile.defaults) })
        }
    }

    Modal(visible = selected != null, onDismiss = { selected = null }, title = selected?.let { quickTileName(it) }, maxWidth = 560.dp) {
        val id = selected ?: return@Modal
        val i = tiles.indexOf(id)
        ButtonRow {
            LauncherButton(stringResource(R.string.move_up), icon = Icons.Filled.ArrowUpward, onClick = {
                if (i > 0) save(tiles.toMutableList().apply { add(i - 1, removeAt(i)) })
            })
            LauncherButton(stringResource(R.string.move_down), icon = Icons.Filled.ArrowDownward, onClick = {
                if (i in 0 until tiles.lastIndex) save(tiles.toMutableList().apply { add(i + 1, removeAt(i)) })
            })
            LauncherButton(stringResource(R.string.qt_remove), icon = Icons.Filled.Delete, onClick = {
                save(tiles - id); selected = null
            })
        }
    }

    // Picker: system tiles by group, then installed apps as shortcuts.
    val entries by vm.allEntries.collectAsState()
    val apps = remember(entries) {
        entries.mapNotNull { e -> e.localTarget?.packageName?.let { it to e.item.title } }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Only tiles the device has the hardware for.
    val available = remember { QuickTile.groups.flatMap { it.second }.filter { QuickTile.isAvailable(context, it) }.toSet() }
    Modal(visible = adding, onDismiss = { adding = false }, title = stringResource(R.string.qt_add), maxWidth = 640.dp) {
        Column(Modifier.heightIn(max = 600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile.groups.forEach { (title, ids) ->
                val free = ids.filter { it !in tiles && it in available }
                if (free.isEmpty()) return@forEach
                SectionHeader(stringResource(title))
                free.forEach { id ->
                    LauncherButton(quickTileName(id), icon = quickTileIcon(id), modifier = Modifier.fillMaxWidth(), onClick = {
                        save(tiles + id); adding = false
                    })
                }
            }
            val freeApps = apps.filter { QuickTile.appId(it.first) !in tiles }
            if (freeApps.isNotEmpty()) {
                SectionHeader(stringResource(R.string.qt_group_apps))
                freeApps.forEach { (pkg, title) ->
                    LauncherButton(title, icon = Icons.Filled.Apps, modifier = Modifier.fillMaxWidth(), onClick = {
                        save(tiles + QuickTile.appId(pkg)); adding = false
                    })
                }
            }
        }
    }
}
