package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.relocation.bringIntoViewRequester
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.fadingEdges
import io.github.thecodingdad.gamelodge.ui.components.mouseDragScroll
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
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
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import io.github.thecodingdad.gamelodge.ui.components.AppIcon
import io.github.thecodingdad.gamelodge.ui.components.CoverImage
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import androidx.compose.material.icons.filled.Add
import io.github.thecodingdad.gamelodge.data.library.FilterField
import io.github.thecodingdad.gamelodge.data.library.FieldSection
import io.github.thecodingdad.gamelodge.data.library.FieldType
import io.github.thecodingdad.gamelodge.data.library.FilterCondition
import io.github.thecodingdad.gamelodge.data.db.FilterEntity
import io.github.thecodingdad.gamelodge.data.db.FilterKind
import io.github.thecodingdad.gamelodge.data.db.FilterScope
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.library.FilterEngine
import io.github.thecodingdad.gamelodge.data.library.FilterRules
import io.github.thecodingdad.gamelodge.data.library.GroupBy
import io.github.thecodingdad.gamelodge.data.library.TitleNormalizer
import io.github.thecodingdad.gamelodge.data.library.LibrarySort
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.library.Chip
import io.github.thecodingdad.gamelodge.ui.screens.library.builtinLabel
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

/**
 * Manage filters: default filters, smart filters and manual groups, and where each appears. Game
 * and app filters share the page; chips on top switch between them ([initialScope] first).
 */
@Composable
fun FilterSettings(vm: LauncherViewModel, initialScope: FilterScope) {
    var scope by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialScope) }
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
    val smartTitle = stringResource(Res.string.filter_new_smart)
    val groupTitle = stringResource(Res.string.filter_new_group)

    SettingsPage(stringResource(Res.string.settings_filters)) {
        // Games / apps (LB/RB already switch the main tabs here).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(stringResource(Res.string.filters_scope_games), selected = !apps, onClick = { scope = FilterScope.GAMES; editing = null })
            Chip(stringResource(Res.string.filters_scope_apps), selected = apps, onClick = { scope = FilterScope.APPS; editing = null })
        }
        Text(stringResource(if (apps) Res.string.app_filters_help else Res.string.filters_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 12.dp, modifier = Modifier.padding(vertical = 6.dp)) {
            LauncherButton(
                smartTitle, icon = Icons.Filled.FilterAlt,
                onClick = { keyboard.open(smartTitle, "") { name -> if (name.isNotBlank()) vm.createSmartFilter(name.trim(), scope) { editing = it.id } } },
            )
            LauncherButton(
                groupTitle, icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                onClick = { keyboard.open(groupTitle, "") { name -> if (name.isNotBlank()) vm.createGroup(name.trim(), scope) { editing = it.id; pickMembers = true } } },
            )
            LauncherButton(stringResource(Res.string.filters_restore), icon = Icons.Filled.Restore, onClick = { resetting = true })
        }
        val members = collections.associate { (c, ids) -> c.id to ids.size }
        ReorderableList(
            items = filters.sortedBy { it.sortOrder }, id = { it.id },
            onReorder = vm::reorderFilters,
        ) { index, f ->
            val places = buildList {
                if (!apps && f.showInLibrary) add(stringResource(Res.string.filter_place_library))
                if (apps && f.showInApps) add(stringResource(Res.string.filter_place_apps))
                if (f.showOnHome) add(stringResource(Res.string.filter_place_home))
            }.ifEmpty { listOf(stringResource(Res.string.filter_place_none)) }
            val kind = when (f.kind) {
                FilterKind.BUILTIN, FilterKind.SMART ->
                    stringResource(if (f.builtin != null) Res.string.filter_kind_builtin else Res.string.filter_kind_smart)
                FilterKind.GROUP -> stringResource(Res.string.filter_kind_group, members[f.id] ?: 0)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    NavRow(
                        title = filterEntityLabel(f),
                        subtitle = "$kind  ·  ${places.joinToString(", ")}",
                        onClick = { editing = f.id },
                        initialFocus = index == 0,
                    )
                }
                // Chip visibility at a glance and one tap/A away.
                val visible = if (apps) f.showInApps else f.showInLibrary
                io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                    onClick = { vm.saveFilter(if (apps) f.copy(showInApps = !visible) else f.copy(showInLibrary = !visible)) },
                    modifier = Modifier.padding(start = 8.dp).size(56.dp),
                    color = LauncherTheme.colors.surface,
                    clickSound = io.github.thecodingdad.gamelodge.ui.feedback.UiSound.TOGGLE,
                ) {
                    androidx.compose.material3.Icon(
                        if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        stringResource(if (visible) Res.string.filter_chip_visible else Res.string.filter_chip_hidden),
                        tint = if (visible) LauncherTheme.colors.onSurface else LauncherTheme.colors.onSurfaceMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.home_rows_section), SettingsTarget.Page(SettingsPageId.HOME_SCREEN), highlight = stringResource(Res.string.home_rows_section), subtitle = stringResource(Res.string.settings_home_screen)),
        )
    }

    Modal(visible = resetting, onDismiss = { resetting = false }, title = stringResource(Res.string.filters_restore), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD, closeButton = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.filters_reset_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherButton(
                stringResource(Res.string.filters_reset_defaults), icon = Icons.Filled.Restore, primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.resetDefaultFilters(scope); resetting = false },
            )
            HoldToConfirmButton(
                stringResource(Res.string.filters_reset_all), icon = Icons.Filled.Delete,
                modifier = Modifier.fillMaxWidth(),
                onConfirmed = { vm.resetAllFilters(scope); resetting = false },
            )
            LauncherButton(stringResource(Res.string.action_cancel), modifier = Modifier.fillMaxWidth(), onClick = { resetting = false })
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
    Modal(visible = filter != null, onDismiss = onDismiss, title = filter?.let { filterEntityLabel(it) }, size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        val f = filter ?: return@Modal
        var editingCondition by remember(f.id) { mutableStateOf<Int?>(null) }
        val kind = if (f.scope == FilterScope.APPS) ItemKind.APP else ItemKind.GAME
        val games = entries.filter { it.item.kind == kind }
        Column(
            Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LauncherTextField(
                stringResource(Res.string.filter_name), f.name, { vm.renameFilter(f, it) },
                placeholder = f.builtin?.let { builtinLabel(it) },
            )
            if (f.scope == FilterScope.APPS) {
                SwitchRow(stringResource(Res.string.filter_show_apps), f.showInApps, { vm.saveFilter(f.copy(showInApps = it)) })
            } else {
                SwitchRow(stringResource(Res.string.filter_show_library), f.showInLibrary, { vm.saveFilter(f.copy(showInLibrary = it)) })
            }
            SwitchRow(stringResource(Res.string.filter_show_home), f.showOnHome, { vm.saveFilter(f.copy(showOnHome = it)) })

            when (f.kind) {
                // Default filters are free filters too (old BUILTIN rows become one on the first change).
                FilterKind.SMART, FilterKind.BUILTIN -> {
                    val current = FilterEngine.effectiveRules(f)
                    fun saveRules(rules: FilterRules) =
                        vm.saveFilter(f.copy(kind = FilterKind.SMART, rules = FilterRules.encode(rules)))
                    fun saveConditions(list: List<FilterCondition>) = saveRules(current.copy(conditions = list))
                    SectionHeader(stringResource(Res.string.filter_conditions))
                    Text(stringResource(Res.string.filter_conditions_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
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
                    LauncherButton(stringResource(Res.string.filter_condition_add), icon = Icons.Filled.Add, onClick = { pickingField = true })
                    FieldPickerDialog(pickingField, onDismiss = { pickingField = false }) { field ->
                        pickingField = false
                        saveConditions(current.conditions + FilterCondition(field, if (field.boolean) setOf("true") else emptySet()))
                        editingCondition = current.conditions.size
                    }
                    ChoiceRow(
                        stringResource(Res.string.filter_group_by), listOf<GroupBy?>(null) + GroupBy.entries, current.groupBy,
                        label = { groupByLabel(it) },
                        onSelect = { v -> saveRules(current.copy(groupBy = v)) },
                        subtitle = stringResource(Res.string.filter_group_by_sub),
                    )
                    if (current.groupBy != null) {
                        SwitchRow(
                            stringResource(Res.string.filter_chip_show_name), current.chipShowName, { v -> saveRules(current.copy(chipShowName = v)) },
                            subtitle = stringResource(Res.string.filter_show_name_sub),
                        )
                        SwitchRow(
                            stringResource(Res.string.filter_row_show_name), current.rowShowName, { v -> saveRules(current.copy(rowShowName = v)) },
                            subtitle = stringResource(Res.string.filter_show_name_sub),
                        )
                    }
                    // The same picker as in the library: order and direction.
                    var sorting by remember { mutableStateOf(false) }
                    val order = current.sort ?: LibrarySort.TITLE
                    NavRow(
                        stringResource(Res.string.filter_sort), { sorting = true },
                        trailing = sortLabel(current.sort) + if (current.sortAscending ?: order.defaultAscending) " ↑" else " ↓",
                    )
                    io.github.thecodingdad.gamelodge.ui.screens.library.SortDialog(
                        sorting, order, current.sortAscending, manual = false,
                        onChange = { s, a -> saveRules(current.copy(sort = s.takeIf { it != LibrarySort.TITLE || a != null }, sortAscending = a)) },
                        onDismiss = { sorting = false },
                    )
                    ChoiceRow(
                        stringResource(Res.string.filter_limit), listOf<Int?>(null, 10, 20, 30, 50, 100), current.limit,
                        label = { it?.toString() ?: stringResource(Res.string.filter_limit_none) },
                        onSelect = { v -> saveRules(current.copy(limit = v)) },
                    )
                    val members by vm.collections.collectAsState()
                    val memberMap = members.associate { (c, ids) -> c.id to ids.toSet() }
                    val count = FilterEngine.apply(games, current, memberMap).size
                    Text(stringResource(if (f.scope == FilterScope.APPS) Res.string.filter_match_count_apps else Res.string.filter_match_count, count), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)

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
                    Text(stringResource(Res.string.filter_group_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    val collections by vm.collections.collectAsState()
                    val memberIds = collections.firstOrNull { it.first.id == f.id }?.second.orEmpty().toSet()
                    var picking by remember(f.id) { mutableStateOf(pickMembers) }
                    val apps = f.scope == FilterScope.APPS
                    NavRow(
                        title = stringResource(if (apps) Res.string.group_pick_apps else Res.string.group_pick_games),
                        onClick = { picking = true },
                        subtitle = stringResource(Res.string.group_member_count, games.count { it.item.id in memberIds }),
                        icon = Icons.Filled.Checklist,
                    )
                    var ordering by remember(f.id) { mutableStateOf(false) }
                    val byId = games.associateBy { it.item.id }
                    val ordered = memberIds.mapNotNull { byId[it] }
                    if (ordered.size > 1) {
                        NavRow(
                            title = stringResource(Res.string.group_order),
                            onClick = { ordering = true },
                            subtitle = stringResource(Res.string.group_order_sub),
                            icon = Icons.Filled.DragHandle,
                        )
                    }
                    Modal(visible = ordering, onDismiss = { ordering = false }, title = filterEntityLabel(f), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
                        io.github.thecodingdad.gamelodge.ui.components.ScrollAreaColumn(Modifier.weight(1f, fill = false).popupHeightIn(560.dp)) {
                            ReorderableList(ordered, id = { it.item.id }, onReorder = { vm.reorderGroup(f.id, it) }) { _, entry ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(LauncherTheme.dimens.cornerRadius))
                                        .background(LauncherTheme.colors.surfaceRaised)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    MemberThumb(entry)
                                    Text(
                                        entry.item.title, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
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
                stringResource(Res.string.filter_delete), icon = Icons.Filled.Delete,
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
    Modal(visible = visible, onDismiss = onDismiss, title = title, size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        var query by remember { mutableStateOf("") }
        LauncherTextField(stringResource(Res.string.hint_search), query, { query = it })
        Spacer(Modifier.height(12.dp))
        val q = TitleNormalizer.normalize(query)
        val shown = remember(entries, q) {
            entries
                .filter { q.isEmpty() || TitleNormalizer.normalize(it.item.title).contains(q) }
                .sortedBy { it.item.title.lowercase() }
        }
        val pickerList = io.github.thecodingdad.gamelodge.ui.input.rememberStickLazyListState()
        LazyColumn(state = pickerList, modifier = Modifier.weight(1f, fill = false).popupHeightIn(460.dp).fadingEdges(pickerList).mouseDragScroll(pickerList), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        CoverImage(entry, Modifier.width(33.dp).height(44.dp).clip(shape), showTitleOnPlaceholder = false)
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
        null -> Res.string.filter_group_none
        GroupBy.PLATFORM -> Res.string.filter_field_platform
        GroupBy.GENRE -> Res.string.filter_field_genre
        GroupBy.CATEGORY -> Res.string.filter_field_category
        GroupBy.SOURCE -> Res.string.filter_field_source
        GroupBy.HOST -> Res.string.filter_field_host
        GroupBy.LIBRARY -> Res.string.filter_field_library
        GroupBy.DEVELOPER -> Res.string.filter_field_developer
        GroupBy.PUBLISHER -> Res.string.filter_field_publisher
        GroupBy.SERIES -> Res.string.filter_field_series
        GroupBy.FEATURE -> Res.string.filter_field_feature
        GroupBy.AGE_RATING -> Res.string.filter_field_age_rating
        GroupBy.RELEASE_YEAR -> Res.string.filter_field_release_year
    },
)

@Composable
private fun sortLabel(sort: LibrarySort?): String = stringResource(
    when (sort) {
        null -> Res.string.filter_sort_title
        LibrarySort.TITLE -> Res.string.filter_sort_title
        LibrarySort.LAST_PLAYED -> Res.string.filter_sort_last_played
        LibrarySort.ADDED -> Res.string.filter_sort_added
        LibrarySort.PLAYTIME -> Res.string.filter_sort_playtime
        LibrarySort.TIME_TO_BEAT -> Res.string.filter_sort_time_to_beat
        LibrarySort.USER_RATING -> Res.string.rating_own
        LibrarySort.CRITIC_RATING -> Res.string.rating_critic
        LibrarySort.COMMUNITY_RATING -> Res.string.rating_community
        LibrarySort.MANUAL -> Res.string.filter_sort_manual
    },
)

@Composable
private fun fieldLabel(field: FilterField): String = stringResource(
    when (field) {
        FilterField.NAME -> Res.string.filter_field_name
        FilterField.PLATFORM -> Res.string.filter_field_platform
        FilterField.SOURCE -> Res.string.filter_field_source
        FilterField.LIBRARY -> Res.string.filter_field_library
        FilterField.CATEGORY -> Res.string.filter_field_category
        FilterField.GROUP -> Res.string.filter_field_group
        FilterField.INSTALLED -> Res.string.filter_field_installed
        FilterField.NOT_INSTALLED -> Res.string.not_installed
        FilterField.GENRE -> Res.string.filter_field_genre
        FilterField.DEVELOPER -> Res.string.filter_field_developer
        FilterField.PUBLISHER -> Res.string.filter_field_publisher
        FilterField.SERIES -> Res.string.filter_field_series
        FilterField.FEATURE -> Res.string.filter_field_feature
        FilterField.AGE_RATING -> Res.string.filter_field_age_rating
        FilterField.RELEASE_YEAR -> Res.string.filter_field_release_year
        FilterField.RATING -> Res.string.rating_critic
        FilterField.COMMUNITY_RATING -> Res.string.rating_community
        FilterField.USER_RATING -> Res.string.rating_own
        FilterField.TIME_TO_BEAT -> Res.string.filter_field_time_to_beat
        FilterField.PLAYED -> Res.string.filter_field_played
        FilterField.LAST_PLAYED -> Res.string.filter_field_last_played
        FilterField.PLAYTIME -> Res.string.filter_field_playtime
        FilterField.ADDED -> Res.string.filter_field_added
        FilterField.FAVORITE -> Res.string.filter_field_favorite
        FilterField.HIDDEN -> Res.string.filter_field_hidden
        FilterField.REVIEW -> Res.string.filter_field_review
    },
)

/** Display text for a condition value (host and group ids become names). */
@Composable
private fun conditionValueLabel(
    entries: List<LibraryEntry>,
    hostNames: Map<String, String>,
    vm: LauncherViewModel,
): (FilterField, String) -> String {
    val localLabel = stringResource(Res.string.filter_local)
    val emulatorLabel = stringResource(Res.string.filter_emulation)
    val collections by vm.collections.collectAsState()
    val groupNames = collections.associate { (c, _) -> c.id to c.name }
    val bucketLabels = FilterField.entries.flatMap { f -> f.buckets.map { (f to it) to bucketLabel(f, it) } }.toMap()
    return { field, value ->
        when (field) {
            FilterField.SOURCE -> if (value == FilterRules.SOURCE_LOCAL) localLabel else if (value == FilterRules.SOURCE_EMULATOR) emulatorLabel else hostNames[value]
                ?: entries.firstNotNullOfOrNull { e -> e.remoteTargets.firstOrNull { t -> t.hostUuid == value }?.hostName } ?: value
            FilterField.GROUP -> groupNames[value] ?: value
            FilterField.PLATFORM -> io.github.thecodingdad.gamelodge.data.library.PlatformResolver.displayName(value)
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
    Modal(visible = condition != null, onDismiss = onDismiss, title = condition?.let { fieldLabel(it.field) }, size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD, closeButton = false) {
        val c = condition ?: return@Modal
        val field = c.field
        Column(
            Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
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
                    stringResource(Res.string.filter_mode), listOf(false, true), c.negate,
                    label = { stringResource(if (it) Res.string.filter_mode_exclude else Res.string.filter_mode_include) },
                    onSelect = { onChange(c.copy(negate = it)) },
                    subtitle = stringResource(if (c.negate) Res.string.filter_mode_exclude_sub else Res.string.filter_mode_include_sub),
                    initialFocus = true,
                )
                if (field.multiValue || field.type == FieldType.TEXT) {
                    SwitchRow(
                        stringResource(Res.string.filter_match_all), c.matchAll, { onChange(c.copy(matchAll = it)) },
                        subtitle = stringResource(Res.string.filter_match_all_sub),
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
                    Text(stringResource(Res.string.filter_name_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    val title = stringResource(Res.string.filter_name_add)
                    LauncherButton(title, icon = Icons.Filled.Add, onClick = {
                        keyboard.open(title, "") { text -> text.trim().takeIf { it.isNotEmpty() }?.let { onChange(c.copy(values = c.values + it)) } }
                    })
                } else {
                    Text(stringResource(Res.string.filter_values_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    if (options.isEmpty()) {
                        Text(stringResource(Res.string.filter_values_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (options + c.values.filter { it !in options }).forEach { v ->
                        Chip(labelFor(field, v), selected = v in c.values, onClick = { onChange(c.copy(values = c.values.toggle(v))) })
                    }
                }
            }
        }
        // Below the scroll area: always in view.
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
            LauncherButton(stringResource(Res.string.action_done), onClick = onDismiss, primary = true)
            io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton(stringResource(Res.string.filter_condition_delete), onConfirmed = onDelete, icon = Icons.Filled.Delete)
        }
    }
}

/** Picks the field of a new condition, sorted into sections like Playnite's filter panel. */
@Composable
private fun FieldPickerDialog(visible: Boolean, onDismiss: () -> Unit, onPick: (FilterField) -> Unit) {
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(Res.string.filter_condition_add), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Column(Modifier.weight(1f, fill = false).popupHeightIn(600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldSection.entries.forEachIndexed { s, section ->
                SectionHeader(
                    stringResource(
                        when (section) {
                            FieldSection.GAME -> Res.string.filter_section_game
                            FieldSection.METADATA -> Res.string.filter_section_metadata
                            FieldSection.ACTIVITY -> Res.string.filter_section_activity
                            FieldSection.STATUS -> Res.string.filter_section_status
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
    if (!c.field.boolean && c.negate) stringResource(Res.string.filter_field_negated, fieldLabel(c.field)) else fieldLabel(c.field)

/** "PC, Nintendo Switch" (or "… und …" when all must match), "Ja" / "Nie" for switches. */
@Composable
private fun conditionValues(c: FilterCondition, labelFor: (FilterField, String) -> String): String {
    if (c.field.boolean) return booleanLabel(c.field, !c.negate)
    if (c.values.isEmpty()) return stringResource(Res.string.filter_condition_no_values)
    val separator = if (c.matchAll) stringResource(Res.string.filter_values_and) else ", "
    val texts = c.values.map { labelFor(c.field, it) }
    return if (c.field.type == FieldType.TEXT) texts.joinToString(separator) { "„$it“" } else texts.joinToString(separator)
}

@Composable
private fun booleanLabel(field: FilterField, yes: Boolean): String = stringResource(
    when {
        yes -> Res.string.yes
        field == FilterField.PLAYED -> Res.string.filter_never
        else -> Res.string.no
    },
)

@Composable
private fun bucketLabel(field: FilterField, key: String): String = when (field) {
    FilterField.PLAYTIME -> stringResource(
        when (key) {
            "none" -> Res.string.filter_playtime_none
            "lt1" -> Res.string.filter_playtime_lt1
            "1to10" -> Res.string.filter_playtime_1to10
            "10to100" -> Res.string.filter_playtime_10to100
            "100to500" -> Res.string.filter_playtime_100to500
            else -> Res.string.filter_playtime_gt500
        },
    )
    FilterField.TIME_TO_BEAT -> stringResource(
        when (key) {
            "lt5" -> Res.string.filter_ttb_lt5
            "5to10" -> Res.string.filter_ttb_5to10
            "10to20" -> Res.string.filter_ttb_10to20
            "20to40" -> Res.string.filter_ttb_20to40
            "40to80" -> Res.string.filter_ttb_40to80
            "gt80" -> Res.string.filter_ttb_gt80
            else -> Res.string.filter_ttb_none
        },
    )
    FilterField.USER_RATING -> when (key) {
        "none" -> stringResource(Res.string.filter_rating_none)
        "5" -> stringResource(Res.string.rating_stars, 5)
        else -> stringResource(Res.string.filter_stars_from, key.toInt())
    }
    FilterField.RATING, FilterField.COMMUNITY_RATING -> when (key) {
        "lt60" -> stringResource(Res.string.filter_rating_lt60)
        "none" -> stringResource(Res.string.filter_rating_none)
        else -> stringResource(Res.string.filter_rating_from, key)
    }
    else -> stringResource(
        when (key) {
            "today" -> Res.string.filter_period_today
            "yesterday" -> Res.string.filter_period_yesterday
            "week" -> Res.string.filter_period_week
            "month" -> Res.string.filter_period_month
            "year" -> Res.string.filter_period_year
            "older" -> Res.string.filter_period_older
            else -> Res.string.filter_never
        },
    )
}

/**
 * Filter rows with a drag handle on the right. Touch: drag the handle. Controller: A on the
 * handle picks the filter up, D-pad up/down moves it, A or B drops it.
 */
@Composable
fun <T> ReorderableList(
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
    val heights = remember { HashMap<String, Float>() }
    val rootTops = remember { HashMap<String, Float>() }
    val scrollArea = io.github.thecodingdad.gamelodge.ui.components.LocalScrollArea.current
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    val feedback = io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback.current
    // Moved by controller: the row stays in view (the page scrolls along).
    val inView = remember { HashMap<String, androidx.compose.foundation.relocation.BringIntoViewRequester>() }
    var keyMoved by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(keyMoved) {
        if (keyMoved == 0) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        picked?.let { inView[it]?.bringIntoView() }
    }

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
    // Swaps the dragged row with a neighbour once its centre passes the neighbour's centre.
    // Positions come from the current order and the row heights, not from the last layout: after
    // a swap the layout lags a frame behind, and stale positions made the row swap back and forth
    // (it jumped away from the finger, most of all while the page scrolled along).
    fun reposition(id: String) {
        val i = order.indexOf(id)
        val h = heights[id] ?: return
        val center = dragOffset + h / 2
        order.getOrNull(i + 1)?.let { next ->
            val nH = heights[next] ?: return@let
            // Next row's centre relative to the dragged row's resting top.
            if (center > h + gap + nH / 2 && move(id, 1)) dragOffset -= nH + gap
        }
        order.getOrNull(i - 1)?.let { prev ->
            val pH = heights[prev] ?: return@let
            if (center < -gap - pH / 2 && move(id, -1)) dragOffset += pH + gap
        }
    }
    // Touch drag near the top/bottom edge of the page scrolls along; the row stays under the finger.
    androidx.compose.runtime.LaunchedEffect(dragging, scrollArea) {
        val id = dragging ?: return@LaunchedEffect
        val area = scrollArea ?: return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val top = (rootTops[id] ?: continue) + dragOffset
            val bottom = top + (heights[id] ?: continue)
            val speed = when {
                top < area.top + edge -> -((area.top + edge - top) / edge).coerceAtMost(1f) * 24f
                bottom > area.bottom - edge -> ((bottom - (area.bottom - edge)) / edge).coerceAtMost(1f) * 24f
                else -> 0f
            }
            if (speed == 0f) continue
            val consumed = area.state.scrollBy(speed)
            dragOffset += consumed
            reposition(id)
        }
    }
    // B drops a picked-up row instead of leaving the page.
    io.github.thecodingdad.gamelodge.ui.input.PadAction(
        io.github.thecodingdad.gamelodge.ui.input.PadButton.B, enabled = picked != null,
    ) { feedback?.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.TOGGLE); picked = null; commit() }

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
                        .onGloballyPositioned {
                            heights[id] = it.size.height.toFloat()
                            rootTops[id] = it.positionInRoot().y
                        }
                        .graphicsLayer { translationY = if (dragging == id) dragOffset else 0f }
                        .bringIntoViewRequester(inView.getOrPut(id) { androidx.compose.foundation.relocation.BringIntoViewRequester() }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { row(index, f) }
                    io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
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
                                if (event.type == KeyEventType.KeyDown && delta != 0 && move(id, delta)) {
                                    // Sound and vibration for every step (focus moves may be silent to the touch).
                                    feedback?.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.TOGGLE)
                                    keyMoved++
                                }
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
                                    reposition(id)
                                }
                            },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.DragHandle, stringResource(Res.string.filter_drag),
                                tint = if (active) LauncherTheme.colors.onAccent else LauncherTheme.colors.onSurfaceMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}
