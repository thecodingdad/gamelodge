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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun GridHome(games: List<LibraryEntry>, callbacks: HomeCallbacks, emptyContent: @Composable () -> Unit) {
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
    val shown = focused ?: sorted.first()
    PublishAccentFrom(shown.coverModel())
    PadAction(PadButton.X) { focused?.let(actions::context) }

    Row(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(LauncherTheme.dimens.coverWidth * 0.7f + 8.dp),
            contentPadding = PaddingValues(start = 36.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.weight(1f).focusRestorer(),
        ) {
            items(sorted, key = { it.item.id }) { entry ->
                val key = "grid:${entry.item.id}"
                CoverCard(
                    entry = entry,
                    width = LauncherTheme.dimens.coverWidth * 0.7f,
                    onClick = { actions.open(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = { focused = entry; callbacks.onFocusKey(key) },
                    animated = callbacks.animatedArtwork,
                    modifier = Modifier.restoreFocusIf(
                        callbacks.lastFocusedKey == key || (callbacks.lastFocusedKey?.startsWith("grid:") != true && entry === sorted.first()),
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
