package io.github.thecodingdad.gamelodge.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.stream.ProfileEditing
import io.github.thecodingdad.gamelodge.stream.ProfileValues
import io.github.thecodingdad.gamelodge.stream.StreamOption
import io.github.thecodingdad.gamelodge.stream.StreamOptionCategory
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import io.github.thecodingdad.gamelodge.stream.StreamProfiles
import io.github.thecodingdad.gamelodge.ui.components.ButtonHint
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SliderRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.shell.ScreenHints
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import java.util.UUID
import io.github.thecodingdad.gamelodge.resources.*

/** The stream profiles page (only reached by old links; the profiles are a group on the streaming hosts page). */
@Composable
fun ProfilesList(
    editing: ProfileEditing,
    profiles: List<StreamProfile>,
    active: StreamProfile,
    openProfile: (UUID?) -> Unit,
) {
    SettingsPage(stringResource(Res.string.settings_profiles)) {
        ProfilesGroup(editing, profiles, active, openProfile, header = false)
    }
}

/**
 * The stream profiles as a group (Settings → Streaming hosts): the active one ticked, A edits,
 * X makes the focused one active, and a new profile.
 */
@Composable
fun ProfilesGroup(
    editing: ProfileEditing,
    profiles: List<StreamProfile>,
    active: StreamProfile,
    openProfile: (UUID?) -> Unit,
    header: Boolean = true,
) {
    val keyboard = LocalKeyboardController.current
    // The focused profile row; null while the focus is elsewhere on the page (X does nothing then).
    var focusedIndex by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val newTitle = stringResource(Res.string.profile_new_name)
    val profileStore = io.github.thecodingdad.gamelodge.LocalContainer.current.profiles

    if (focusedIndex != null) {
        PadAction(PadButton.X) { focusedIndex?.let { profiles.getOrNull(it) }?.let { profileStore.setActive(it.uuid) } }
        ScreenHints(
            listOf(
                ButtonHint(PadButton.A, stringResource(Res.string.profile_edit)),
                ButtonHint(PadButton.X, stringResource(Res.string.profile_set_active)),
                ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
            ),
        )
    }

    if (header) SectionHeader(stringResource(Res.string.settings_profiles))
    Text(stringResource(Res.string.profiles_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
    profiles.forEachIndexed { index, profile ->
        val isActive = profile.uuid == active.uuid
        Box(Modifier.onFocusEvent { if (it.hasFocus) focusedIndex = index else if (focusedIndex == index) focusedIndex = null }) {
            NavRow(
                title = profile.name,
                onClick = { openProfile(profile.uuid) },
                subtitle = if (profile.uuid == null) stringResource(Res.string.profile_base_sub) else null,
                icon = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                initialFocus = !header && index == 0,
                trailing = if (isActive) stringResource(Res.string.profile_active) else null,
            )
        }
    }
    LauncherButton(
        stringResource(Res.string.profile_new), icon = Icons.Filled.Add,
        onClick = { keyboard.open(newTitle, "") { name -> if (name.isNotBlank()) openProfile(editing.create(name.trim())) } },
    )
}

@Composable
fun ProfileEditor(editing: ProfileEditing, uuid: UUID?, name: String, onDeleted: () -> Unit) {
    val store = remember(uuid) { editing.values(uuid) }
    val schema = editing.rememberSchema()
    val keyboard = LocalKeyboardController.current
    val renameTitle = stringResource(Res.string.profile_rename)
    val profileStore = io.github.thecodingdad.gamelodge.LocalContainer.current.profiles

    SettingsPage(name) {
        if (uuid != null) {
            io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 12.dp) {
                LauncherButton(stringResource(Res.string.profile_set_active), icon = Icons.Filled.CheckCircle, onClick = { profileStore.setActive(uuid) })
                LauncherButton(stringResource(Res.string.profile_rename), icon = Icons.Filled.Edit, onClick = {
                    keyboard.open(renameTitle, name) { n -> if (n.isNotBlank()) editing.rename(uuid, n.trim()) }
                })
                HoldToConfirmButton(stringResource(Res.string.profile_delete), onConfirmed = { editing.delete(uuid); onDeleted() }, icon = Icons.Filled.Delete)
            }
        }
        // PC and Xbox parts of the profile as tabs (Xbox only once a console is added).
        val container = io.github.thecodingdad.gamelodge.LocalContainer.current
        val settings by container.settings.settings.collectAsState(null)
        val withXbox = settings?.xboxConsoles?.isNotEmpty() == true
        var xboxTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        if (withXbox) {
            val tabFocus = remember { List(2) { androidx.compose.ui.focus.FocusRequester() } }
            var refocus by remember { androidx.compose.runtime.mutableStateOf(false) }
            fun switchTo(xbox: Boolean) {
                xboxTab = xbox
                refocus = true
            }
            // LT/RT: LB/RB stay with the main tabs.
            io.github.thecodingdad.gamelodge.ui.input.PadAction(PadButton.L2) { switchTo(!xboxTab) }
            io.github.thecodingdad.gamelodge.ui.input.PadAction(PadButton.R2) { switchTo(!xboxTab) }
            ScreenHints(
                listOf(
                    ButtonHint(PadButton.A, stringResource(Res.string.hint_select)),
                    ButtonHint(PadButton.L2, stringResource(Res.string.profile_tabs_hint), secondButton = PadButton.R2),
                    ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
                ),
            )
            androidx.compose.runtime.LaunchedEffect(xboxTab, refocus) {
                if (!refocus) return@LaunchedEffect
                refocus = false
                runCatching { tabFocus[if (xboxTab) 1 else 0].requestFocus() }
            }
            Row(Modifier.padding(vertical = 4.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                io.github.thecodingdad.gamelodge.ui.screens.library.Chip(
                    stringResource(Res.string.profile_tab_pc), selected = !xboxTab, onClick = { switchTo(false) },
                    icon = { io.github.thecodingdad.gamelodge.ui.components.HostIcon(false, androidx.compose.material3.LocalContentColor.current, Modifier.padding(end = 8.dp), size = 20.dp) },
                    modifier = Modifier.focusRequester(tabFocus[0]),
                )
                io.github.thecodingdad.gamelodge.ui.screens.library.Chip(
                    stringResource(Res.string.profile_tab_xbox), selected = xboxTab, onClick = { switchTo(true) },
                    icon = { io.github.thecodingdad.gamelodge.ui.components.HostIcon(true, androidx.compose.material3.LocalContentColor.current, Modifier.padding(end = 8.dp), size = 20.dp) },
                    modifier = Modifier.focusRequester(tabFocus[1]),
                )
            }
        }
        if (withXbox && xboxTab) {
            io.github.thecodingdad.gamelodge.ui.screens.settings.XboxStreamSection(store, if (uuid != null) editing.values(null) else null)
        } else PcOptions(editing, store, schema, uuid)
    }
}

/** The PC part of a profile: the important options, then the rest in popups by topic. */
@Composable
private fun PcOptions(editing: ProfileEditing, store: ProfileValues, schema: List<StreamOptionCategory>, uuid: UUID?) {
    // Bumped after every write so rows re-read the store.
    var revision by remember { mutableIntStateOf(0) }
    val byKey = remember(schema) { schema.flatMap { it.options }.associateBy { it.key } }
    val essentials = editing.essentialKeys.mapNotNull { byKey[it] }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(Res.string.profile_essentials))
        essentials.forEachIndexed { i, option -> OptionRow(option, store, revision, i == 0 && uuid == null, editing) { revision++ } }
        // Everything else is rarely changed: one popup per topic (the list in one piece was too long).
        val groups = schema.mapNotNull { category ->
            category.options.filter { it.key !in editing.essentialKeys }.takeIf { it.isNotEmpty() }?.let { category.title to it }
        }
        if (groups.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.profile_advanced))
            var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
            groups.forEach { (title, options) ->
                NavRow(title, { open = title }, subtitle = options.take(3).joinToString(", ") { it.title } + if (options.size > 3) " …" else "")
            }
            val shown = groups.firstOrNull { it.first == open }
            io.github.thecodingdad.gamelodge.ui.components.Modal(
                visible = shown != null, onDismiss = { open = null }, title = shown?.first,
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown?.second?.forEachIndexed { i, option -> OptionRow(option, store, revision, i == 0, editing) { revision++ } }
                }
            }
        }
        // GameLodge's own: a notice over the stream when the connection gets bad.
        SectionHeader(stringResource(Res.string.conn_warnings_section))
        val base = remember(uuid) { if (uuid != null) editing.values(null) else null }
        var warnings by remember(store) { androidx.compose.runtime.mutableStateOf(io.github.thecodingdad.gamelodge.stream.StreamQuality.modeOf(store, base)) }
        ChoiceRow(
            stringResource(Res.string.conn_warnings), io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.entries, warnings,
            label = {
                stringResource(
                    when (it) {
                        io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.OFF -> Res.string.conn_warnings_off
                        io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.PERF_OVERLAY -> Res.string.conn_warnings_perf
                        io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.ALWAYS -> Res.string.conn_warnings_always
                    },
                )
            },
            onSelect = { mode -> store.set(io.github.thecodingdad.gamelodge.stream.StreamQuality.PROFILE_KEY, mode.name); warnings = mode },
            subtitle = stringResource(Res.string.conn_warnings_sub),
        )
    }
}

@Composable
fun OptionRow(option: StreamOption, store: ProfileValues, revision: Int, initialFocus: Boolean, editing: ProfileEditing, changed: () -> Unit) {
    // `revision` is read so the row recomposes after writes.
    if (revision < 0) return
    when (option) {
        is StreamOption.Toggle -> SwitchRow(
            option.title, store.getBoolean(option.key, option.default),
            { store.set(option.key, it); changed() }, subtitle = option.summary, initialFocus = initialFocus,
        )
        is StreamOption.Choice -> {
            val current = store.getString(option.key, option.default) ?: option.values.first()
            val selected = option.values.indexOf(current).takeIf { it >= 0 } ?: 0
            ChoiceRow(
                option.title, option.values.indices.toList(), selected,
                label = { option.labels[it] },
                onSelect = { i -> store.set(option.key, option.values[i]); changed() },
                subtitle = option.summary, initialFocus = initialFocus,
            )
        }
        is StreamOption.Slider -> {
            // Bitrate has no static default: Artemis derives it from this profile's resolution and FPS.
            val dynamicDefault = editing.dynamicDefault(option.key, store)
            val value = store.getInt(option.key, option.default ?: dynamicDefault ?: option.min)
            SliderRow(
                option.title, value.coerceIn(option.min, option.max), option.min..option.max, option.step.coerceAtLeast(1),
                onChange = { store.set(option.key, it); changed() },
                valueLabel = { v ->
                    val shown = if (option.divisor > 1) "%.1f".format(v.toFloat() / option.divisor) else v.toString()
                    listOfNotNull(shown, option.suffix).joinToString(" ")
                },
            )
        }
    }
}
