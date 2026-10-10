package io.github.thecodingdad.moonlightlauncher.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.preferences.PreferenceConfiguration
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.stream.ProfileStore
import io.github.thecodingdad.moonlightlauncher.stream.StreamOption
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfiles
import io.github.thecodingdad.moonlightlauncher.stream.StreamSettingsSchema
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SliderRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import java.util.UUID

@Composable
fun ProfilesList(
    profiles: List<StreamProfile>,
    active: StreamProfile,
    openProfile: (UUID?) -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalKeyboardController.current
    var focusedIndex by remember { mutableIntStateOf(0) }
    val newTitle = stringResource(R.string.profile_new_name)

    PadAction(PadButton.X) { profiles.getOrNull(focusedIndex)?.let { StreamProfiles.setActive(it.uuid) } }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(R.string.profile_edit)),
            ButtonHint(PadButton.X, stringResource(R.string.profile_set_active)),
            ButtonHint(PadButton.B, stringResource(R.string.hint_back)),
        ),
    )

    SettingsPage(stringResource(R.string.settings_profiles)) {
        Text(stringResource(R.string.profiles_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        profiles.forEachIndexed { index, profile ->
            val isActive = profile.uuid == active.uuid
            Box(Modifier.onFocusEvent { if (it.hasFocus) focusedIndex = index }) {
                NavRow(
                    title = profile.name,
                    onClick = { openProfile(profile.uuid) },
                    subtitle = if (profile.uuid == null) stringResource(R.string.profile_base_sub) else null,
                    icon = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                    initialFocus = index == 0,
                    trailing = if (isActive) stringResource(R.string.profile_active) else null,
                )
            }
        }
        LauncherButton(
            stringResource(R.string.profile_new), icon = Icons.Filled.Add,
            onClick = { keyboard.open(newTitle, "") { name -> if (name.isNotBlank()) openProfile(ProfileStore.create(context, name.trim())) } },
        )
    }
}

@Composable
fun ProfileEditor(uuid: UUID?, name: String, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val store = remember(uuid) { ProfileStore(context, uuid) }
    val schema = remember { StreamSettingsSchema.load(context) }
    val keyboard = LocalKeyboardController.current
    // Bumped after every write so rows re-read the store.
    var revision by remember { mutableIntStateOf(0) }
    val renameTitle = stringResource(R.string.profile_rename)

    val byKey = remember(schema) { schema.flatMap { it.options }.associateBy { it.key } }
    val essentials = StreamSettingsSchema.essentialKeys.mapNotNull { byKey[it] }

    SettingsPage(name) {
        if (uuid != null) {
            io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
                LauncherButton(stringResource(R.string.profile_set_active), icon = Icons.Filled.CheckCircle, onClick = { StreamProfiles.setActive(uuid) })
                LauncherButton(stringResource(R.string.profile_rename), icon = Icons.Filled.Edit, onClick = {
                    keyboard.open(renameTitle, name) { n -> if (n.isNotBlank()) ProfileStore.rename(uuid, n.trim()) }
                })
                HoldToConfirmButton(stringResource(R.string.profile_delete), onConfirmed = { ProfileStore.delete(uuid); onDeleted() }, icon = Icons.Filled.Delete)
            }
        }
        SectionHeader(stringResource(R.string.profile_essentials))
        essentials.forEachIndexed { i, option -> OptionRow(option, store, revision, i == 0 && uuid == null) { revision++ } }
        schema.forEach { category ->
            val rest = category.options.filter { it.key !in StreamSettingsSchema.essentialKeys }
            if (rest.isEmpty()) return@forEach
            SectionHeader(category.title)
            rest.forEach { option -> OptionRow(option, store, revision, false) { revision++ } }
        }
    }
}

@Composable
fun OptionRow(option: StreamOption, store: ProfileStore, revision: Int, initialFocus: Boolean, changed: () -> Unit) {
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
            val dynamicDefault = if (option.key == "seekbar_bitrate_kbps") {
                PreferenceConfiguration.getDefaultBitrate(
                    store.getString("list_resolution", "1280x720") ?: "1280x720",
                    store.getString("list_fps", "60") ?: "60",
                )
            } else null
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
