package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.system.LauncherMode
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

@Composable
fun LauncherModeSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val mode = remember { LauncherMode(context) }
    // Role state changes outside the app; re-read on every resume.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val isDefault = remember(tick) { mode.isDefaultHome }

    // The switch shows whether we are the default home app. Turning it on asks Android; if the
    // user picks another launcher there, it springs back to off.
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val held = mode.isDefaultHome
        if (!held) mode.setAvailable(false)
        update { it.copy(launcherMode = held) }
        tick++
    }

    // Default app changed outside (system settings, another launcher): follow it. The home
    // alias only stays enabled while we hold the role.
    LaunchedEffect(isDefault) {
        if (settings.launcherMode != isDefault) update { it.copy(launcherMode = isDefault) }
        if (mode.isAvailable != isDefault) mode.setAvailable(isDefault)
    }

    SettingsPage(stringResource(Res.string.settings_launcher)) {
        StartTabRow()
        Text(stringResource(Res.string.launcher_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SwitchRow(
            stringResource(Res.string.launcher_enable), isDefault,
            onChange = { enabled ->
                if (enabled) {
                    mode.setAvailable(true)
                    val request = mode.requestDefaultHomeIntent()
                    if (request != null) roleRequest.launch(request) else context.startActivity(mode.homeSettingsIntent())
                } else {
                    // Android can't give the role back from here: choose another launcher.
                    update { it.copy(launcherMode = false) }
                    mode.setAvailable(false)
                    context.startActivity(mode.homeSettingsIntent())
                }
            },
            subtitle = stringResource(Res.string.launcher_enable_sub),
            icon = Icons.Filled.Home,
            initialFocus = true,
        )
        NavRow(stringResource(Res.string.launcher_system_settings), { context.startActivity(mode.homeSettingsIntent()) }, icon = Icons.Filled.Settings)
        SeeAlso(SeeAlsoLink(stringResource(Res.string.settings_home_screen), SettingsTarget.Page(SettingsPageId.HOME_SCREEN)))
    }
}
