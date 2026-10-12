package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import io.github.thecodingdad.moonlightlauncher.ui.components.AppIcon
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.coverModel
import androidx.compose.material.icons.filled.Add
import io.github.thecodingdad.moonlightlauncher.data.library.FilterField
import io.github.thecodingdad.moonlightlauncher.data.library.FieldSection
import io.github.thecodingdad.moonlightlauncher.data.library.FieldType
import io.github.thecodingdad.moonlightlauncher.data.library.FilterCondition
import io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterKind
import io.github.thecodingdad.moonlightlauncher.data.db.FilterScope
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.library.FilterEngine
import io.github.thecodingdad.moonlightlauncher.data.library.FilterRules
import io.github.thecodingdad.moonlightlauncher.data.library.GroupBy
import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySort
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
    // A new group opens straight into picking its members.
    var pickMembers by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
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
                onClick = { keyboard.open(groupTitle, "") { name -> if (name.isNotBlank()) vm.createGroup(name.trim(), scope) { editing = it.id; pickMembers = true } } },
            )
            LauncherButton(stringResource(R.string.filters_restore), icon = Icons.Filled.Restore, onClick = { resetting = true })
        }
        val members = collections.associate { (c, ids) -> c.id to ids.size }
        ReorderableList(
            items = filters.sortedBy { it.sortOrder }, id = { it.id },
            onReorder = vm::reorderFilters,
        ) { index, f ->
            val places = buildList {
                if (!apps && f.showInLibrary) add(stringResource(R.string.filter_place_library))
                if (apps && f.showInApps) add(stringResource(R.string.filter_place_apps))
                if (f.showOnHome) add(stringResource(R.string.filter_place_home))
            }.ifEmpty { listOf(stringResource(R.string.filter_place_none)) }
            val kind = when (f.kind) {
                FilterKind.BUILTIN, FilterKind.SMART ->
                    stringResource(if (f.builtin != null) R.string.filter_kind_builtin else R.string.filter_kind_smart)
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

    Modal(visible = resetting, onDismiss = { resetting = false }, title = stringResource(R.string.filters_restore), maxWidth = 680.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.filters_reset_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherButton(
                stringResource(R.string.filters_reset_defaults), icon = Icons.Filled.Restore, primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.resetDefaultFilters(scope); resetting = false },
            )
            HoldToConfirmButton(
                stringResource(R.string.filters_reset_all), icon = Icons.Filled.Delete,
                modifier = Modifier.fillMaxWidth(),
                onConfirmed = { vm.resetAllFilters(scope); resetting = false },
            )
            LauncherButton(stringResource(R.string.action_cancel), modifier = Modifier.fillMaxWidth(), onClick = { resetting = false })
        }
    }

    FilterEditorDialog(
        filter = filters.firstOrNull { it.id == editing },
        entries = all,
        hostNames = hosts.mapValues { it.value.name },
        vm = vm,
        pickMembers = pickMembers,
        onDismiss = { editing = null; pickMembers = false },
    )
}

@Composable
internal fun filterEntityLabel(f: FilterEntity): String =
    f.name.ifBlank { f.builtin?.let { builtinLabel(it) } ?: "?" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterEditorDialog(
    filter: FilterEntity?,
    entries: List<LibraryEntry>,
    hostNames: Map<String, String>,
    vm: LauncherViewModel,
    pickMembers: Boolean,
    onDismiss: () -> Unit,
) {
    Modal(visible = filter != null, onDismiss = onDismiss, title = filter?.let { filterEntityLabel(it) }, maxWidth = 760.dp) {
        val f = filter ?: return@Modal
        var editingCondition by remember(f.id) { mutableStateOf<Int?>(null) }
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
                // Default filters are free filters too (old BUILTIN rows become one on the first change).
                FilterKind.SMART, FilterKind.BUILTIN -> {
                    val current = FilterEngine.effectiveRules(f)
                    fun saveRules(rules: FilterRules) =
                        vm.saveFilter(f.copy(kind = FilterKind.SMART, rules = FilterRules.encode(rules)))
                    fun saveConditions(list: List<FilterCondition>) = saveRules(current.copy(conditions = list))
                    SectionHeader(stringResource(R.string.filter_conditions))
                    Text(stringResource(R.string.filter_conditions_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    val labelFor = conditionValueLabel(entries, hostNames, vm)
                    // Playnite style: field name as title, the chosen values below.
                    current.conditions.forEachIndexed { index, condition ->
                        NavRow(
                            title = conditionTitle(condition),
                            onClick = { editingCondition = index },
                            subtitle = conditionValues(condition, labelFor),
                            icon = Icons.Filled.FilterAlt,
                        )
                    }
                    var pickingField by remember(f.id) { mutableStateOf(false) }
                    LauncherButton(stringResource(R.string.filter_condition_add), icon = Icons.Filled.Add, onClick = { pickingField = true })
                    FieldPickerDialog(pickingField, onDismiss = { pickingField = false }) { field ->
                        pickingField = false
                        saveConditions(current.conditions + FilterCondition(field, if (field.boolean) setOf("true") else emptySet()))
                        editingCondition = current.conditions.size
                    }
                    ChoiceRow(
                        stringResource(R.string.filter_group_by), listOf<GroupBy?>(null) + GroupBy.entries, current.groupBy,
                        label = { groupByLabel(it) },
                        onSelect = { v -> saveRules(current.copy(groupBy = v)) },
                        subtitle = stringResource(R.string.filter_group_by_sub),
                    )
                    if (current.groupBy != null) {
                        SwitchRow(
                            stringResource(R.string.filter_chip_show_name), current.chipShowName, { v -> saveRules(current.copy(chipShowName = v)) },
                            subtitle = stringResource(R.string.filter_show_name_sub),
                        )
                        SwitchRow(
                            stringResource(R.string.filter_row_show_name), current.rowShowName, { v -> saveRules(current.copy(rowShowName = v)) },
                            subtitle = stringResource(R.string.filter_show_name_sub),
                        )
                    }
                    ChoiceRow(
                        stringResource(R.string.filter_sort), listOf<LibrarySort?>(null) + LibrarySort.entries, current.sort,
                        label = { sortLabel(it) },
                        onSelect = { v -> saveRules(current.copy(sort = v)) },
                    )
                    ChoiceRow(
                        stringResource(R.string.filter_limit), listOf<Int?>(null, 10, 20, 30, 50, 100), current.limit,
                        label = { it?.toString() ?: stringResource(R.string.filter_limit_none) },
                        onSelect = { v -> saveRules(current.copy(limit = v)) },
                    )
                    val members by vm.collections.collectAsState()
                    val memberMap = members.associate { (c, ids) -> c.id to ids }
                    val count = FilterEngine.apply(games, current, memberMap).size
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
                FilterKind.GROUP -> {
                    Text(stringResource(R.string.filter_group_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    val collections by vm.collections.collectAsState()
                    val memberIds = collections.firstOrNull { it.first.id == f.id }?.second.orEmpty()
                    var picking by remember(f.id) { mutableStateOf(pickMembers) }
                    val apps = f.scope == FilterScope.APPS
                    NavRow(
                        title = stringResource(if (apps) R.string.group_pick_apps else R.string.group_pick_games),
                        onClick = { picking = true },
                        subtitle = stringResource(R.string.group_member_count, games.count { it.item.id in memberIds }),
                        icon = Icons.Filled.Checklist,
                    )
                    GroupMembersDialog(
                        visible = picking,
                        title = filterEntityLabel(f),
                        entries = games,
                        members = memberIds,
                        onToggle = { id, member -> vm.setInCollection(id, f.id, member) },
                        onDismiss = { picking = false },
                    )
                }
            }

            HoldToConfirmButton(
                stringResource(R.string.filter_delete), icon = Icons.Filled.Delete,
                modifier = Modifier.padding(top = 8.dp),
                onConfirmed = { vm.deleteFilter(f); onDismiss() },
            )
        }
    }
}

/** Checklist of all games (or apps) to pick the members of a group in one go. */
@Composable
private fun GroupMembersDialog(
    visible: Boolean,
    title: String,
    entries: List<LibraryEntry>,
    members: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Modal(visible = visible, onDismiss = onDismiss, title = title, maxWidth = 720.dp) {
        var query by remember { mutableStateOf("") }
        LauncherTextField(stringResource(R.string.hint_search), query, { query = it })
        Spacer(Modifier.height(12.dp))
        val q = TitleNormalizer.normalize(query)
        val shown = remember(entries, q) {
            entries
                .filter { q.isEmpty() || TitleNormalizer.normalize(it.item.title).contains(q) }
                .sortedBy { it.item.title.lowercase() }
        }
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.item.id }) { entry ->
                val member = entry.item.id in members
                FocusSurface(
                    onClick = { onToggle(entry.item.id, !member) },
                    modifier = Modifier.fillMaxWidth(),
                    color = LauncherTheme.colors.surfaceRaised,
                    focusScale = 1.02f,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MemberThumb(entry)
                        Text(
                            entry.item.title, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Checkbox(
                            checked = member, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = LauncherTheme.colors.accent),
                        )
                    }
                }
            }
        }
    }
}

/** Small cover (games) or icon (local apps) in front of a checklist row. */
@Composable
private fun MemberThumb(entry: LibraryEntry) {
    val pkg = entry.localTarget?.packageName
    val shape = RoundedCornerShape(6.dp)
    if (entry.item.kind == ItemKind.APP && pkg != null) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { AppIcon(pkg, Modifier.size(40.dp)) }
    } else {
        ArtworkImage(
            entry.coverModel(), entry.item.title,
            Modifier.width(33.dp).height(44.dp).clip(shape),
            showTitleOnPlaceholder = false,
        )
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
        GroupBy.PLATFORM -> R.string.filter_field_platform
        GroupBy.GENRE -> R.string.filter_field_genre
        GroupBy.CATEGORY -> R.string.filter_field_category
        GroupBy.SOURCE -> R.string.filter_field_source
        GroupBy.HOST -> R.string.filter_field_host
        GroupBy.LIBRARY -> R.string.filter_field_library
        GroupBy.DEVELOPER -> R.string.filter_field_developer
        GroupBy.PUBLISHER -> R.string.filter_field_publisher
        GroupBy.SERIES -> R.string.filter_field_series
        GroupBy.FEATURE -> R.string.filter_field_feature
        GroupBy.AGE_RATING -> R.string.filter_field_age_rating
        GroupBy.RELEASE_YEAR -> R.string.filter_field_release_year
    },
)

@Composable
private fun sortLabel(sort: LibrarySort?): String = stringResource(
    when (sort) {
        null -> R.string.filter_sort_title
        LibrarySort.TITLE -> R.string.filter_sort_title
        LibrarySort.LAST_PLAYED -> R.string.filter_sort_last_played
        LibrarySort.ADDED -> R.string.filter_sort_added
        LibrarySort.PLAYTIME -> R.string.filter_sort_playtime
    },
)

@Composable
private fun fieldLabel(field: FilterField): String = stringResource(
    when (field) {
        FilterField.NAME -> R.string.filter_field_name
        FilterField.PLATFORM -> R.string.filter_field_platform
        FilterField.SOURCE -> R.string.filter_field_source
        FilterField.LIBRARY -> R.string.filter_field_library
        FilterField.CATEGORY -> R.string.filter_field_category
        FilterField.GROUP -> R.string.filter_field_group
        FilterField.INSTALLED -> R.string.filter_field_installed
        FilterField.NOT_INSTALLED -> R.string.not_installed
        FilterField.GENRE -> R.string.filter_field_genre
        FilterField.DEVELOPER -> R.string.filter_field_developer
        FilterField.PUBLISHER -> R.string.filter_field_publisher
        FilterField.SERIES -> R.string.filter_field_series
        FilterField.FEATURE -> R.string.filter_field_feature
        FilterField.AGE_RATING -> R.string.filter_field_age_rating
        FilterField.RELEASE_YEAR -> R.string.filter_field_release_year
        FilterField.RATING -> R.string.filter_field_rating
        FilterField.PLAYED -> R.string.filter_field_played
        FilterField.LAST_PLAYED -> R.string.filter_field_last_played
        FilterField.PLAYTIME -> R.string.filter_field_playtime
        FilterField.ADDED -> R.string.filter_field_added
        FilterField.FAVORITE -> R.string.filter_field_favorite
        FilterField.HIDDEN -> R.string.filter_field_hidden
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
    val bucketLabels = FilterField.entries.flatMap { f -> f.buckets.map { (f to it) to bucketLabel(f, it) } }.toMap()
    return { field, value ->
        when (field) {
            FilterField.SOURCE -> if (value == FilterRules.SOURCE_LOCAL) localLabel else hostNames[value]
                ?: entries.firstNotNullOfOrNull { e -> e.remoteTargets.firstOrNull { t -> t.hostUuid == value }?.hostName } ?: value
            FilterField.GROUP -> groupNames[value] ?: value
            else -> if (field.type == FieldType.BUCKET) bucketLabels[field to value] ?: value else value
        }
    }
}

/** Edit one condition: include / exclude and its values (yes / no for switches). */
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
    val keyboard = LocalKeyboardController.current
    Modal(visible = condition != null, onDismiss = onDismiss, title = condition?.let { fieldLabel(it.field) }, maxWidth = 760.dp) {
        val c = condition ?: return@Modal
        val field = c.field
        Column(
            Modifier.heightIn(max = 560.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (field.boolean) {
                ChoiceRow(
                    fieldLabel(field), listOf(false, true), c.negate,
                    label = { booleanLabel(field, !it) },
                    onSelect = { onChange(c.copy(negate = it)) },
                    initialFocus = true,
                )
            } else {
                ChoiceRow(
                    stringResource(R.string.filter_mode), listOf(false, true), c.negate,
                    label = { stringResource(if (it) R.string.filter_mode_exclude else R.string.filter_mode_include) },
                    onSelect = { onChange(c.copy(negate = it)) },
                    subtitle = stringResource(if (c.negate) R.string.filter_mode_exclude_sub else R.string.filter_mode_include_sub),
                    initialFocus = true,
                )
                if (field.multiValue || field.type == FieldType.TEXT) {
                    SwitchRow(
                        stringResource(R.string.filter_match_all), c.matchAll, { onChange(c.copy(matchAll = it)) },
                        subtitle = stringResource(R.string.filter_match_all_sub),
                    )
                }
                val options = when (field.type) {
                    FieldType.BUCKET -> field.buckets
                    FieldType.TEXT -> emptyList()
                    else -> if (field == FilterField.GROUP) groupIds
                    else entries.flatMap { FilterEngine.values(it, field) }.distinct().let { list ->
                        if (field == FilterField.RELEASE_YEAR) list.sortedDescending() else list.sortedBy { it.lowercase() }
                    }
                }
                if (field.type == FieldType.TEXT) {
                    Text(stringResource(R.string.filter_name_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    val title = stringResource(R.string.filter_name_add)
                    LauncherButton(title, icon = Icons.Filled.Add, onClick = {
                        keyboard.open(title, "") { text -> text.trim().takeIf { it.isNotEmpty() }?.let { onChange(c.copy(values = c.values + it)) } }
                    })
                } else {
                    Text(stringResource(R.string.filter_values_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    if (options.isEmpty()) {
                        Text(stringResource(R.string.filter_values_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (options + c.values.filter { it !in options }).forEach { v ->
                        Chip(labelFor(field, v), selected = v in c.values, onClick = { onChange(c.copy(values = c.values.toggle(v))) })
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

/** Picks the field of a new condition, sorted into sections like Playnite's filter panel. */
@Composable
private fun FieldPickerDialog(visible: Boolean, onDismiss: () -> Unit, onPick: (FilterField) -> Unit) {
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(R.string.filter_condition_add), maxWidth = 640.dp) {
        Column(Modifier.heightIn(max = 600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldSection.entries.forEachIndexed { s, section ->
                SectionHeader(
                    stringResource(
                        when (section) {
                            FieldSection.GAME -> R.string.filter_section_game
                            FieldSection.METADATA -> R.string.filter_section_metadata
                            FieldSection.ACTIVITY -> R.string.filter_section_activity
                            FieldSection.STATUS -> R.string.filter_section_status
                        },
                    ),
                )
                FilterField.entries.filter { it.section == section }.forEachIndexed { i, field ->
                    NavRow(fieldLabel(field), { onPick(field) }, initialFocus = s == 0 && i == 0)
                }
            }
        }
    }
}

/** "Plattform" or "Plattform (nicht)"; switches just show their name. */
@Composable
private fun conditionTitle(c: FilterCondition): String =
    if (!c.field.boolean && c.negate) stringResource(R.string.filter_field_negated, fieldLabel(c.field)) else fieldLabel(c.field)

/** "PC, Nintendo Switch" (or "… und …" when all must match), "Ja" / "Nie" for switches. */
@Composable
private fun conditionValues(c: FilterCondition, labelFor: (FilterField, String) -> String): String {
    if (c.field.boolean) return booleanLabel(c.field, !c.negate)
    if (c.values.isEmpty()) return stringResource(R.string.filter_condition_no_values)
    val separator = if (c.matchAll) stringResource(R.string.filter_values_and) else ", "
    val texts = c.values.map { labelFor(c.field, it) }
    return if (c.field.type == FieldType.TEXT) texts.joinToString(separator) { "„$it“" } else texts.joinToString(separator)
}

@Composable
private fun booleanLabel(field: FilterField, yes: Boolean): String = stringResource(
    when {
        yes -> R.string.yes
        field == FilterField.PLAYED -> R.string.filter_never
        else -> R.string.no
    },
)

@Composable
private fun bucketLabel(field: FilterField, key: String): String = when (field) {
    FilterField.PLAYTIME -> stringResource(
        when (key) {
            "none" -> R.string.filter_playtime_none
            "lt1" -> R.string.filter_playtime_lt1
            "1to10" -> R.string.filter_playtime_1to10
            "10to100" -> R.string.filter_playtime_10to100
            "100to500" -> R.string.filter_playtime_100to500
            else -> R.string.filter_playtime_gt500
        },
    )
    FilterField.RATING -> when (key) {
        "lt60" -> stringResource(R.string.filter_rating_lt60)
        "none" -> stringResource(R.string.filter_rating_none)
        else -> stringResource(R.string.filter_rating_from, key)
    }
    else -> stringResource(
        when (key) {
            "today" -> R.string.filter_period_today
            "yesterday" -> R.string.filter_period_yesterday
            "week" -> R.string.filter_period_week
            "month" -> R.string.filter_period_month
            "year" -> R.string.filter_period_year
            "older" -> R.string.filter_period_older
            else -> R.string.filter_never
        },
    )
}

/**
 * Filter rows with a drag handle on the right. Touch: drag the handle. Controller: A on the
 * handle picks the filter up, D-pad up/down moves it, A or B drops it.
 */
@Composable
internal fun <T> ReorderableList(
    items: List<T>,
    id: (T) -> String,
    onReorder: (List<String>) -> Unit,
    row: @Composable (Int, T) -> Unit,
) {
    val ids = items.map(id)
    val byId = items.associateBy(id)
    var order by remember(ids) { mutableStateOf(ids) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val tops = remember { HashMap<String, Float>() }
    val heights = remember { HashMap<String, Float>() }

    fun commit() {
        if (order != ids) onReorder(order)
    }
    fun move(id: String, delta: Int): Boolean {
        val from = order.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in order.indices) return false
        order = order.toMutableList().also { it.add(to, it.removeAt(from)) }
        return true
    }
    // B drops a picked-up row instead of leaving the page.
    io.github.thecodingdad.moonlightlauncher.ui.input.PadAction(
        io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.B, enabled = picked != null,
    ) { picked = null; commit() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        order.forEachIndexed { index, id ->
            val f = byId[id] ?: return@forEachIndexed
            key(id) {
                val active = dragging == id || picked == id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (active) 1f else 0f)
                        // Measured before the drag translation: the row's resting place.
                        .onGloballyPositioned { tops[id] = it.positionInParent().y; heights[id] = it.size.height.toFloat() }
                        .graphicsLayer { translationY = if (dragging == id) dragOffset else 0f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { row(index, f) }
                    io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface(
                        onClick = {
                            if (picked == id) { picked = null; commit() } else picked = id
                        },
                        color = if (active) LauncherTheme.colors.accent else LauncherTheme.colors.surface,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(56.dp)
                            .onPreviewKeyEvent { event ->
                                if (picked != id) return@onPreviewKeyEvent false
                                val delta = when (event.key) {
                                    Key.DirectionUp -> -1
                                    Key.DirectionDown -> 1
                                    Key.DirectionLeft, Key.DirectionRight -> 0
                                    else -> return@onPreviewKeyEvent false
                                }
                                if (event.type == KeyEventType.KeyDown && delta != 0) move(id, delta)
                                true
                            }
                            // Keyed by the list as well: after a reorder the list state is new, and a
                            // gesture still bound to the old one moved nothing and saved the old order.
                            .pointerInput(id, ids) {
                                detectDragGestures(
                                    onDragStart = { dragging = id; dragOffset = 0f },
                                    onDragEnd = { dragging = null; dragOffset = 0f; commit() },
                                    onDragCancel = { dragging = null; dragOffset = 0f; order = ids },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val i = order.indexOf(id)
                                    val top = tops[id] ?: return@detectDragGestures
                                    val h = heights[id] ?: return@detectDragGestures
                                    val center = top + dragOffset + h / 2
                                    // Swap with a neighbour once the dragged row's centre passes its centre.
                                    order.getOrNull(i + 1)?.let { next ->
                                        val nTop = tops[next] ?: return@let
                                        val nH = heights[next] ?: return@let
                                        if (center > nTop + nH / 2 && move(id, 1)) dragOffset -= nTop + nH - (top + h)
                                    }
                                    order.getOrNull(i - 1)?.let { prev ->
                                        val pTop = tops[prev] ?: return@let
                                        val pH = heights[prev] ?: return@let
                                        if (center < pTop + pH / 2 && move(id, -1)) dragOffset += top - pTop
                                    }
                                }
                            },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.DragHandle, stringResource(R.string.filter_drag),
                                tint = if (active) LauncherTheme.colors.onAccent else LauncherTheme.colors.onSurfaceMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}
