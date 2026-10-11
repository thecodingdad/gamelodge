package io.github.thecodingdad.moonlightlauncher.ui.screens.library

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
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.BuiltinFilter
import io.github.thecodingdad.moonlightlauncher.data.library.FilterEngine
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySort
import io.github.thecodingdad.moonlightlauncher.data.library.ResolvedFilter
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.CoverCard
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.PublishAccentFrom
import io.github.thecodingdad.moonlightlauncher.ui.components.coverModel
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.shell.focusAfterBrowse
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

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
    BuiltinFilter.ALL -> stringResource(R.string.filter_all)
    BuiltinFilter.CONTINUE -> stringResource(R.string.home_continue)
    BuiltinFilter.RECENT -> stringResource(R.string.home_recently_added)
    BuiltinFilter.LOCAL -> stringResource(R.string.filter_local)
    BuiltinFilter.HOSTS -> stringResource(R.string.filter_hosts)
    BuiltinFilter.PLATFORMS -> stringResource(R.string.filter_platforms)
    BuiltinFilter.FAVORITES -> stringResource(R.string.filter_favorites)
    BuiltinFilter.NEVER_PLAYED -> stringResource(R.string.filter_never_played)
    BuiltinFilter.REVIEW -> stringResource(R.string.filter_review)
    BuiltinFilter.HIDDEN -> stringResource(R.string.filter_hidden)
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
    onFocusKey: (String) -> Unit,
) {
    val actions = LocalEntryActions.current
    val keyboard = LocalKeyboardController.current
    // Saveable (the main shell keeps saved state per tab), so filter, sort and search survive
    // opening details and switching tabs.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    var search by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    PublishAccentFrom(focused?.coverModel())

    val selected = filters.firstOrNull { it.key == selectedKey }
        ?: filters.firstOrNull { it.builtin == BuiltinFilter.ALL } ?: filters.firstOrNull()
    val base = selected?.entries ?: remember(allEntries) { allEntries.filter { it.item.kind == ItemKind.GAME && !it.item.hidden } }
    val shown = remember(base, sort, search) { FilterEngine.query(base, sort, search) }

    val searchTitle = stringResource(R.string.hint_search)
    fun openSearch() = keyboard.open(title = searchTitle, initial = search) { search = it }
    // LT/RT switch filters (LB/RB stay with the tabs).
    val feedback = io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback.current
    fun stepFilter(delta: Int) {
        filters.step(selected?.key, delta)?.let {
            selectedKey = it
            feedback?.play(io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound.TOGGLE)
        }
    }
    // B moves focus up to the active filter instead of leaving the tab.
    var filterFocusRequests by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler { filterFocusRequests++ }
    PadAction(PadButton.L2) { stepFilter(-1) }
    PadAction(PadButton.R2) { stepFilter(1) }
    PadAction(PadButton.SELECT) { sort = sort.next() }
    PadAction(PadButton.START) { openSearch() }
    PadAction(PadButton.X) { focused?.let(actions::context) }

    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(R.string.hint_open)),
            ButtonHint(PadButton.X, stringResource(R.string.hint_options), onClick = { focused?.let(actions::context) }),
            ButtonHint(PadButton.L2, stringResource(R.string.hint_filters), onClick = { stepFilter(1) }, secondButton = PadButton.R2),
            ButtonHint(PadButton.SELECT, sortLabel(sort), onClick = { sort = sort.next() }),
            ButtonHint(PadButton.START, stringResource(R.string.hint_search), onClick = ::openSearch),
        ),
    )
    // Scroll position and focused game are kept per filter.
    val gridStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Saveable: survives switching tabs (the tab's saved state is kept while another tab is shown).
    val focusByFilter = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, HashMap<String, String>>(
            save = { HashMap(it) }, restore = { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(it) } },
        ),
    ) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val filterKey = selected?.key ?: BuiltinFilter.ALL

    Column(Modifier.fillMaxSize()) {
        FilterBar(
            filters = filters,
            selectedKey = selected?.key,
            onSelect = { selectedKey = it },
            search = search,
            onSearch = ::openSearch,
            onClearSearch = { search = "" },
            sort = sort,
            onSort = { sort = sort.next() },
            onEdit = openFilterSettings,
        focusRequests = filterFocusRequests,
            onStats = openStats,
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_empty_filter), style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurfaceMuted)
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
        val targetId = listOfNotNull(focusByFilter[filterKey], lastId).firstOrNull { id -> shown.any { it.item.id == id } }
            ?: shown.first().item.id
        gridStates.SaveableStateProvider(filterKey) {
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        // Back from paging through details: the last game viewed may be off screen.
        LaunchedEffect(Unit) {
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
        val snapTo = io.github.thecodingdad.moonlightlauncher.ui.components.rememberFocusLineSnap(gridState)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.thecodingdad.moonlightlauncher.ui.components.NoScrollBringIntoView,
        ) {
        LazyVerticalGrid(
            state = gridState,
            // Narrow screens get smaller covers (three columns instead of two huge ones).
            columns = GridCells.Adaptive(
                if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) LauncherTheme.dimens.coverWidth * 0.62f
                else LauncherTheme.dimens.coverWidth + 8.dp,
            ),
            contentPadding = PaddingValues(horizontal = if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) 16.dp else 36.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            itemsIndexed(shown, key = { _, it -> it.item.id }) { index, entry ->
                val key = "library:${entry.item.id}"
                CoverCard(
                    entry = entry,
                    onClick = {
                        actions.open(entry, shown) { id -> focusByFilter[filterKey] = id; onFocusKey("library:$id") }
                    },
                    onLongClick = { actions.context(entry) },
                    onFocused = { focused = entry; focusByFilter[filterKey] = entry.item.id; onFocusKey(key); snapTo(index) },
                    showTitle = true,
                    animated = animatedArtwork,
                    modifier = Modifier.restoreFocusIf(entry.item.id == targetId).focusAfterBrowse(entry.item.id),
                )
            }
        }
        }
        }
    }
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
