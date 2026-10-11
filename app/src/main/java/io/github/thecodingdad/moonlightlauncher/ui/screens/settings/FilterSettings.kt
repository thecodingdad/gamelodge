package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterKind
import io.github.thecodingdad.moonlightlauncher.data.db.FilterScope
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.FilterEngine
import io.github.thecodingdad.moonlightlauncher.data.library.FilterRules
import io.github.thecodingdad.moonlightlauncher.data.library.GroupBy
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.builtinLabel
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Manage library filters: default filters, smart filters and manual groups, and where each appears. */
@Composable
fun FilterSettings(vm: LauncherViewModel, scope: FilterScope) {
    val apps = scope == FilterScope.APPS
    val allFilters by vm.filters.collectAsState()
    // Game and app filters are configured separately (legacy BOTH rows are game filters).
    val filters = allFilters.filter { (it.scope == FilterScope.APPS) == apps }
    val all by vm.allEntries.collectAsState()
    val collections by vm.collections.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val keyboard = LocalKeyboardController.current
    var editing by remember { mutableStateOf<String?>(null) }
    val smartTitle = stringResource(R.string.filter_new_smart)
    val groupTitle = stringResource(R.string.filter_new_group)

    SettingsPage(stringResource(if (apps) R.string.settings_app_filters else R.string.settings_filters)) {
        Text(stringResource(if (apps) R.string.app_filters_help else R.string.filters_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp, modifier = Modifier.padding(vertical = 6.dp)) {
            LauncherButton(
                smartTitle, icon = Icons.Filled.FilterAlt,
                onClick = { keyboard.open(smartTitle, "") { name -> if (name.isNotBlank()) vm.createSmartFilter(name.trim(), scope) { editing = it.id } } },
            )
            LauncherButton(
                groupTitle, icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onClick = { keyboard.open(groupTitle, "") { name -> if (name.isNotBlank()) vm.createGroup(name.trim(), scope) } },
            )
            LauncherButton(stringResource(R.string.filters_restore), icon = Icons.Filled.Restore, onClick = { vm.restoreDefaultFilters(scope) })
        }
        val members = collections.associate { (c, ids) -> c.id to ids.size }
        filters.sortedBy { it.sortOrder }.forEachIndexed { index, f ->
            val places = buildList {
                if (!apps && f.showInLibrary) add(stringResource(R.string.filter_place_library))
                if (apps && f.showInApps) add(stringResource(R.string.filter_place_apps))
                if (f.showOnHome) add(stringResource(R.string.filter_place_home))
            }.ifEmpty { listOf(stringResource(R.string.filter_place_none)) }
            val kind = when (f.kind) {
                FilterKind.BUILTIN -> stringResource(R.string.filter_kind_builtin)
                FilterKind.SMART -> stringResource(R.string.filter_kind_smart)
                FilterKind.GROUP -> stringResource(R.string.filter_kind_group, members[f.id] ?: 0)
            }
            NavRow(
                title = filterEntityLabel(f),
                subtitle = "$kind  ·  ${places.joinToString(", ")}",
                onClick = { editing = f.id },
                initialFocus = index == 0,
            )
        }
    }

    FilterEditorDialog(
        filter = filters.firstOrNull { it.id == editing },
        entries = all,
        hostNames = hosts.mapValues { it.value.name },
        vm = vm,
        onDismiss = { editing = null },
    )
}

@Composable
private fun filterEntityLabel(f: FilterEntity): String =
    f.name.ifBlank { f.builtin?.let { builtinLabel(it) } ?: "?" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterEditorDialog(
    filter: FilterEntity?,
    entries: List<LibraryEntry>,
    hostNames: Map<String, String>,
    vm: LauncherViewModel,
    onDismiss: () -> Unit,
) {
    Modal(visible = filter != null, onDismiss = onDismiss, title = filter?.let { filterEntityLabel(it) }, maxWidth = 760.dp) {
        val f = filter ?: return@Modal
        val rules = FilterRules.decode(f.rules)
        fun saveRules(transform: (FilterRules) -> FilterRules) = vm.saveFilter(f.copy(rules = FilterRules.encode(transform(rules))))
        val kind = if (f.scope == FilterScope.APPS) ItemKind.APP else ItemKind.GAME
        val games = entries.filter { it.item.kind == kind }
        Column(
            Modifier.heightIn(max = 560.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LauncherTextField(
                stringResource(R.string.filter_name), f.name, { vm.renameFilter(f, it) },
                placeholder = f.builtin?.let { builtinLabel(it) },
            )
            if (f.scope == FilterScope.APPS) {
                SwitchRow(stringResource(R.string.filter_show_apps), f.showInApps, { vm.saveFilter(f.copy(showInApps = it)) })
            } else {
                SwitchRow(stringResource(R.string.filter_show_library), f.showInLibrary, { vm.saveFilter(f.copy(showInLibrary = it)) })
            }
            SwitchRow(stringResource(R.string.filter_show_home), f.showOnHome, { vm.saveFilter(f.copy(showOnHome = it)) })

            when (f.kind) {
                FilterKind.SMART -> {
                    SectionHeader(stringResource(R.string.filter_rules))
                    ChoiceRow(
                        stringResource(R.string.filter_group_by), listOf<GroupBy?>(null) + GroupBy.entries, rules.groupBy,
                        label = { groupByLabel(it) },
                        onSelect = { v -> saveRules { it.copy(groupBy = v) } },
                        subtitle = stringResource(R.string.filter_group_by_sub),
                    )
                    ChoiceRow(
                        stringResource(R.string.filter_favorites), listOf(null, true, false), rules.favorite,
                        label = { triLabel(it) }, onSelect = { v -> saveRules { it.copy(favorite = v) } },
                    )
                    ChoiceRow(
                        stringResource(R.string.filter_played), listOf(null, true, false), rules.played,
                        label = { triLabel(it) }, onSelect = { v -> saveRules { it.copy(played = v) } },
                    )
                    ValuePicker(stringResource(R.string.filter_platforms), FilterEngine.allValues(games, GroupBy.PLATFORM), rules.platforms, { it }) { v ->
                        saveRules { it.copy(platforms = it.platforms.toggle(v)) }
                    }
                    ValuePicker(stringResource(R.string.filter_genres), FilterEngine.allValues(games, GroupBy.GENRE), rules.genres, { it }) { v ->
                        saveRules { it.copy(genres = it.genres.toggle(v)) }
                    }
                    ValuePicker(stringResource(R.string.filter_categories), FilterEngine.allValues(games, GroupBy.CATEGORY), rules.categories, { it }) { v ->
                        saveRules { it.copy(categories = it.categories.toggle(v)) }
                    }
                    val localLabel = stringResource(R.string.filter_local)
                    ValuePicker(
                        stringResource(R.string.filter_sources), FilterEngine.allValues(games, GroupBy.SOURCE), rules.sources,
                        { if (it == FilterRules.SOURCE_LOCAL) localLabel else hostNames[it] ?: entries.firstNotNullOfOrNull { e -> e.remoteTargets.firstOrNull { t -> t.hostUuid == it }?.hostName } ?: it },
                    ) { v -> saveRules { it.copy(sources = it.sources.toggle(v)) } }
                    val count = games.count { !it.item.hidden && FilterEngine.matches(it, rules) }
                    Text(stringResource(if (f.scope == FilterScope.APPS) R.string.filter_match_count_apps else R.string.filter_match_count, count), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                }
                FilterKind.GROUP -> Text(
                    stringResource(R.string.filter_group_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
                )
                FilterKind.BUILTIN -> Unit
            }

            io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
                LauncherButton(stringResource(R.string.move_up), icon = Icons.Filled.ArrowUpward, onClick = { vm.moveFilter(f.id, -1) })
                LauncherButton(stringResource(R.string.move_down), icon = Icons.Filled.ArrowDownward, onClick = { vm.moveFilter(f.id, 1) })
                HoldToConfirmButton(
                    stringResource(R.string.filter_delete), icon = Icons.Filled.Delete,
                    onConfirmed = { vm.deleteFilter(f); onDismiss() },
                )
            }
        }
    }
}

private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValuePicker(title: String, values: List<String>, selected: Set<String>, label: (String) -> String, onToggle: (String) -> Unit) {
    if (values.isEmpty() && selected.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface, modifier = Modifier.padding(top = 6.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (values + selected.filter { it !in values }).forEach { v ->
            Chip(label(v), selected = v in selected, onClick = { onToggle(v) })
        }
    }
}

@Composable
private fun groupByLabel(g: GroupBy?): String = stringResource(
    when (g) {
        null -> R.string.filter_group_none
        GroupBy.PLATFORM -> R.string.filter_platforms
        GroupBy.GENRE -> R.string.filter_genres
        GroupBy.CATEGORY -> R.string.filter_categories
        GroupBy.SOURCE -> R.string.filter_sources
    },
)

@Composable
private fun triLabel(v: Boolean?): String = stringResource(
    when (v) {
        null -> R.string.filter_any
        true -> R.string.yes
        false -> R.string.no
    },
)
