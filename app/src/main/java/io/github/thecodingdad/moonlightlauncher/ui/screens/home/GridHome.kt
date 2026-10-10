package io.github.thecodingdad.moonlightlauncher.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage
import io.github.thecodingdad.moonlightlauncher.ui.components.CoverCard
import io.github.thecodingdad.moonlightlauncher.ui.components.PublishAccentFrom
import io.github.thecodingdad.moonlightlauncher.ui.components.coverModel
import io.github.thecodingdad.moonlightlauncher.ui.components.heroModel
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Playnite fullscreen style: all games as a cover grid with a detail column on the right. */
@Composable
fun GridHome(
    games: List<LibraryEntry>,
    running: List<io.github.thecodingdad.moonlightlauncher.ui.RunningSession>,
    callbacks: HomeCallbacks,
    emptyContent: @Composable () -> Unit,
) {
    val actions = LocalEntryActions.current
    if (games.isEmpty()) {
        emptyContent()
        return
    }
    // Recently played first, then alphabetical: the most likely next pick is top-left.
    val sorted = remember(games) {
        games.sortedWith(compareByDescending<LibraryEntry> { it.item.lastPlayedAt ?: 0L }.thenBy { TitleNormalizer.sortKey(it.item.title) })
    }
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var focusedSession by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.ui.RunningSession?>(null) }
    val shown = focused ?: running.firstOrNull()?.entry ?: sorted.first()
    PublishAccentFrom(shown.coverModel())
    // Start: quick start of the focused game (no hint, a shortcut for those who know it).
    PadAction(PadButton.START) { focused?.let(actions::play) }
    PadAction(PadButton.X) {
        val session = focusedSession
        if (session != null) callbacks.onRunningMenu(session) else focused?.let(actions::context)
    }
    val coverWidth = LauncherTheme.dimens.coverWidth * 0.7f

    Row(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(LauncherTheme.dimens.coverWidth * 0.7f + 8.dp),
            contentPadding = PaddingValues(start = 36.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.weight(1f).focusRestorer(),
        ) {
            // A running game spans two cells, like the wide cards of the other layouts.
            items(running, key = { "run:" + it.host.uuid }, span = { GridItemSpan(minOf(2, maxLineSpan)) }) { session ->
                val key = "grid:run:${session.host.uuid}"
                // Grid cells force their width onto the covers, so derive the height from the
                // real cell width: two cells plus the gap, covers are 2:3.
                androidx.compose.foundation.layout.BoxWithConstraints {
                val cellWidth = if (maxWidth > coverWidth * 1.6f) (maxWidth - 16.dp) / 2 else maxWidth
                io.github.thecodingdad.moonlightlauncher.ui.components.WideCard(
                    title = session.appName,
                    subtitle = stringResource(R.string.running_on, session.host.name),
                    image = session.entry?.heroModel(),
                    onClick = { callbacks.onResume(session) },
                    onLongClick = { callbacks.onRunningMenu(session) },
                    onFocused = { focused = session.entry; focusedSession = session; callbacks.onFocusKey(key) },
                    modifier = Modifier.fillMaxWidth().height(cellWidth * 1.5f)
                        .restoreFocusIf(callbacks.lastFocusedKey == key || callbacks.lastFocusedKey?.startsWith("grid:") != true),
                )
                }
            }
            items(sorted, key = { it.item.id }) { entry ->
                val key = "grid:${entry.item.id}"
                CoverCard(
                    entry = entry,
                    width = LauncherTheme.dimens.coverWidth * 0.7f,
                    onClick = { actions.open(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = { focused = entry; focusedSession = null; callbacks.onFocusKey(key) },
                    animated = callbacks.animatedArtwork,
                    modifier = Modifier.restoreFocusIf(
                        callbacks.lastFocusedKey == key ||
                            (running.isEmpty() && callbacks.lastFocusedKey?.startsWith("grid:") != true && entry === sorted.first()),
                    ),
                )
            }
        }
        Crossfade(shown, label = "gridDetail", modifier = Modifier.width(340.dp).fillMaxHeight()) { entry ->
            Column(
                Modifier.fillMaxSize().background(LauncherTheme.colors.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ArtworkImage(
                    entry.heroModel(callbacks.animatedArtwork), entry.item.title,
                    Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)), ContentScale.Crop, showTitleOnPlaceholder = false,
                )
                Text(entry.item.title, style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaLine(entry)
                entry.item.description?.let {
                    Box(Modifier.weight(1f)) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
