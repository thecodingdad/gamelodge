package io.github.thecodingdad.moonlightlauncher.ui.shell

import android.content.Context
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
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.preferences.PreferenceConfiguration
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.stream.ProfileStore
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ControllerInfo
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.vibrateController
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import java.util.UUID

/** "1080p · 60 fps · 20 Mbit": the key values of a stream profile (unset values come from the base). */
fun profileSummary(context: Context, uuid: UUID?): String {
    val base = ProfileStore(context, null)
    val profile = ProfileStore(context, uuid)
    fun value(key: String, default: String) = profile.getString(key, null) ?: base.getString(key, default) ?: default
    val resolution = value("list_resolution", "1280x720")
    val fps = value("list_fps", "60")
    val height = resolution.substringAfter('x', "").takeIf { it.isNotEmpty() }?.let { "${it}p" } ?: resolution
    val defaultBitrate = PreferenceConfiguration.getDefaultBitrate(resolution, fps)
    val kbps = profile.getString("seekbar_bitrate_kbps", null)?.toIntOrNull()
        ?: base.getInt("seekbar_bitrate_kbps", defaultBitrate)
    val mbit = if (kbps % 1000 == 0) "${kbps / 1000}" else "%.1f".format(kbps / 1000f)
    return "$height · $fps fps · $mbit Mbit"
}

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
    val context = LocalContext.current
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(R.string.profile_picker_title), maxWidth = 560.dp) {
        Text(stringResource(R.string.profile_picker_sub), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 14.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            profiles.forEach { p ->
                val isActive = p.uuid == active.uuid
                LauncherButton(
                    "${p.name}  ·  ${profileSummary(context, p.uuid)}",
                    onClick = { onSelect(p); onDismiss() },
                    icon = if (isActive) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                    primary = isActive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LauncherButton(stringResource(R.string.profile_picker_edit), onClick = { onDismiss(); onEdit() }, icon = Icons.Filled.Edit, modifier = Modifier.fillMaxWidth())
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
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(R.string.settings_controllers), maxWidth = 600.dp) {
        val colors = LauncherTheme.colors
        Text(stringResource(R.string.controller_popup_sub), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 14.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (controllers.isEmpty()) Text(stringResource(R.string.controllers_none), color = colors.onSurface)
            controllers.forEach { c ->
                val details = listOfNotNull(
                    stringResource(if (c.external) R.string.controller_external else R.string.controller_builtin),
                    c.batteryPercent?.let { stringResource(R.string.controller_battery, it) },
                ).joinToString("  ·  ")
                LauncherButton(
                    "${c.name}  ·  $details",
                    onClick = { if (c.canVibrate) vibrateController(c.id) },
                    icon = if (c.canVibrate) Icons.Filled.Vibration else Icons.Filled.SportsEsports,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LauncherButton(stringResource(R.string.controller_popup_settings), onClick = { onDismiss(); onOpenSettings() }, icon = Icons.Filled.Settings, modifier = Modifier.fillMaxWidth())
        }
    }
}
