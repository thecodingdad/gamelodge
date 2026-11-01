package io.github.thecodingdad.gamelodge.ui.screens.library

import io.github.thecodingdad.gamelodge.ui.components.fadingEdges
import io.github.thecodingdad.gamelodge.ui.components.mouseDragScroll
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.library.BuiltinFilter
import io.github.thecodingdad.gamelodge.data.library.FilterEngine
import io.github.thecodingdad.gamelodge.data.library.LibrarySort
import io.github.thecodingdad.gamelodge.data.library.ResolvedFilter
import io.github.thecodingdad.gamelodge.ui.components.ButtonHint
import io.github.thecodingdad.gamelodge.ui.components.CoverCard
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.PublishAccentFrom
import io.github.thecodingdad.gamelodge.ui.components.coverModel
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.shell.LocalEntryActions
import io.github.thecodingdad.gamelodge.ui.shell.focusAfterBrowse
import io.github.thecodingdad.gamelodge.ui.shell.ScreenHints
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

/**
 * Chip ([row] = false) or home row title of a resolved filter: its name (custom or localized
 * default); for a part of a grouped filter the group value, prefixed with the name if wanted.
 */
@Composable
fun filterLabel(filter: ResolvedFilter, row: Boolean = false): String {
    val name = filter.name ?: filter.builtin?.let { builtinLabel(it) }
    val part = filter.valueLabel ?: return name ?: filter.value.orEmpty()
    val withName = if (row) filter.rowShowName else filter.chipShowName
    return if (withName && name != null) "$name: $part" else part
}

@Composable
fun builtinLabel(key: String): String = when (key) {
    BuiltinFilter.ALL -> stringResource(Res.string.filter_all)
    BuiltinFilter.CONTINUE -> stringResource(Res.string.home_continue)
    BuiltinFilter.RECENT -> stringResource(Res.string.home_recently_added)
    BuiltinFilter.LOCAL -> stringResource(Res.string.filter_local)
    BuiltinFilter.EMULATION -> stringResource(Res.string.filter_emulation)
    BuiltinFilter.HOSTS -> stringResource(Res.string.filter_hosts)
    BuiltinFilter.PLATFORMS -> stringResource(Res.string.filter_platforms)
    BuiltinFilter.FAVORITES -> stringResource(Res.string.filter_favorites)
    BuiltinFilter.NEVER_PLAYED -> stringResource(Res.string.filter_never_played)
    BuiltinFilter.NOT_INSTALLED -> stringResource(Res.string.not_installed)
    BuiltinFilter.REVIEW -> stringResource(Res.string.filter_review)
    BuiltinFilter.HIDDEN -> stringResource(Res.string.filter_hidden)
    else -> key
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    allEntries: List<LibraryEntry>,
    filters: List<ResolvedFilter>,
    animatedArtwork: Boolean,
    lastFocusedKey: String?,
    openStats: () -> Unit,
    openFilterSettings: () -> Unit,
    layout: io.github.thecodingdad.gamelodge.data.settings.TabLayout = io.github.thecodingdad.gamelodge.data.settings.TabLayout(),
    onFocusKey: (String) -> Unit,
) {
    val actions = LocalEntryActions.current
    val keyboard = LocalKeyboardController.current
    // Saveable (the main shell keeps saved state per tab), so filter, sort and search survive
    // opening details and switching tabs.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    var search by rememberSaveable { mutableStateOf("") }
    // Read only where it is shown (hints, accent): a focus move must not recompose the grid.
    val focused = remember { mutableStateOf<LibraryEntry?>(null) }
    val gridFocused = remember { mutableStateOf(true) }
    // On tab enter the grid is briefly unfocused before a card claims focus; ignoring that first
    // unfocused state keeps the hints in the grid state (A = open, quit hint) instead of flipping
    // to the chip state (A = select) and reflowing the bar. Honoured once the grid was focused.
    var gridEverFocused by remember { mutableStateOf(false) }

    val selected = filters.firstOrNull { it.key == selectedKey }
        ?: filters.firstOrNull { it.builtin == BuiltinFilter.ALL } ?: filters.firstOrNull()
    // Groups open in their own order; other filters fall back to the name order.
    val manualSort = selected?.manualOrder == true
    var sortFor by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(selected?.key) {
        if (sortFor == selected?.key) return@LaunchedEffect
        sortFor = selected?.key
        if (manualSort) sort = LibrarySort.MANUAL else if (sort == LibrarySort.MANUAL) sort = LibrarySort.TITLE
    }
    val base = selected?.entries ?: remember(allEntries) { allEntries.filter { it.item.kind == ItemKind.GAME && !it.item.hidden } }
    val shown = remember(base, sort, search) { FilterEngine.query(base, sort, search) }
    // Saveable: survives switching tabs (the tab's saved state is kept while another tab is shown).
    val focusByFilter = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, HashMap<String, String>>(
            save = { HashMap(it) }, restore = { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(it) } },
        ),
    ) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val filterKey = selected?.key ?: BuiltinFilter.ALL
    // Seed the focused entry from the remembered position (the card that will claim focus this
    // frame), so the hints show the focused state (A label, quit hint) at once. Without it the
    // bar reflows a frame later when the card below takes focus — the A hint visibly jumps left.
    remember(Unit) {
        val lastId0 = lastFocusedKey?.takeIf { it.startsWith("library:") }?.removePrefix("library:")
        val id = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            listOfNotNull(focusByFilter[filterKey], lastId0).firstOrNull { id -> shown.any { it.item.id == id } }
        } ?: shown.firstOrNull()?.item?.id
        focused.value = shown.firstOrNull { it.item.id == id }
        true
    }
    // Context menu of a card: its "Details" pages through this list like A does.
    fun openContext(entry: LibraryEntry) = actions.context(entry, shown) { id -> focusByFilter[filterKey] = id; onFocusKey("library:$id") }

    val searchTitle = stringResource(Res.string.hint_search)
    fun openSearch() = keyboard.open(title = searchTitle, initial = search) { search = it }
    // LT/RT switch filters (LB/RB stay with the tabs).
    val feedback = io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback.current
    // One grid for all filters: games in both stay as they are (animations keep running). Each
    // filter's scroll position is kept here and restored when it is shown again.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val positions = remember { HashMap<String, Pair<Int, Int>>() }
    fun select(key: String?) {
        positions[filterKey] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        selectedKey = key
        // Sort and scroll position change with the list, in the same frame: set afterwards (an
        // effect), the first frame showed the new filter in the old order or at the old position.
        // Resolved like [selected] (no key: all games).
        val next = filters.firstOrNull { it.key == key }
            ?: filters.firstOrNull { it.builtin == BuiltinFilter.ALL } ?: filters.firstOrNull()
        if (next != null) {
            sortFor = next.key
            if (next.manualOrder) sort = LibrarySort.MANUAL else if (sort == LibrarySort.MANUAL) sort = LibrarySort.TITLE
        }
        val (first, offset) = positions[next?.key ?: BuiltinFilter.ALL] ?: (0 to 0)
        gridState.requestScrollToItem(first, offset)
    }
    fun stepFilter(delta: Int) {
        filters.step(selected?.key, delta)?.let {
            select(it)
            feedback?.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.TOGGLE)
        }
    }
    // B moves focus up to the active filter instead of leaving the tab.
    var filterFocusRequests by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    io.github.thecodingdad.gamelodge.ui.components.BackHandler { filterFocusRequests++ }
    PadAction(PadButton.L2) { stepFilter(-1) }
    PadAction(PadButton.R2) { stepFilter(1) }
    PadAction(PadButton.SELECT) { sort = sort.next(manualSort) }
    PadAction(PadButton.START) { openSearch() }
    PadAction(PadButton.X) { focused.value?.let { openContext(it) } }

    LibraryFocusDependents(
        focused, gridFocused,
        onOptions = { focused.value?.let { openContext(it) } },
        onFilters = { stepFilter(1) },
        sortLabel = sortLabel(sort),
        onSort = { sort = sort.next(manualSort) },
        onSearch = ::openSearch,
    )

    Column(Modifier.fillMaxSize()) {
        FilterBar(
            filters = filters,
            selectedKey = selected?.key,
            onSelect = { select(it) },
            search = search,
            onSearch = ::openSearch,
            onClearSearch = { search = "" },
            sort = sort,
            manualSort = manualSort,
            onSort = { sort = sort.next(manualSort) },
            onEdit = openFilterSettings,
        focusRequests = filterFocusRequests,
            onStats = openStats,
        )
        if (allEntries.isEmpty()) {
            EmptyLibrary()
            return@Column
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.library_empty_filter), style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurfaceMuted)
            }
            return@Column
        }
        val lastId = lastFocusedKey?.takeIf { it.startsWith("library:") }?.removePrefix("library:")
        // Back from paging through details: the last game viewed (reported via the focus key while
        // this screen was away) takes over this filter's remembered position.
        // Done before the cards appear: they request focus in their first frame.
        remember {
            if (lastId != null && lastId != focusByFilter[filterKey] && shown.any { it.item.id == lastId }) focusByFilter[filterKey] = lastId
            true
        }
        // Not observed: the remembered position only matters when the cards appear, and every
        // focus move writes it (observing it recomposed all cards on each move).
        val targetId = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            listOfNotNull(focusByFilter[filterKey], lastId).firstOrNull { id -> shown.any { it.item.id == id } }
        } ?: shown.first().item.id
        io.github.thecodingdad.gamelodge.ui.input.RegisterStickScroll(gridState)
        // Another filter: its own scroll position. Back from paging through details: the last
        // game viewed may be off screen.
        var gridFilter by remember { mutableStateOf(filterKey) }
        LaunchedEffect(filterKey) {
            if (gridFilter != filterKey) {
                gridFilter = filterKey
                val (first, offset) = positions[filterKey] ?: (0 to 0)
                gridState.scrollToItem(first, offset)
            }
            val index = shown.indexOfFirst { it.item.id == targetId }
            androidx.compose.runtime.snapshotFlow { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
            // Not (fully) on screen: put its line at the top, like the controller focus does.
            val info = gridState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
            val fullyVisible = item != null && item.offset.y >= info.viewportStartOffset &&
                item.offset.y + item.size.height <= info.viewportEndOffset
            if (index >= 0 && !fullyVisible) gridState.scrollToItem(index)
        }
        // The focused line always sits at the top, so lines don't jump around while navigating.
        val snapTo = io.github.thecodingdad.gamelodge.ui.components.rememberFocusLineSnap(gridState)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.thecodingdad.gamelodge.ui.components.NoScrollBringIntoView,
        ) {
        LazyVerticalGrid(
            state = gridState,
            // Narrow screens get smaller covers (three columns instead of two huge ones). With the
            // device held sideways every card size is one step smaller here than on the start tab
            // (one more column); upright the sizes stay as they are.
            columns = io.github.thecodingdad.gamelodge.ui.components.coverColumns(
                io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current, layout.format,
                smaller = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.let { if (it.height > it.width) 0 else 1 },
            ),
            contentPadding = PaddingValues(horizontal = if (io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current) 16.dp else 36.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.focusRestorer().onFocusChanged {
                if (it.hasFocus) { gridFocused.value = true; gridEverFocused = true } else if (gridEverFocused) gridFocused.value = false
            }.fadingEdges(gridState).mouseDragScroll(gridState),
        ) {
            itemsIndexed(shown, key = { _, it -> it.item.id }) { index, entry ->
                val key = "library:${entry.item.id}"
                CoverCard(
                    entry = entry,
                    onClick = {
                        actions.open(entry, shown) { id -> focusByFilter[filterKey] = id; onFocusKey("library:$id") }
                    },
                    onLongClick = { openContext(entry) },
                    onFocused = { focused.value = entry; focusByFilter[filterKey] = entry.item.id; onFocusKey(key); snapTo(index) },
                    animated = animatedArtwork,
                    layout = layout,
                    modifier = Modifier.restoreFocusIf(entry.item.id == targetId, trigger = filterKey).focusAfterBrowse(entry.item.id),
                )
            }
        }
        }
    }
}

/** What follows the focused card: the button hints and the accent from its cover. */
@Composable
private fun LibraryFocusDependents(
    focused: androidx.compose.runtime.State<LibraryEntry?>,
    gridFocused: androidx.compose.runtime.State<Boolean>,
    onOptions: () -> Unit,
    onFilters: () -> Unit,
    sortLabel: String,
    onSort: () -> Unit,
    onSearch: () -> Unit,
) {
    val actions = LocalEntryActions.current
    val entry = focused.value
    PublishAccentFrom(entry?.coverModel())
    val quitHint = io.github.thecodingdad.gamelodge.ui.components.quitHoldHint(entry?.takeIf { gridFocused.value })
    ScreenHints(
        listOfNotNull(
            // On the filter chips A selects a filter; only on a card it opens/starts.
            ButtonHint(PadButton.A, stringResource(if (gridFocused.value) actions.openLabel(entry) else Res.string.hint_select)),
            ButtonHint(PadButton.X, stringResource(Res.string.hint_options), onClick = onOptions),
            quitHint,
            ButtonHint(PadButton.L2, stringResource(Res.string.hint_filters), onClick = onFilters, secondButton = PadButton.R2),
            ButtonHint(PadButton.SELECT, sortLabel, onClick = onSort),
            ButtonHint(PadButton.START, stringResource(Res.string.hint_search), onClick = onSearch),
        ),
    )
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.accent else colors.surfaceRaised,
        focusScale = 1.08f,
    ) {
        val content = if (selected) colors.onAccent else colors.onSurface
        // Icons take their tint from LocalContentColor, which FocusSurface doesn't set.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides content) {
            Row(
                // Fixed content height: chips with and without icon line up.
                Modifier.padding(horizontal = if (label.isEmpty()) 12.dp else 16.dp, vertical = 9.dp).height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.invoke()
                if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}
