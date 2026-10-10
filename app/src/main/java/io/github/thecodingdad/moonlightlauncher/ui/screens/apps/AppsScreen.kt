package io.github.thecodingdad.moonlightlauncher.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.thecodingdad.moonlightlauncher.data.library.BuiltinFilter
import io.github.thecodingdad.moonlightlauncher.data.library.ResolvedFilter
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.filterLabel
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import io.github.thecodingdad.moonlightlauncher.ui.components.AppTile
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Regular apps, separate from the game library: device apps first, then each host's apps. */
@Composable
fun AppsScreen(
    apps: List<LibraryEntry>,
    filters: List<ResolvedFilter>,
    lastFocusedKey: String?,
    openFilterSettings: () -> Unit,
    onFocusKey: (String) -> Unit,
) {
    val actions = LocalEntryActions.current
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = filters.firstOrNull { it.key == selectedKey }
    PadAction(PadButton.X) { focused?.let(actions::context) }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(R.string.hint_start)),
            ButtonHint(PadButton.X, stringResource(R.string.hint_options), onClick = { focused?.let(actions::context) }),
        ),
    )

    // "All" (or no filter) shows sections per source; any other filter a flat list.
    val flat = selected != null && selected.builtin != BuiltinFilter.ALL
    val source = if (flat) selected!!.entries else apps
    val sorted = remember(source) { source.sortedBy { TitleNormalizer.sortKey(it.item.title) } }
    val local = if (flat) sorted else sorted.filter { it.localTarget != null }
    val byHost = if (flat) emptyMap() else sorted.filter { it.localTarget == null }
        .groupBy { it.remoteTargets.firstOrNull()?.hostName.orEmpty() }

    Column(Modifier.fillMaxSize()) {
    run {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            rowItems(filters, key = { "filter:${it.key}" }) { f ->
                val isSelected = f.key == selected?.key || (selected == null && f.builtin == BuiltinFilter.ALL)
                Chip(filterLabel(f), selected = isSelected, onClick = { selectedKey = f.key })
            }
            // App filters are configured on their own page, separate from the game filters.
            item(key = "edit") {
                Chip(
                    "", selected = false, onClick = openFilterSettings,
                    icon = { androidx.compose.material3.Icon(Icons.Filled.Edit, stringResource(R.string.filters_edit)) },
                )
            }
        }
    }
    if (sorted.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.apps_empty), color = LauncherTheme.colors.onSurfaceMuted, style = MaterialTheme.typography.titleMedium)
        }
        return@Column
    }

    val first = local.firstOrNull() ?: byHost.values.firstOrNull()?.firstOrNull()
    val deviceTitle = stringResource(R.string.apps_on_device)
    val selectedTitle = selected?.let { filterLabel(it) }.orEmpty()
    val hostTitles = byHost.keys.associateWith { stringResource(R.string.apps_on_host, it) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(LauncherTheme.dimens.iconTile + 36.dp),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        fun section(title: String, list: List<LibraryEntry>) {
            if (list.isEmpty()) return
            item(span = { GridItemSpan(maxLineSpan) }, key = "header:$title") {
                Text(title, style = MaterialTheme.typography.titleLarge, color = LauncherTheme.colors.onSurface, modifier = Modifier.padding(top = 10.dp))
            }
            items(list, key = { it.item.id }) { entry ->
                val key = "apps:${entry.item.id}"
                AppTile(
                    entry = entry,
                    onClick = { actions.play(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = { focused = entry; onFocusKey(key) },
                    modifier = Modifier.restoreFocusIf(
                        lastFocusedKey == key || (lastFocusedKey?.startsWith("apps:") != true && entry === first),
                    ),
                )
            }
        }
        if (flat) {
            section(selectedTitle, local)
        } else {
            section(deviceTitle, local)
            byHost.forEach { (host, list) -> section(hostTitles.getValue(host), list) }
        }
    }
    }
}
