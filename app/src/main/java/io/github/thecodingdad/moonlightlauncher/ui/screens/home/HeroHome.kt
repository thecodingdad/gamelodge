package io.github.thecodingdad.moonlightlauncher.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.ui.RunningSession
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage
import io.github.thecodingdad.moonlightlauncher.ui.components.CoverCard
import io.github.thecodingdad.moonlightlauncher.ui.components.PublishAccentFrom
import io.github.thecodingdad.moonlightlauncher.ui.components.WideCard
import io.github.thecodingdad.moonlightlauncher.ui.components.coverModel
import io.github.thecodingdad.moonlightlauncher.ui.components.heroModel
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/** Callbacks shared by all home layouts. */
class HomeCallbacks(
    val onResume: (RunningSession) -> Unit,
    val onRunningMenu: (RunningSession) -> Unit,
    val lastFocusedKey: String?,
    val onFocusKey: (String) -> Unit,
    val animatedArtwork: Boolean,
)

/** PS5/Netflix style: large artwork of the focused game on top, rows below. */
@Composable
fun HeroHome(
    rows: HomeRows,
    callbacks: HomeCallbacks,
    collectionRows: List<Pair<String, List<LibraryEntry>>>,
    haRow: (@Composable () -> Unit)?,
    emptyContent: @Composable () -> Unit,
) {
    val actions = LocalEntryActions.current
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var focusedSession by remember { mutableStateOf<RunningSession?>(null) }
    var shown by remember { mutableStateOf<LibraryEntry?>(null) }
    // Avoid reloading artwork for every item while the user scrolls fast.
    val fallback = rows.running.firstOrNull()?.entry ?: rows.continuePlaying.firstOrNull()
        ?: rows.favorites.firstOrNull() ?: rows.recentlyAdded.firstOrNull()
    LaunchedEffect(focused, fallback) {
        if (focused != null) delay(140)
        shown = focused ?: fallback
    }
    PublishAccentFrom(shown?.coverModel())
    PadAction(PadButton.X) {
        val session = focusedSession
        if (session != null) callbacks.onRunningMenu(session) else focused?.let(actions::context)
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(shown, animationSpec = tween(400), label = "hero") { entry ->
            if (entry != null) {
                ArtworkImage(entry.heroModel(callbacks.animatedArtwork), entry.item.title, Modifier.fillMaxSize(), ContentScale.Crop, showTitleOnPlaceholder = false)
            }
        }
        val bg = LauncherTheme.colors.background
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.95f), 0.55f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to bg.copy(alpha = 0.92f), 1f to bg)))

        if (rows.isEmpty && rows.running.isEmpty()) {
            emptyContent()
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            HeroInfo(shown, Modifier.padding(horizontal = 40.dp).padding(top = 8.dp).heightIn(min = 150.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows.running.isNotEmpty()) {
                    item(key = "running") {
                        RowTitle(stringResource(R.string.home_running))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(rows.running, key = { it.host.uuid }) { session ->
                                val key = "running:${session.host.uuid}"
                                WideCard(
                                    title = session.appName,
                                    subtitle = stringResource(R.string.running_on, session.host.name),
                                    image = session.entry?.heroModel(),
                                    onClick = { callbacks.onResume(session) },
                                    onLongClick = { callbacks.onRunningMenu(session) },
                                    onFocused = { focused = session.entry; focusedSession = session; callbacks.onFocusKey(key) },
                                    modifier = Modifier.width(300.dp).height(170.dp)
                                        .restoreFocusIf(callbacks.lastFocusedKey == key || callbacks.lastFocusedKey == null),
                                )
                            }
                        }
                    }
                }
                homeRow("continue", R.string.home_continue, rows.continuePlaying, callbacks, firstRow = rows.running.isEmpty(), onFocus = { focused = it; focusedSession = null }, actions = actions)
                homeRow("favorites", R.string.home_favorites, rows.favorites, callbacks, firstRow = rows.running.isEmpty() && rows.continuePlaying.isEmpty(), onFocus = { focused = it; focusedSession = null }, actions = actions)
                if (haRow != null) item(key = "ha") { haRow() }
                collectionRows.forEach { (name, list) ->
                    homeRow("collection:$name", 0, list, callbacks, firstRow = false, onFocus = { focused = it; focusedSession = null }, actions = actions, titleText = name)
                }
                homeRow(
                    "recent", R.string.home_recently_added, rows.recentlyAdded, callbacks,
                    firstRow = rows.running.isEmpty() && rows.continuePlaying.isEmpty() && rows.favorites.isEmpty(),
                    onFocus = { focused = it; focusedSession = null }, actions = actions,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeRow(
    key: String,
    title: Int,
    entries: List<LibraryEntry>,
    callbacks: HomeCallbacks,
    firstRow: Boolean,
    onFocus: (LibraryEntry) -> Unit,
    actions: io.github.thecodingdad.moonlightlauncher.ui.shell.EntryActions,
    titleText: String? = null,
) {
    if (entries.isEmpty()) return
    item(key = key) {
        RowTitle(titleText ?: stringResource(title))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            items(entries, key = { it.item.id }) { entry ->
                val focusKey = "$key:${entry.item.id}"
                val isDefault = callbacks.lastFocusedKey == null && firstRow && entry === entries.first()
                CoverCard(
                    entry = entry,
                    width = LauncherTheme.dimens.coverWidth * 0.82f,
                    onClick = { actions.open(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = { onFocus(entry); callbacks.onFocusKey(focusKey) },
                    animated = callbacks.animatedArtwork,
                    modifier = Modifier.restoreFocusIf(isDefault || callbacks.lastFocusedKey == focusKey),
                )
            }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = LauncherTheme.colors.onSurface,
        modifier = Modifier.padding(start = 40.dp, top = 6.dp),
    )
}

@Composable
private fun HeroInfo(entry: LibraryEntry?, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry == null) return@Column
        if (entry.item.logoUrl != null) {
            ArtworkImage(
                entry.item.logoUrl, entry.item.title,
                Modifier.height(84.dp).widthIn(max = 420.dp), ContentScale.Fit, showTitleOnPlaceholder = false,
            )
        } else {
            Text(
                entry.item.title,
                style = MaterialTheme.typography.displaySmall,
                color = LauncherTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MetaLine(entry)
        entry.item.description?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
    }
}
