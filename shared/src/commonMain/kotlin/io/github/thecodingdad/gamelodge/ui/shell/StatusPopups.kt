package io.github.thecodingdad.gamelodge.ui.shell

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo
import io.github.thecodingdad.gamelodge.ui.screens.settings.vibrateController
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import java.util.UUID
import io.github.thecodingdad.gamelodge.resources.*

/** Status bar profile chip: pick the stream profile used for the next streams. */
@Composable
fun ProfilePickerPopup(
    visible: Boolean,
    profiles: List<StreamProfile>,
    active: StreamProfile,
    onSelect: (StreamProfile) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val profileStore = io.github.thecodingdad.gamelodge.LocalContainer.current.profiles
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(Res.string.profile_picker_title), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Text(stringResource(Res.string.profile_picker_sub), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 14.dp))
        Column(Modifier.weight(1f, fill = false).popupHeightIn(420.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            profiles.forEach { p ->
                val isActive = p.uuid == active.uuid
                LauncherButton(
                    "${p.name}  ·  ${profileStore.summary(p.uuid)}",
                    onClick = { onSelect(p); onDismiss() },
                    icon = if (isActive) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                    primary = isActive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LauncherButton(stringResource(Res.string.profile_picker_edit), onClick = { onDismiss(); onEdit() }, icon = Icons.Filled.Edit, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Status bar controller icon: connected controllers with battery, rumble test, settings link. */
@Composable
fun ControllerPopup(
    visible: Boolean,
    controllers: List<ControllerInfo>,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Selecting a controller opens its settings (on top of this popup).
    var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ControllerInfo?>(null) }
    io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerDetailDialog(selected, onDismiss = { selected = null })
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(Res.string.settings_controllers), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        val colors = LauncherTheme.colors
        Text(stringResource(Res.string.controller_popup_sub), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 14.dp))
        Column(Modifier.weight(1f, fill = false).popupHeightIn(420.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (controllers.isEmpty()) Text(stringResource(Res.string.controllers_none), color = colors.onSurface)
            controllers.forEach { c ->
                val details = listOfNotNull(
                    stringResource(if (c.external) Res.string.controller_external else Res.string.controller_builtin),
                    c.batteryPercent?.let { stringResource(Res.string.controller_battery, it) },
                ).joinToString("  ·  ")
                LauncherButton(
                    "${c.displayName}  ·  $details",
                    onClick = { selected = c },
                    icon = c.displayIcon,
                    modifier = Modifier.fillMaxWidth(),
                    iconTint = c.colorArgb?.let { androidx.compose.ui.graphics.Color(it) },
                )
            }
            LauncherButton(stringResource(Res.string.controller_popup_settings), onClick = { onDismiss(); onOpenSettings() }, icon = Icons.Filled.Settings, modifier = Modifier.fillMaxWidth())
        }
    }
}
