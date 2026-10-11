package io.github.thecodingdad.moonlightlauncher.ui.screens.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.ui.components.CoverCard
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.PublishAccentFrom
import io.github.thecodingdad.moonlightlauncher.ui.components.WideCard
import io.github.thecodingdad.moonlightlauncher.ui.components.coverModel
import io.github.thecodingdad.moonlightlauncher.ui.components.heroModel
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Switch / Steam Deck style: one big row of recent games plus round quick buttons. */
@Composable
fun CarouselHome(
    rows: HomeRows,
    callbacks: HomeCallbacks,
    openTab: (Int) -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    val actions = LocalEntryActions.current
    val items = remember(rows) {
        (rows.continuePlaying + rows.favorites + rows.recentlyAdded).distinctBy { it.item.id }.take(24)
    }
    if (items.isEmpty() && rows.running.isEmpty()) {
        Box(Modifier.padding(LocalShellInsets.current)) { emptyContent() }
        return
    }
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var focusedSession by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.ui.RunningSession?>(null) }
    var libraryTileFocused by remember { mutableStateOf(false) }
    val shown = focused ?: rows.running.firstOrNull()?.entry ?: items.firstOrNull()
    PublishAccentFrom(shown?.coverModel())
    // Start: quick start of the focused game (no hint, a shortcut for those who know it).
    PadAction(PadButton.START) { focused?.let(actions::play) }
    PadAction(PadButton.X) {
        val session = focusedSession
        if (session != null) callbacks.onRunningMenu(session) else focused?.let(actions::context)
    }
    val listState = rememberLazyListState()
    val atStart = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
        (focused == null && focusedSession == null && !libraryTileFocused ||
            if (rows.running.isNotEmpty()) focusedSession === rows.running.first() else focused?.item?.id == items.firstOrNull()?.item?.id)
    androidx.compose.runtime.LaunchedEffect(atStart) { callbacks.onAtStart(atStart) }
    // Same artwork backdrop as the hero layout, without reloading on every fast scroll step.
    var backdrop by remember { mutableStateOf<LibraryEntry?>(null) }
    androidx.compose.runtime.LaunchedEffect(shown) {
        if (backdrop != null) kotlinx.coroutines.delay(140)
        backdrop = shown
    }

    Box(Modifier.fillMaxSize()) {
    androidx.compose.animation.Crossfade(backdrop, animationSpec = androidx.compose.animation.core.tween(400), label = "carouselHero") { entry ->
        if (entry != null) {
            io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage(
                entry.heroModel(callbacks.animatedArtwork), entry.item.title, Modifier.fillMaxSize(),
                androidx.compose.ui.layout.ContentScale.Crop, showTitleOnPlaceholder = false,
            )
        }
    }
    val bg = LauncherTheme.colors.background
    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to bg.copy(alpha = 0.55f), 0.45f to bg.copy(alpha = 0.35f), 0.8f to bg.copy(alpha = 0.8f), 1f to bg.copy(alpha = 0.55f),
            ),
        ),
    )
    Column(Modifier.fillMaxSize().padding(LocalShellInsets.current).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusRestorer(),
        ) {
            items(rows.running, key = { "run:" + it.host.uuid }) { session ->
                WideCard(
                    title = session.appName,
                    subtitle = stringResource(R.string.running_on, session.host.name),
                    image = session.entry?.heroModel(),
                    onClick = { callbacks.onResume(session) },
                    onLongClick = { callbacks.onRunningMenu(session) },
                    onFocused = { focused = session.entry; focusedSession = session; libraryTileFocused = false },
                    modifier = Modifier.width(340.dp).height(225.dp).restoreFocusIf(callbacks.lastFocusedKey == null),
                )
            }
            items(items, key = { it.item.id }) { entry ->
                val key = "carousel:${entry.item.id}"
                val isFocused = focused?.item?.id == entry.item.id
                val width by animateDpAsState(if (isFocused) 150.dp else 112.dp, label = "carouselWidth")
                CoverCard(
                    entry = entry,
                    width = width,
                    onClick = { actions.open(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = { focused = entry; focusedSession = null; libraryTileFocused = false; callbacks.onFocusKey(key) },
                    animated = callbacks.animatedArtwork,
                    modifier = Modifier.restoreFocusIf(
                        callbacks.lastFocusedKey == key ||
                            (rows.running.isEmpty() && callbacks.lastFocusedKey?.startsWith("carousel:") != true && entry === items.first()),
                    ),
                )
            }
            // Like the Switch home: the full library sits at the end of the row.
            item(key = "library") {
                LibraryTile(
                    onClick = { openTab(1) },
                    onFocused = { focused = null; focusedSession = null; libraryTileFocused = true; callbacks.onFocusKey("carousel:library") },
                    modifier = Modifier.restoreFocusIf(callbacks.lastFocusedKey == "carousel:library"),
                )
            }
        }
        if (libraryTileFocused) {
            Text(
                stringResource(R.string.tab_library), style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        } else shown?.let {
            Column(Modifier.padding(horizontal = 48.dp)) {
                Text(it.item.title, style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaLine(it)
            }
        }
    }
    }
}

@Composable
private fun LibraryTile(onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.tab_library)
    FocusSurface(
        onClick = onClick, onFocused = onFocused, shape = CircleShape,
        color = LauncherTheme.colors.surfaceRaised, modifier = modifier.size(112.dp), focusScale = 1.1f,
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Apps, label, tint = LauncherTheme.colors.onSurface, modifier = Modifier.size(40.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = LauncherTheme.colors.onSurface, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
