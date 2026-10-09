package io.github.thecodingdad.moonlightlauncher.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
fun AppsScreen(apps: List<LibraryEntry>, lastFocusedKey: String?, onFocusKey: (String) -> Unit) {
    val actions = LocalEntryActions.current
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    PadAction(PadButton.X) { focused?.let(actions::context) }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(R.string.hint_start)),
            ButtonHint(PadButton.X, stringResource(R.string.hint_options), onClick = { focused?.let(actions::context) }),
        ),
    )

    val sorted = remember(apps) { apps.sortedBy { TitleNormalizer.sortKey(it.item.title) } }
    val local = sorted.filter { it.localTarget != null }
    val byHost = sorted.filter { it.localTarget == null }
        .groupBy { it.remoteTargets.firstOrNull()?.hostName.orEmpty() }

    if (sorted.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.apps_empty), color = LauncherTheme.colors.onSurfaceMuted, style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val first = local.firstOrNull() ?: byHost.values.firstOrNull()?.firstOrNull()
    val deviceTitle = stringResource(R.string.apps_on_device)
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
        section(deviceTitle, local)
        byHost.forEach { (host, list) -> section(hostTitles.getValue(host), list) }
    }
}
