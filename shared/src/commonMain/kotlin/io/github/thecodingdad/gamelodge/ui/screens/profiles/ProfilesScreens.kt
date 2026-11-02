package io.github.thecodingdad.gamelodge.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TouchApp
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

@Composable
fun ProfilesList(
    editing: ProfileEditing,
    profiles: List<StreamProfile>,
    active: StreamProfile,
    openProfile: (UUID?) -> Unit,
) {
    val keyboard = LocalKeyboardController.current
    var focusedIndex by remember { mutableIntStateOf(0) }
    val newTitle = stringResource(Res.string.profile_new_name)
    val profileStore = io.github.thecodingdad.gamelodge.LocalContainer.current.profiles

    PadAction(PadButton.X) { profiles.getOrNull(focusedIndex)?.let { profileStore.setActive(it.uuid) } }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(Res.string.profile_edit)),
            ButtonHint(PadButton.X, stringResource(Res.string.profile_set_active)),
            ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
        ),
    )

    SettingsPage(stringResource(Res.string.settings_profiles)) {
        Text(stringResource(Res.string.profiles_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        profiles.forEachIndexed { index, profile ->
            val isActive = profile.uuid == active.uuid
            Box(Modifier.onFocusEvent { if (it.hasFocus) focusedIndex = index }) {
                NavRow(
                    title = profile.name,
                    onClick = { openProfile(profile.uuid) },
                    subtitle = if (profile.uuid == null) stringResource(Res.string.profile_base_sub) else null,
                    icon = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                    initialFocus = index == 0,
                    trailing = if (isActive) stringResource(Res.string.profile_active) else null,
                )
            }
        }
        LauncherButton(
            stringResource(Res.string.profile_new), icon = Icons.Filled.Add,
            onClick = { keyboard.open(newTitle, "") { name -> if (name.isNotBlank()) openProfile(editing.create(name.trim())) } },
        )
        // Touch and keyboard options of this device: the same in every profile.
        val deviceInput = editing.rememberDeviceInputOptions()
        if (deviceInput.isNotEmpty()) {
            var open by remember { androidx.compose.runtime.mutableStateOf(false) }
            SectionHeader(stringResource(Res.string.stream_device_input_section))
            NavRow(
                stringResource(Res.string.stream_device_input), { open = true },
                subtitle = stringResource(Res.string.stream_device_input_sub), icon = Icons.Filled.TouchApp,
            )
            val base = remember { editing.values(null) }
            var revision by remember { mutableIntStateOf(0) }
            io.github.thecodingdad.gamelodge.ui.components.Modal(
                visible = open, onDismiss = { open = false }, title = stringResource(Res.string.stream_device_input), maxWidth = 760.dp,
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    deviceInput.forEachIndexed { i, option -> OptionRow(option, base, revision, i == 0, editing) { revision++ } }
                }
            }
        }
        SectionHeader(stringResource(Res.string.conn_warnings_section))
        val container = io.github.thecodingdad.gamelodge.LocalContainer.current
        val settings by container.settings.settings.collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow(
            stringResource(Res.string.conn_warnings), io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.entries, settings.connectionWarnings,
            label = {
                stringResource(
                    when (it) {
                        io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.OFF -> Res.string.conn_warnings_off
                        io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.PERF_OVERLAY -> Res.string.conn_warnings_perf
                        io.github.thecodingdad.gamelodge.data.settings.ConnectionWarnings.ALWAYS -> Res.string.conn_warnings_always
                    },
                )
            },
            onSelect = { mode -> scope.launch { container.settings.update { s -> s.copy(connectionWarnings = mode) } } },
            subtitle = stringResource(Res.string.conn_warnings_sub),
        )
        io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso(io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink(stringResource(Res.string.settings_hosts), io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Page(io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId.HOSTS)))
    }
}

@Composable
fun ProfileEditor(editing: ProfileEditing, uuid: UUID?, name: String, onDeleted: () -> Unit) {
    val store = remember(uuid) { editing.values(uuid) }
    val schema = editing.rememberSchema()
    val keyboard = LocalKeyboardController.current
    // Bumped after every write so rows re-read the store.
    var revision by remember { mutableIntStateOf(0) }
    val renameTitle = stringResource(Res.string.profile_rename)
    val profileStore = io.github.thecodingdad.gamelodge.LocalContainer.current.profiles

    val byKey = remember(schema) { schema.flatMap { it.options }.associateBy { it.key } }
    val essentials = editing.essentialKeys.mapNotNull { byKey[it] }

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
