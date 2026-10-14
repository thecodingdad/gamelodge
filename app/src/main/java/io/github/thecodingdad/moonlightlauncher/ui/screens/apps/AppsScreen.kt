package io.github.thecodingdad.moonlightlauncher.ui.screens.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.thecodingdad.moonlightlauncher.data.library.BuiltinFilter
import io.github.thecodingdad.moonlightlauncher.data.library.ResolvedFilter
import io.github.thecodingdad.moonlightlauncher.data.library.FilterEngine
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySort
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.FilterBar
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.next
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.step
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.sortLabel
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.filterLabel
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    apps: List<LibraryEntry>,
    filters: List<ResolvedFilter>,
    lastFocusedKey: String?,
    openFilterSettings: () -> Unit,
    onFocusKey: (String) -> Unit,
) {
    val actions = LocalEntryActions.current
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var gridFocused by remember { mutableStateOf(true) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    // Scroll position and focused app are kept per filter.
    val gridStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Saveable: survives switching tabs (the tab's saved state is kept while another tab is shown).
    val focusByFilter = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, HashMap<String, String>>(
            save = { HashMap(it) }, restore = { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(it) } },
        ),
    ) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var search by rememberSaveable { mutableStateOf("") }
    val selected = filters.firstOrNull { it.key == selectedKey }
    // Groups open in their own order; other filters fall back to the name order.
    val manualSort = selected?.manualOrder == true
    var sortFor by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(selected?.key) {
        if (sortFor == selected?.key) return@LaunchedEffect
        sortFor = selected?.key
        if (manualSort) sort = LibrarySort.MANUAL else if (sort == LibrarySort.MANUAL) sort = LibrarySort.TITLE
    }
    val keyboard = io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController.current
    val searchTitle = stringResource(R.string.hint_search)
    fun openSearch() = keyboard.open(title = searchTitle, initial = search) { search = it }
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
    PadAction(PadButton.SELECT) { sort = sort.next(manualSort) }
    PadAction(PadButton.START) { openSearch() }
    PadAction(PadButton.X) { focused?.let(actions::context) }
    ScreenHints(
        listOf(
            // On the filter chips A selects a filter; only on an app it starts it.
            ButtonHint(PadButton.A, stringResource(if (gridFocused) R.string.hint_start else R.string.hint_select)),
            ButtonHint(PadButton.X, stringResource(R.string.hint_options), onClick = { focused?.let(actions::context) }),
            ButtonHint(PadButton.L2, stringResource(R.string.hint_filters), onClick = { stepFilter(1) }, secondButton = PadButton.R2),
            ButtonHint(PadButton.SELECT, sortLabel(sort), onClick = { sort = sort.next(manualSort) }),
            ButtonHint(PadButton.START, stringResource(R.string.hint_search), onClick = ::openSearch),
        ),
    )

    // "All" (or no filter) shows sections per source; any other filter a flat list.
    val flat = selected != null && selected.builtin != BuiltinFilter.ALL
    val source = if (flat) selected!!.entries else apps
    val sorted = remember(source, sort, search) { FilterEngine.query(source, sort, search) }
    val local = if (flat) sorted else sorted.filter { it.localTarget != null }
    val byHost = if (flat) emptyMap() else sorted.filter { it.localTarget == null }
        .groupBy { it.remoteTargets.firstOrNull()?.hostName.orEmpty() }

    Column(Modifier.fillMaxSize()) {
    FilterBar(
        filters = filters,
        selectedKey = selected?.key,
        onSelect = { selectedKey = it },
        search = search,
        onSearch = ::openSearch,
        onClearSearch = { search = "" },
        sort = sort,
        manualSort = manualSort,
        onSort = { sort = sort.next(manualSort) },
        onEdit = openFilterSettings,
        focusRequests = filterFocusRequests,
    )
    if (sorted.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(if (apps.isEmpty()) R.string.apps_empty else R.string.library_empty_filter), color = LauncherTheme.colors.onSurfaceMuted, style = MaterialTheme.typography.titleMedium)
        }
        return@Column
    }

    val first = local.firstOrNull() ?: byHost.values.firstOrNull()?.firstOrNull()
    val filterKey = selected?.key ?: BuiltinFilter.ALL
    val lastId = lastFocusedKey?.takeIf { it.startsWith("apps:") }?.removePrefix("apps:")
    val targetId = listOfNotNull(focusByFilter[filterKey], lastId).firstOrNull { id -> sorted.any { it.item.id == id } }
        ?: first?.item?.id
    val deviceTitle = stringResource(R.string.apps_on_device)
    val selectedTitle = selected?.let { filterLabel(it) }.orEmpty()
    val hostTitles = byHost.keys.associateWith { stringResource(R.string.apps_on_host, it) }
    gridStates.SaveableStateProvider(filterKey) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    io.github.thecodingdad.moonlightlauncher.ui.input.RegisterStickScroll(gridState)
    // Focused line snaps to the top; in a section's first line the section header stays visible.
    val snapTo = io.github.thecodingdad.moonlightlauncher.ui.components.rememberFocusLineSnap(gridState)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.thecodingdad.moonlightlauncher.ui.components.NoScrollBringIntoView,
    ) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(
            if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) LauncherTheme.dimens.iconTile * 0.6f + 30.dp
            else LauncherTheme.dimens.iconTile + 36.dp,
        ),
        contentPadding = PaddingValues(horizontal = if (io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current) 16.dp else 36.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize().focusRestorer().onFocusChanged { gridFocused = it.hasFocus },
    ) {
        var gridIndex = 0
        fun section(title: String, list: List<LibraryEntry>) {
            if (list.isEmpty()) return
            val headerIndex = gridIndex
            gridIndex += 1 + list.size
            item(span = { GridItemSpan(maxLineSpan) }, key = "header:$title") {
                Text(title, style = MaterialTheme.typography.titleLarge, color = LauncherTheme.colors.onSurface, modifier = Modifier.padding(top = 10.dp))
            }
            itemsIndexed(list, key = { _, it -> it.item.id }) { position, entry ->
                val key = "apps:${entry.item.id}"
                AppTile(
                    entry = entry,
                    onClick = { actions.play(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = {
                        focused = entry; focusByFilter[filterKey] = entry.item.id; onFocusKey(key)
                        val columns = gridState.layoutInfo.maxSpan.coerceAtLeast(1)
                        snapTo(if (position < columns) headerIndex else headerIndex + 1 + position)
                    },
                    modifier = Modifier.restoreFocusIf(entry.item.id == targetId),
                )
            }
        }
        if (flat) {
            section(selectedTitle, local)
        } else {
            section(deviceTitle, local)
            byHost.forEach { (host, list) -> section(hostTitles.getValue(host), list) }
        }
    }
    }
    }
    }
}
