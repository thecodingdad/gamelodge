package io.github.thecodingdad.moonlightlauncher.ui.screens.home

import io.github.thecodingdad.moonlightlauncher.ui.components.mouseDragScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.github.thecodingdad.moonlightlauncher.ui.shell.focusAfterBrowse
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import io.github.thecodingdad.moonlightlauncher.resources.*

/** Callbacks shared by all home layouts. */
class HomeCallbacks(
    val onResume: (RunningSession) -> Unit,
    val onRunningMenu: (RunningSession) -> Unit,
    val lastFocusedKey: String?,
    val onFocusKey: (String) -> Unit,
    val animatedArtwork: Boolean,
    /** Reports whether the layout is in its initial state (top, first item focused). */
    val onAtStart: (Boolean) -> Unit = {},
    /** The entry the user is on (for the A hint). */
    val onFocusedEntry: (LibraryEntry?) -> Unit = {},
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
    /** Index in [filterRows] before which [haRow] goes (rows.size = after the last). */
    haIndex: Int = 2,
) {
    val actions = LocalEntryActions.current
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var focusedSession by remember { mutableStateOf<RunningSession?>(null) }
    // Row of the focused card, for the context menu's "Details" (pages through the row).
    var focusedList by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var focusedListKey by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<LibraryEntry?>(null) }
    // Back on the tab (e.g. from a detail page): the game focused, tapped or browsed to last.
    // By controller the card regains focus anyway; by touch nothing has focus, so the hero
    // starts with that game instead of the first one.
    val lastId = callbacks.lastFocusedKey?.substringAfterLast(':')
    val remembered = lastId?.let { id -> filterRows.firstNotNullOfOrNull { row -> row.entries.firstOrNull { it.item.id == id } } }
    // Avoid reloading artwork for every item while the user scrolls fast.
    val fallback = remembered ?: rows.running.firstOrNull()?.entry ?: filterRows.firstOrNull()?.entries?.firstOrNull()
        ?: rows.continuePlaying.firstOrNull() ?: rows.recentlyAdded.firstOrNull()
    LaunchedEffect(focused, fallback) {
        if (focused != null) delay(140)
        shown = focused ?: fallback
    }
    PublishAccentFrom(shown?.coverModel())
    LaunchedEffect(focused) { callbacks.onFocusedEntry(focused) }
    // Start: quick start of the focused game (no hint, a shortcut for those who know it).
    PadAction(PadButton.START) { focused?.let(actions::play) }
    PadAction(PadButton.X) {
        val session = focusedSession
        if (session != null) callbacks.onRunningMenu(session)
        else focused?.let { e -> actions.context(e, focusedList) { id -> focusedListKey?.let { callbacks.onFocusKey("$it:$id") } } }
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(shown, animationSpec = tween(400), label = "hero") { entry ->
            if (entry != null) {
                io.github.thecodingdad.moonlightlauncher.ui.components.HeroImage(entry, Modifier.fillMaxSize(), animated = callbacks.animatedArtwork)
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
            io.github.thecodingdad.moonlightlauncher.ui.input.RegisterStickScroll(listState)
            var focusedRow by remember { mutableStateOf<String?>(null) }
            val rowKeys = buildList {
                if (rows.running.isNotEmpty()) add("running")
                filterRows.forEachIndexed { index, row ->
                    if (index == haIndex && haRow != null) add("ha")
                    if (row.entries.isNotEmpty()) add("row:${row.key}")
                }
                if (haIndex >= filterRows.size && haRow != null) add("ha")
            }
            var snapped by remember { mutableStateOf(false) }
            // Initial state: list at the top and the first card of the first row focused (or nothing yet).
            val firstFilled = filterRows.firstOrNull { it.entries.isNotEmpty() }
            val atStart = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && when {
                focused == null && focusedSession == null -> true
                rows.running.isNotEmpty() -> focusedSession === rows.running.first()
                else -> focusedRow == firstFilled?.let { "row:${it.key}" } && focused?.item?.id == firstFilled?.entries?.first()?.item?.id
            }
            LaunchedEffect(atStart) { callbacks.onAtStart(atStart) }
            // Rows arrive in stages at start (e.g. the smart home row before the game rows). The
            // list keeps its first visible item when rows are inserted above it, so without a
            // remembered position jump back to the top whenever the first row changes.
            val firstRowKey = rowKeys.firstOrNull()
            LaunchedEffect(firstRowKey) {
                if (firstRowKey != null && callbacks.lastFocusedKey == null && !snapped) listState.scrollToItem(0)
            }
            LaunchedEffect(focusedRow) {
                val row = rowKeys.indexOf(focusedRow).takeIf { it >= 0 } ?: return@LaunchedEffect
                // The first row returns to its start position at the bottom (the spacer item 0
                // in view), every other row snaps to the top.
                val index = if (row == 0) 0 else row + 1
                // First focus (e.g. restored after returning) jumps, later changes glide.
                if (snapped) listState.animateScrollToItem(index) else listState.scrollToItem(index)
                snapped = true
            }
            // Height of the first row, so the spacer above it can put it at the bottom edge.
            var firstRowHeight by remember { mutableStateOf(0) }
            val pinned: @Composable (String, @Composable () -> Unit) -> Unit = { key, content ->
                Column(
                    Modifier
                        .onFocusChanged { if (it.hasFocus) focusedRow = key }
                        .then(if (key == rowKeys.firstOrNull()) Modifier.onSizeChanged { firstRowHeight = it.height } else Modifier),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides outerSpec, content = content,
                    )
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.thecodingdad.moonlightlauncher.ui.components.NoScrollBringIntoView) {
            // Snapped row starts at 48dp; the row above ends ~22dp earlier (spacing + row padding).
            // That part stays fully transparent, the fade lies in the gap, so nothing of the
            // previous row shows and the snapped row's title is not dimmed.
            val topClearPx = with(density) { 26.dp.toPx() }
            val topFadePx = with(density) { 48.dp.toPx() }
            val fadePx = with(density) { (insets.calculateBottomPadding() + 56.dp).toPx() }
            // Portrait: the bottom navigation bar is opaque, so the fade must end at its top edge
            // (landscape: the hint bar is translucent and the fade runs on behind it).
            val opaqueBottomPx = if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) {
                with(density) { insets.calculateBottomPadding().toPx() }
            } else 0f
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).mouseDragScroll(listState)
                    // Extend 48dp upwards under the info block (room for the top fade).
                    .layout { measurable, constraints ->
                        val extra = 48.dp.roundToPx()
                        val c = if (constraints.hasBoundedHeight) constraints.copy(maxHeight = constraints.maxHeight + extra, minHeight = constraints.minHeight + extra) else constraints
                        val placeable = measurable.measure(c)
                        layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, -extra) }
                    }
                    // Rows fade out towards the bottom edge (behind the hint bar) instead of
                    // being cut off or showing through; the artwork stays visible underneath.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val stop = ((size.height - fadePx) / size.height).coerceIn(0f, 1f)
                        val end = ((size.height - opaqueBottomPx) / size.height).coerceIn(stop, 1f)
                        // Top edge fades too, so rows scrolled up don't end in a hard cut.
                        val topStop = (topFadePx / size.height).coerceIn(0f, stop)
                        val clearStop = (topClearPx / size.height).coerceIn(0f, topStop)
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent, clearStop to Color.Transparent, topStop to Color.Black,
                                stop to Color.Black, end to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
                // Top padding = fade height: a snapped row starts below the fade, so its title isn't dimmed
                // (the list is pulled up by the same amount below, so the row keeps its position).
                contentPadding = PaddingValues(top = 48.dp, bottom = 24.dp + insets.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Start position: the top row sits at the bottom edge with the artwork above it.
                // Scrolling the spacer away still lets the rows go up to below the info block.
                item(key = "start-spacer") {
                    val viewport = listState.layoutInfo.viewportSize.height
                    // Landscape: the cards end just above the hint bar (the inset includes some margin),
                    // so with small cards the next row stays hidden too. Portrait: above the fade.
                    val bottom = if (opaqueBottomPx > 0f) fadePx.toInt() else with(density) { (insets.calculateBottomPadding() - 16.dp).coerceAtLeast(0.dp).roundToPx() }
                    val gap = with(density) { 10.dp.roundToPx() + 48.dp.roundToPx() } + bottom
                    val height = if (firstRowHeight == 0) 0 else (viewport - firstRowHeight - gap).coerceAtLeast(0)
                    androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(with(density) { height.toDp() }))
                }
                if (rows.running.isNotEmpty()) {
                    item(key = "running") { pinned("running") {
                        RowTitle(stringResource(Res.string.home_running))
                        val runningRow = androidx.compose.foundation.lazy.rememberLazyListState()
                        LazyRow(
                            state = runningRow,
                            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier.focusRestorer().mouseDragScroll(runningRow, androidx.compose.foundation.gestures.Orientation.Horizontal),
                        ) {
                            items(rows.running, key = { it.host.uuid }) { session ->
                                val key = "running:${session.host.uuid}"
                                WideCard(
                                    title = session.appName,
                                    subtitle = stringResource(Res.string.running_on, session.host.name),
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
                    if (index == haIndex && haRow != null) item(key = "ha") { pinned("ha") { haRow() } }
                    homeRow(
                        "row:${row.key}", null, row.entries, callbacks, pinned,
                        firstRow = rows.running.isEmpty() && index == firstFilled,
                        onFocus = { entry, list -> focused = entry; focusedSession = null; focusedList = list; focusedListKey = "row:${row.key}" },
                        actions = actions,
                        titleText = { filterLabel(row, row = true) },
                    )
                }
                if (haIndex >= filterRows.size && haRow != null) item(key = "ha") { pinned("ha") { haRow() } }
            }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeRow(
    key: String,
    /** Null when [titleText] names the row. */
    title: org.jetbrains.compose.resources.StringResource?,
    entries: List<LibraryEntry>,
    callbacks: HomeCallbacks,
    pinned: @Composable (String, @Composable () -> Unit) -> Unit,
    firstRow: Boolean,
    onFocus: (LibraryEntry, List<LibraryEntry>) -> Unit,
    actions: io.github.thecodingdad.moonlightlauncher.ui.shell.EntryActions,
    titleText: (@Composable () -> String)? = null,
) {
    if (entries.isEmpty()) return
    item(key = key) { pinned(key) {
        RowTitle(titleText?.invoke() ?: stringResource(requireNotNull(title)))
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
        val rowBringIntoView = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        // Back from paging through details: bring the last game viewed into the row's view.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val id = callbacks.lastFocusedKey?.takeIf { it.startsWith("$key:") }?.removePrefix("$key:") ?: return@LaunchedEffect
            val index = entries.indexOfFirst { it.item.id == id }
            androidx.compose.runtime.snapshotFlow { rowState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            if (index < 0) return@LaunchedEffect
            val info = rowState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val fullyVisible = item != null && item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
            if (!fullyVisible) rowState.scrollToItem(index)
            // The row itself may be scrolled out of the screen (touch has no focus to follow).
            rowBringIntoView.bringIntoView()
        }
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.focusRestorer().bringIntoViewRequester(rowBringIntoView).mouseDragScroll(rowState, androidx.compose.foundation.gestures.Orientation.Horizontal),
        ) {
            items(entries, key = { it.item.id }) { entry ->
                val focusKey = "$key:${entry.item.id}"
                val isDefault = callbacks.lastFocusedKey == null && firstRow && entry === entries.first()
                val cardModifier = Modifier.restoreFocusIf(isDefault || callbacks.lastFocusedKey == focusKey).focusAfterBrowse(entry.item.id)
                if (entry.item.kind == io.github.thecodingdad.moonlightlauncher.data.db.ItemKind.APP) {
                    // Apps on the start tab: landscape tiles.
                    io.github.thecodingdad.moonlightlauncher.ui.components.AppTileCard(
                        entry = entry,
                        onClick = { actions.open(entry, entries) },
                        onLongClick = { actions.context(entry, entries) { id -> callbacks.onFocusKey("$key:$id") } },
                        onFocused = { onFocus(entry, entries); callbacks.onFocusKey(focusKey) },
                        modifier = cardModifier,
                    )
                } else {
                    CoverCard(
                        entry = entry,
                        width = LauncherTheme.dimens.coverWidth * 0.82f,
                        onClick = { actions.open(entry, entries) { id -> callbacks.onFocusKey("$key:$id") } },
                        onLongClick = { actions.context(entry, entries) { id -> callbacks.onFocusKey("$key:$id") } },
                        onFocused = { onFocus(entry, entries); callbacks.onFocusKey(focusKey) },
                        animated = callbacks.animatedArtwork,
                        modifier = cardModifier,
                    )
                }
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
