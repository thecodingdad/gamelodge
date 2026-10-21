package io.github.thecodingdad.gamelodge.ui.screens.settings

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.R
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.shell.QuickTile
import io.github.thecodingdad.gamelodge.ui.shell.quickTileIcon
import io.github.thecodingdad.gamelodge.ui.shell.quickTileName
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

/** Which tiles the quick menu shows and in which order; app shortcuts included. */
@Composable
fun QuickMenuSettings(vm: LauncherViewModel, settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val tiles = settings.quickTiles
    var adding by remember { mutableStateOf(false) }
    fun save(list: List<String>) = update { it.copy(quickTiles = list) }

    SettingsPage(stringResource(Res.string.settings_quick_menu)) {
        Text(stringResource(Res.string.qt_settings_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        val tileContext = androidx.compose.ui.platform.LocalContext.current
        val unavailable = remember(tiles) { tiles.filterNot { QuickTile.isAvailable(it) }.toSet() }
        // Order by dragging the handle (controller: A on the handle, then up/down), like the filters.
        ReorderableList(tiles, id = { it }, onReorder = ::save) { index, id ->
            TileRow(id, unavailable = id in unavailable, initialFocus = index == 0, onRemove = { save(tiles - id) })
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.qt_add), modifier = Modifier.searchHighlight(stringResource(Res.string.qt_add), androidx.compose.foundation.shape.RoundedCornerShape(26.dp)), icon = Icons.Filled.Add, onClick = { adding = true })
            LauncherButton(stringResource(Res.string.qt_reset), icon = Icons.Filled.Restore, onClick = { save(QuickTile.defaults) })
        }

        // Opening the menu by touch while another app is in front.
        SectionHeader(stringResource(Res.string.qm_touch_section))
        var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
        val canOverlay = remember(tick) { android.provider.Settings.canDrawOverlays(tileContext) }
        SwitchRow(
            stringResource(Res.string.qm_edge_handle), settings.quickMenuEdgeHandle,
            { on ->
                update { it.copy(quickMenuEdgeHandle = on) }
                if (on && !canOverlay) {
                    runCatching {
                        tileContext.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${tileContext.packageName}")),
                        )
                    }
                }
            },
            subtitle = stringResource(if (settings.quickMenuEdgeHandle && !canOverlay) Res.string.qm_edge_handle_permission else Res.string.qm_edge_handle_sub),
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            NavRow(
                stringResource(Res.string.qm_tile_add), { requestQuickMenuTile(tileContext) },
                subtitle = stringResource(Res.string.qm_tile_sub), icon = Icons.Filled.Add,
            )
        } else {
            Text(stringResource(Res.string.qm_tile_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
        SeeAlso(SeeAlsoLink(stringResource(Res.string.settings_controls), SettingsTarget.Page(SettingsPageId.CONTROLS)), SeeAlsoLink(stringResource(Res.string.settings_controllers), SettingsTarget.Controllers))
    }

    // Picker: system tiles by group, then installed apps as shortcuts.
    val entries by vm.allEntries.collectAsState()
    val apps = remember(entries) {
        entries.mapNotNull { e -> e.localTarget?.packageName?.let { it to e.item.title } }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Only tiles the device has the hardware for.
    val available = remember { QuickTile.groups.flatMap { it.second }.filter { QuickTile.isAvailable(it) }.toSet() }
    Modal(visible = adding, onDismiss = { adding = false }, title = stringResource(Res.string.qt_add), maxWidth = 760.dp) {
        Column(Modifier.popupHeightIn(600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fun add(id: String) { save(tiles + id); adding = false }
            QuickTile.groups.forEach { (title, ids) ->
                val free = ids.filter { it !in tiles && it in available }
                if (free.isEmpty()) return@forEach
                SectionHeader(stringResource(title))
                TwoColumns(free) { id -> PickerTile(quickTileName(id), onClick = { add(id) }) { TileIcon(id) } }
            }
            val freeApps = apps.filter { QuickTile.appId(it.first) !in tiles }
            if (freeApps.isNotEmpty()) {
                SectionHeader(stringResource(Res.string.qt_group_apps))
                TwoColumns(freeApps) { (pkg, title) ->
                    PickerTile(title, onClick = { add(QuickTile.appId(pkg)) }) { TileIcon(QuickTile.appId(pkg)) }
                }
            }
        }
    }
}

/** Asks Android to add the quick menu tile to the quick settings (Android 13+). */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
private fun requestQuickMenuTile(context: android.content.Context) {
    val sbm = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(
        android.content.ComponentName(context, io.github.thecodingdad.gamelodge.system.QuickMenuTileService::class.java),
        context.getString(R.string.quick_menu),
        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_quick_menu),
        context.mainExecutor,
    ) { }
}

/** Icon of a tile: the app's own icon for app shortcuts, otherwise the tile's symbol. */
@Composable
private fun TileIcon(id: String) {
    if (id.startsWith(QuickTile.APP_PREFIX)) {
        io.github.thecodingdad.gamelodge.ui.components.AppIcon(id.removePrefix(QuickTile.APP_PREFIX), Modifier.size(28.dp))
    } else {
        Icon(quickTileIcon(id) ?: Icons.Filled.Apps, null, tint = LauncherTheme.colors.accent, modifier = Modifier.size(26.dp))
    }
}

/** Row of the tile list: icon, name and a delete button (the drag handle comes from the list). */
@Composable
private fun TileRow(id: String, unavailable: Boolean, initialFocus: Boolean, onRemove: () -> Unit) {
    RemovableRow(
        quickTileName(id), if (unavailable) stringResource(Res.string.qt_unavailable) else null, null, onRemove,
        removeLabel = stringResource(Res.string.qt_remove), leading = { TileIcon(id) }, initialFocus = initialFocus,
    )
}

/** Items two per row (a lone last item keeps half the width). */
@Composable
private fun <T> TwoColumns(items: List<T>, cell: @Composable (T) -> Unit) {
    items.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { Box(Modifier.weight(1f)) { cell(it) } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PickerTile(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        color = LauncherTheme.colors.surfaceRaised,
        focusScale = 1.03f,
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface, maxLines = 2)
        }
    }
}
