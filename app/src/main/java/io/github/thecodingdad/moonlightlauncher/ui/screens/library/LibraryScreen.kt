package io.github.thecodingdad.moonlightlauncher.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.thecodingdad.moonlightlauncher.data.db.MetadataState
import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
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
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Local : LibraryFilter
    data class Host(val uuid: String, val name: String) : LibraryFilter
    data object Favorites : LibraryFilter
    data object NeverPlayed : LibraryFilter
    data object Review : LibraryFilter
    data object Hidden : LibraryFilter
    data class Collection(val id: String, val name: String, val members: Set<String>) : LibraryFilter
}

enum class LibrarySort { TITLE, LAST_PLAYED, ADDED, PLAYTIME }

/** Pure filtering/sorting, unit-testable and shared with the playnite-style home grid. */
fun applyLibraryQuery(
    entries: List<LibraryEntry>,
    filter: LibraryFilter,
    sort: LibrarySort,
    search: String,
): List<LibraryEntry> {
    val query = TitleNormalizer.normalize(search)
    return entries
        .asSequence()
        .filter { filter == LibraryFilter.Hidden || !it.item.hidden }
        .filter {
            when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Local -> it.localTarget != null
                is LibraryFilter.Host -> it.remoteTargets.any { t -> t.hostUuid == filter.uuid }
                LibraryFilter.Favorites -> it.item.favorite
                LibraryFilter.NeverPlayed -> it.item.lastPlayedAt == null
                LibraryFilter.Review -> it.item.metadataState == MetadataState.REVIEW
                LibraryFilter.Hidden -> it.item.hidden
                is LibraryFilter.Collection -> it.item.id in filter.members
            }
        }
        .filter { query.isEmpty() || it.item.normalizedTitle.contains(query) }
        .let { seq ->
            when (sort) {
                LibrarySort.TITLE -> seq.sortedBy { TitleNormalizer.sortKey(it.item.title) }
                LibrarySort.LAST_PLAYED -> seq.sortedByDescending { it.item.lastPlayedAt ?: 0L }
                LibrarySort.ADDED -> seq.sortedByDescending { it.item.addedAt }
                LibrarySort.PLAYTIME -> seq.sortedByDescending { it.item.playtimeSeconds }
            }
        }
        .toList()
}

@Composable
fun LibraryScreen(
    allEntries: List<LibraryEntry>,
    hosts: List<HostState>,
    collections: List<Pair<io.github.thecodingdad.moonlightlauncher.data.db.CollectionEntity, Set<String>>>,
    animatedArtwork: Boolean,
    lastFocusedKey: String?,
    openStats: () -> Unit,
    onFocusKey: (String) -> Unit,
) {
    val actions = LocalEntryActions.current
    val keyboard = LocalKeyboardController.current
    var filter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.TITLE) }
    var search by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    PublishAccentFrom(focused?.coverModel())

    val games = remember(allEntries) { allEntries.filter { it.item.kind == ItemKind.GAME } }
    val filters = buildList {
        add(LibraryFilter.All to stringResource(R.string.filter_all))
        if (games.any { it.localTarget != null }) add(LibraryFilter.Local to stringResource(R.string.filter_local))
        hosts.forEach { add(LibraryFilter.Host(it.uuid, it.name) to it.name) }
        add(LibraryFilter.Favorites to stringResource(R.string.filter_favorites))
        add(LibraryFilter.NeverPlayed to stringResource(R.string.filter_never_played))
        collections.forEach { (c, members) -> add(LibraryFilter.Collection(c.id, c.name, members) to c.name) }
        if (games.any { it.item.metadataState == MetadataState.REVIEW }) add(LibraryFilter.Review to stringResource(R.string.filter_review))
        if (games.any { it.item.hidden }) add(LibraryFilter.Hidden to stringResource(R.string.filter_hidden))
    }
    val shown = remember(games, filter, sort, search) { applyLibraryQuery(games, filter, sort, search) }

    val searchTitle = stringResource(R.string.hint_search)
    fun openSearch() = keyboard.open(title = searchTitle, initial = search) { search = it }
    PadAction(PadButton.SELECT) { sort = LibrarySort.entries[(sort.ordinal + 1) % LibrarySort.entries.size] }
    PadAction(PadButton.START) { openSearch() }
    PadAction(PadButton.X) { focused?.let(actions::context) }

    val sortLabel = stringResource(
        when (sort) {
            LibrarySort.TITLE -> R.string.sort_title
            LibrarySort.LAST_PLAYED -> R.string.sort_last_played
            LibrarySort.ADDED -> R.string.sort_added
            LibrarySort.PLAYTIME -> R.string.sort_playtime
        },
    )
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(R.string.hint_open)),
            ButtonHint(PadButton.X, stringResource(R.string.hint_options), onClick = { focused?.let(actions::context) }),
            ButtonHint(PadButton.SELECT, sortLabel, onClick = { sort = LibrarySort.entries[(sort.ordinal + 1) % LibrarySort.entries.size] }),
            ButtonHint(PadButton.START, stringResource(R.string.hint_search), onClick = ::openSearch),
        ),
    )

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            item {
                Chip(
                    label = if (search.isEmpty()) stringResource(R.string.hint_search) else "\"$search\"",
                    selected = search.isNotEmpty(),
                    icon = { Icon(Icons.Filled.Search, null, Modifier.padding(end = 6.dp)) },
                    onClick = { if (search.isNotEmpty()) search = "" else openSearch() },
                )
            }
            item {
                Chip(
                    label = sortLabel, selected = false,
                    icon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.padding(end = 6.dp)) },
                    onClick = { sort = LibrarySort.entries[(sort.ordinal + 1) % LibrarySort.entries.size] },
                )
            }
            items(filters, key = { it.second }) { (f, label) ->
                Chip(label, selected = f == filter, onClick = { filter = f })
            }
            item { Chip(stringResource(R.string.stats_title), selected = false, onClick = openStats) }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_empty_filter), style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurfaceMuted)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(LauncherTheme.dimens.coverWidth + 8.dp),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            items(shown, key = { it.item.id }) { entry ->
                val key = "library:${entry.item.id}"
                CoverCard(
                    entry = entry,
                    onClick = { actions.open(entry) },
                    onLongClick = { actions.context(entry) },
                    onFocused = { focused = entry; onFocusKey(key) },
                    showTitle = true,
                    animated = animatedArtwork,
                    modifier = Modifier.restoreFocusIf(
                        lastFocusedKey == key || (lastFocusedKey?.startsWith("library:") != true && entry === shown.first()),
                    ),
                )
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, icon: (@Composable () -> Unit)? = null) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.accent else colors.surfaceRaised,
        focusScale = 1.08f,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onAccent else colors.onSurface)
        }
    }
}
