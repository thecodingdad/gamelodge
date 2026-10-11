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
import androidx.compose.material.icons.filled.Add
import io.github.thecodingdad.moonlightlauncher.data.library.FilterField
import io.github.thecodingdad.moonlightlauncher.data.library.FilterCondition
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
        var editingCondition by remember(f.id) { mutableStateOf<Int?>(null) }
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
                    val current = rules.normalized()
                    fun saveConditions(list: List<FilterCondition>) =
                        vm.saveFilter(f.copy(rules = FilterRules.encode(current.copy(conditions = list))))
                    SectionHeader(stringResource(R.string.filter_conditions))
                    Text(stringResource(R.string.filter_conditions_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    val labelFor = conditionValueLabel(entries, hostNames, vm)
                    current.conditions.forEachIndexed { index, condition ->
                        NavRow(
                            title = fieldLabel(condition.field) + "  " + stringResource(if (condition.negate) R.string.filter_is_not else R.string.filter_is),
                            onClick = { editingCondition = index },
                            subtitle = if (condition.field.boolean) null else condition.values.joinToString(", ") { labelFor(condition.field, it) }
                                .ifEmpty { stringResource(R.string.filter_condition_no_values) },
                            icon = Icons.Filled.FilterAlt,
                        )
                    }
                    LauncherButton(
                        stringResource(R.string.filter_condition_add), icon = Icons.Filled.Add,
                        onClick = {
                            saveConditions(current.conditions + FilterCondition(FilterField.PLATFORM))
                            editingCondition = current.conditions.size
                        },
                    )
                    ChoiceRow(
                        stringResource(R.string.filter_group_by), listOf<GroupBy?>(null) + GroupBy.entries, current.groupBy,
                        label = { groupByLabel(it) },
                        onSelect = { v -> vm.saveFilter(f.copy(rules = FilterRules.encode(current.copy(groupBy = v)))) },
                        subtitle = stringResource(R.string.filter_group_by_sub),
                    )
                    val members by vm.collections.collectAsState()
                    val memberMap = members.associate { (c, ids) -> c.id to ids }
                    val count = games.count { !it.item.hidden && FilterEngine.matches(it, current, memberMap) }
                    Text(stringResource(if (f.scope == FilterScope.APPS) R.string.filter_match_count_apps else R.string.filter_match_count, count), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)

                    ConditionDialog(
                        condition = editingCondition?.let { current.conditions.getOrNull(it) },
                        entries = games,
                        labelFor = labelFor,
                        groupIds = members.map { it.first.id },
                        onChange = { updated -> editingCondition?.let { i -> saveConditions(current.conditions.toMutableList().also { it[i] = updated }) } },
                        onDelete = { editingCondition?.let { i -> saveConditions(current.conditions.filterIndexed { j, _ -> j != i }) }; editingCondition = null },
                        onDismiss = { editingCondition = null },
                    )
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

@Composable
private fun fieldLabel(field: FilterField): String = stringResource(
    when (field) {
        FilterField.PLATFORM -> R.string.filter_field_platform
        FilterField.GENRE -> R.string.filter_field_genre
        FilterField.CATEGORY -> R.string.filter_field_category
        FilterField.SOURCE -> R.string.filter_field_source
        FilterField.GROUP -> R.string.filter_field_group
        FilterField.FAVORITE -> R.string.filter_field_favorite
        FilterField.PLAYED -> R.string.filter_field_played
        FilterField.REVIEW -> R.string.filter_field_review
    },
)

/** Display text for a condition value (host and group ids become names). */
@Composable
private fun conditionValueLabel(
    entries: List<LibraryEntry>,
    hostNames: Map<String, String>,
    vm: LauncherViewModel,
): (FilterField, String) -> String {
    val localLabel = stringResource(R.string.filter_local)
    val collections by vm.collections.collectAsState()
    val groupNames = collections.associate { (c, _) -> c.id to c.name }
    return { field, value ->
        when (field) {
            FilterField.SOURCE -> if (value == FilterRules.SOURCE_LOCAL) localLabel else hostNames[value]
                ?: entries.firstNotNullOfOrNull { e -> e.remoteTargets.firstOrNull { t -> t.hostUuid == value }?.hostName } ?: value
            FilterField.GROUP -> groupNames[value] ?: value
            else -> value
        }
    }
}

/** Edit one condition: field, "is / is not" and its values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionDialog(
    condition: FilterCondition?,
    entries: List<LibraryEntry>,
    labelFor: (FilterField, String) -> String,
    groupIds: List<String>,
    onChange: (FilterCondition) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Modal(visible = condition != null, onDismiss = onDismiss, title = stringResource(R.string.filter_condition), maxWidth = 760.dp) {
        val c = condition ?: return@Modal
        Column(
            Modifier.heightIn(max = 560.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChoiceRow(
                stringResource(R.string.filter_field), FilterField.entries, c.field,
                label = { fieldLabel(it) },
                onSelect = { onChange(FilterCondition(it, if (it.boolean) setOf("true") else emptySet(), c.negate)) },
                initialFocus = true,
            )
            ChoiceRow(
                stringResource(R.string.filter_operator), listOf(false, true), c.negate,
                label = { stringResource(if (it) R.string.filter_is_not else R.string.filter_is) },
                onSelect = { onChange(c.copy(negate = it)) },
            )
            if (!c.field.boolean) {
                val options = when (c.field) {
                    FilterField.PLATFORM -> FilterEngine.allValues(entries, GroupBy.PLATFORM)
                    FilterField.GENRE -> FilterEngine.allValues(entries, GroupBy.GENRE)
                    FilterField.CATEGORY -> FilterEngine.allValues(entries, GroupBy.CATEGORY)
                    FilterField.SOURCE -> FilterEngine.allValues(entries, GroupBy.SOURCE)
                    FilterField.GROUP -> groupIds
                    else -> emptyList()
                }
                Text(stringResource(R.string.filter_values_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                if (options.isEmpty()) {
                    Text(stringResource(R.string.filter_values_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (options + c.values.filter { it !in options }).forEach { v ->
                        Chip(labelFor(c.field, v), selected = v in c.values, onClick = { onChange(c.copy(values = c.values.toggle(v))) })
                    }
                }
            }
            io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
                LauncherButton(stringResource(R.string.action_done), onClick = onDismiss, primary = true)
                LauncherButton(stringResource(R.string.filter_condition_delete), onClick = onDelete, icon = Icons.Filled.Delete)
            }
        }
    }
}
