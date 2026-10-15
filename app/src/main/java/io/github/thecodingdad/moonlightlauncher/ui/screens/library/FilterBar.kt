package io.github.thecodingdad.moonlightlauncher.ui.screens.library

import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.data.library.BuiltinFilter
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySort
import io.github.thecodingdad.moonlightlauncher.data.library.ResolvedFilter
import io.github.thecodingdad.moonlightlauncher.resources.*

/**
 * Filter bar shared by library and Apps tab: filter chips on the left (scrolling when they
 * don't fit), fixed icon-only tools on the right: search, sort, statistics, edit filters.
 * While a search is active it shows up as the first chip on the left; tapping it clears it.
 */
@Composable
fun FilterBar(
    filters: List<ResolvedFilter>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    search: String,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    sort: LibrarySort,
    onSort: () -> Unit,
    onEdit: () -> Unit,
    onStats: (() -> Unit)? = null,
    /** The selected filter is a group: its own order is the default sort. */
    manualSort: Boolean = false,
    /** Incremented by the screen to move focus to the active filter chip (B button). */
    focusRequests: Int = 0,
) {
    val listState = rememberLazyListState()
    val defaultSort = if (manualSort) LibrarySort.MANUAL else LibrarySort.TITLE
    val selectedRequester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val selectedIndex = filters.indexOfFirst { it.key == selectedKey || (selectedKey == null && it.builtin == BuiltinFilter.ALL) }
        .takeIf { it >= 0 }?.let { if (search.isNotEmpty()) it + 1 else it }
    // Keep the active chip visible, e.g. after switching with LT/RT.
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(selectedIndex, filters, search.isNotEmpty()) {
        val index = selectedIndex ?: return@LaunchedEffect
        val margin = with(density) { 24.dp.toPx() }
        /** Pixels to scroll so the whole chip (plus margin) is visible; null = not laid out yet. */
        fun overflow(): Float? {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
            val start = info.viewportStartOffset + margin
            val end = info.viewportEndOffset - margin
            return when {
                item.size > end - start || item.offset < start -> item.offset - start
                item.offset + item.size > end -> item.offset + item.size - end
                else -> 0f
            }
        }
        // Keeps correcting for a moment: chip widths can still change after the switch (labels
        // such as host names arrive later, the list is rebuilt), which left a chip cut off.
        kotlinx.coroutines.withTimeoutOrNull(1_500) {
            androidx.compose.runtime.snapshotFlow { listState.layoutInfo }.collect { info ->
                if (info.totalItemsCount <= index) return@collect
                if (index == 0) {
                    if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) listState.animateScrollToItem(0)
                    return@collect
                }
                val delta = overflow()
                when {
                    delta == null -> listState.scrollToItem(index)
                    kotlin.math.abs(delta) > 1f -> listState.animateScrollBy(delta)
                }
            }
        }
    }
    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        val index = selectedIndex ?: return@LaunchedEffect
        // The chip must be composed before it can take focus.
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem((index - 1).coerceAtLeast(0))
        kotlinx.coroutines.yield()
        runCatching { selectedRequester.requestFocus() }
    }
    Row(
        Modifier.fillMaxWidth().padding(end = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(start = 36.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).focusRestorer(),
        ) {
            if (search.isNotEmpty()) {
                item(key = "search") {
                    Chip(
                        "„$search“", selected = true, onClick = onClearSearch,
                        icon = { Icon(Icons.Filled.Close, stringResource(Res.string.search_clear), Modifier.padding(end = 6.dp)) },
                    )
                }
            }
            items(filters, key = { "filter:${it.key}" }) { f ->
                val selected = f.key == selectedKey || (selectedKey == null && f.builtin == BuiltinFilter.ALL)
                Chip(
                    filterLabel(f), selected = selected, onClick = { onSelect(f.key) },
                    modifier = if (selected) Modifier.focusRequester(selectedRequester) else Modifier,
                )
            }
        }
        if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) {
            // Narrow screens: the tools sit behind one menu button, the chips get the width.
            var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            Chip("", selected = search.isNotEmpty() || sort != defaultSort, onClick = { menu = true }, icon = {
                Icon(Icons.Filled.MoreVert, stringResource(Res.string.hint_options))
            })
            io.github.thecodingdad.moonlightlauncher.ui.components.Modal(
                visible = menu, onDismiss = { menu = false }, maxWidth = 420.dp, title = stringResource(Res.string.hint_options),
            ) {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val item: @Composable (androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit) -> Unit = { icon, label, action ->
                        io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                            label, onClick = { menu = false; action() }, icon = icon,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(Icons.Filled.Search, stringResource(Res.string.hint_search), onSearch)
                    item(Icons.AutoMirrored.Filled.Sort, sortLabel(sort) + "  →  " + sortLabel(sort.next(manualSort)).substringAfter(": "), onSort)
                    if (onStats != null) item(Icons.Filled.BarChart, stringResource(Res.string.stats_title), onStats)
                    item(Icons.Filled.Edit, stringResource(Res.string.filters_edit), onEdit)
                }
            }
        } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusRestorer()) {
            Chip(
                "", selected = search.isNotEmpty(), onClick = onSearch,
                icon = { Icon(Icons.Filled.Search, stringResource(Res.string.hint_search)) },
            )
            Chip(
                "", selected = sort != defaultSort, onClick = onSort,
                icon = { Icon(Icons.AutoMirrored.Filled.Sort, sortLabel(sort)) },
            )
            if (onStats != null) {
                Chip("", selected = false, onClick = onStats, icon = { Icon(Icons.Filled.BarChart, stringResource(Res.string.stats_title)) })
            }
            Chip("", selected = false, onClick = onEdit, icon = { Icon(Icons.Filled.Edit, stringResource(Res.string.filters_edit)) })
        }
            }
    }
}

/** Next/previous filter for LT/RT; stops at both ends. Returns null when nothing changes. */
fun List<ResolvedFilter>.step(selectedKey: String?, delta: Int): String? {
    if (isEmpty()) return null
    val current = indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 }
        ?: indexOfFirst { it.builtin == BuiltinFilter.ALL }.coerceAtLeast(0)
    val next = (current + delta).coerceIn(0, lastIndex)
    return if (next == current) null else this[next].key
}

/** Next sort order; [LibrarySort.MANUAL] only when the filter has its own order (groups). */
fun LibrarySort.next(manual: Boolean = false): LibrarySort {
    val options = LibrarySort.entries.filter { manual || it != LibrarySort.MANUAL }
    return options[(options.indexOf(this) + 1).mod(options.size)]
}

@Composable
fun sortLabel(sort: LibrarySort): String = stringResource(
    when (sort) {
        LibrarySort.TITLE -> Res.string.sort_title
        LibrarySort.LAST_PLAYED -> Res.string.sort_last_played
        LibrarySort.ADDED -> Res.string.sort_added
        LibrarySort.PLAYTIME -> Res.string.sort_playtime
        LibrarySort.MANUAL -> Res.string.sort_manual
    },
)
