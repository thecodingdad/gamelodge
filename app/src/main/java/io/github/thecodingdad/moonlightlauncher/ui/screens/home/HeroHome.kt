package io.github.thecodingdad.moonlightlauncher.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.ResolvedFilter
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.filterLabel
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HeroHome(
    rows: HomeRows,
    callbacks: HomeCallbacks,
    filterRows: List<ResolvedFilter>,
    haRow: (@Composable () -> Unit)?,
    emptyContent: @Composable () -> Unit,
) {
    val actions = LocalEntryActions.current
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var focusedSession by remember { mutableStateOf<RunningSession?>(null) }
    var shown by remember { mutableStateOf<LibraryEntry?>(null) }
    // Avoid reloading artwork for every item while the user scrolls fast.
    val fallback = rows.running.firstOrNull()?.entry ?: filterRows.firstOrNull()?.entries?.firstOrNull()
        ?: rows.continuePlaying.firstOrNull() ?: rows.recentlyAdded.firstOrNull()
    LaunchedEffect(focused, fallback) {
        if (focused != null) delay(140)
        shown = focused ?: fallback
    }
    PublishAccentFrom(shown?.coverModel())
    // Start: quick start of the focused game (no hint, a shortcut for those who know it).
    PadAction(PadButton.START) { focused?.let(actions::play) }
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
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            // Dark behind the row titles for legibility, lighter again at the very bottom so the
            // artwork shows through behind the hint bar instead of ending in solid black.
            0.35f to Color.Transparent, 0.65f to bg.copy(alpha = 0.8f), 0.85f to bg.copy(alpha = 0.8f), 1f to bg.copy(alpha = 0.55f),
        )))

        val insets = LocalShellInsets.current
        if (rows.isEmpty && rows.running.isEmpty()) {
            Box(Modifier.padding(insets)) { emptyContent() }
            return@Box
        }

        // Only the top inset pads the column: the rows scroll on behind the translucent hint bar.
        Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding())) {
            HeroInfo(shown, Modifier.padding(horizontal = 40.dp).padding(top = 4.dp).height(160.dp))
            // The focused row always snaps to the top of the list, right below the info block
            // (like TV home screens): moving sideways never scrolls vertically, so rows don't jump.
            // The list itself ignores bring-into-view; the rows inside keep the normal spec.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val outerSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            var focusedRow by remember { mutableStateOf<String?>(null) }
            val rowKeys = buildList {
                if (rows.running.isNotEmpty()) add("running")
                filterRows.forEachIndexed { index, row ->
                    if (index == 2 && haRow != null) add("ha")
                    if (row.entries.isNotEmpty()) add("row:${row.key}")
                }
                if (filterRows.size < 2 && haRow != null) add("ha")
            }
            var snapped by remember { mutableStateOf(false) }
            // Rows arrive in stages at start (e.g. the smart home row before the game rows). The
            // list keeps its first visible item when rows are inserted above it, so without a
            // remembered position jump back to the top whenever the first row changes.
            val firstRowKey = rowKeys.firstOrNull()
            LaunchedEffect(firstRowKey) {
                if (firstRowKey != null && callbacks.lastFocusedKey == null && !snapped) listState.scrollToItem(0)
            }
            LaunchedEffect(focusedRow) {
                val index = rowKeys.indexOf(focusedRow).takeIf { it >= 0 } ?: return@LaunchedEffect
                // First focus (e.g. restored after returning) jumps, later changes glide.
                if (snapped) listState.animateScrollToItem(index) else listState.scrollToItem(index)
                snapped = true
            }
            val pinned: @Composable (String, @Composable () -> Unit) -> Unit = { key, content ->
                Column(Modifier.onFocusChanged { if (it.hasFocus) focusedRow = key }) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides outerSpec, content = content,
                    )
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.thecodingdad.moonlightlauncher.ui.components.NoScrollBringIntoView) {
            val topFadePx = with(density) { 28.dp.toPx() }
            val fadePx = with(density) { (insets.calculateBottomPadding() + 56.dp).toPx() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    // Rows fade out towards the bottom edge (behind the hint bar) instead of
                    // being cut off or showing through; the artwork stays visible underneath.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val stop = ((size.height - fadePx) / size.height).coerceIn(0f, 1f)
                        // Top edge fades too, so rows scrolled up don't end in a hard cut.
                        val topStop = (topFadePx / size.height).coerceIn(0f, stop)
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, topStop to Color.Black, stop to Color.Black, 1f to Color.Transparent),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
                contentPadding = PaddingValues(bottom = 24.dp + insets.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows.running.isNotEmpty()) {
                    item(key = "running") { pinned("running") {
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
                    } }
                }
                val firstFilled = filterRows.indexOfFirst { it.entries.isNotEmpty() }
                filterRows.forEachIndexed { index, row ->
                    // The smart home row keeps its place after the first two rows.
                    if (index == 2 && haRow != null) item(key = "ha") { pinned("ha") { haRow() } }
                    homeRow(
                        "row:${row.key}", 0, row.entries, callbacks, pinned,
                        firstRow = rows.running.isEmpty() && index == firstFilled,
                        onFocus = { focused = it; focusedSession = null }, actions = actions,
                        titleText = { filterLabel(row) },
                    )
                }
                if (filterRows.size < 2 && haRow != null) item(key = "ha") { pinned("ha") { haRow() } }
            }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeRow(
    key: String,
    title: Int,
    entries: List<LibraryEntry>,
    callbacks: HomeCallbacks,
    pinned: @Composable (String, @Composable () -> Unit) -> Unit,
    firstRow: Boolean,
    onFocus: (LibraryEntry) -> Unit,
    actions: io.github.thecodingdad.moonlightlauncher.ui.shell.EntryActions,
    titleText: (@Composable () -> String)? = null,
) {
    if (entries.isEmpty()) return
    item(key = key) { pinned(key) {
        RowTitle(titleText?.invoke() ?: stringResource(title))
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
    } }
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
    // Fixed heights throughout: games with/without logo or description must not change the
    // block's height, otherwise the rows below jump while the user scrolls sideways.
    Column(modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry == null) return@Column
        Box(Modifier.height(84.dp), contentAlignment = Alignment.BottomStart) {
            if (entry.item.logoUrl != null) {
                io.github.thecodingdad.moonlightlauncher.ui.components.LogoImage(
                    entry.item.logoUrl, entry.item.title, Modifier.fillMaxHeight().widthIn(max = 420.dp),
                )
            } else {
                Text(
                    entry.item.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = LauncherTheme.colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MetaLine(entry)
        Text(
            entry.item.description.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = LauncherTheme.colors.onSurfaceMuted,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
