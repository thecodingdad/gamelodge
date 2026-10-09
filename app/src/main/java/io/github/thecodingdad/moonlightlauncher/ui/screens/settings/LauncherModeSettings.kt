package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.system.LauncherMode
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

@Composable
fun LauncherModeSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val mode = remember { LauncherMode(context) }
    // Role state changes outside the app; re-read on every resume.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val isDefault = remember(tick) { mode.isDefaultHome }

    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    // Keep the alias in sync with the stored setting (e.g. after restoring a backup), but never
    // disable it while we are the default home app.
    LaunchedEffect(settings.launcherMode, isDefault) {
        when {
            isDefault && !settings.launcherMode -> update { it.copy(launcherMode = true) }
            mode.isAvailable != settings.launcherMode -> mode.setAvailable(settings.launcherMode)
        }
    }

    SettingsPage(stringResource(R.string.settings_launcher)) {
        Text(stringResource(R.string.launcher_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SwitchRow(
            stringResource(R.string.launcher_enable), settings.launcherMode,
            onChange = { enabled ->
                update { it.copy(launcherMode = enabled) }
                mode.setAvailable(enabled)
                if (enabled) mode.requestDefaultHomeIntent()?.let(roleRequest::launch)
                else if (isDefault) context.startActivity(mode.homeSettingsIntent())
            },
            subtitle = stringResource(R.string.launcher_enable_sub),
            icon = Icons.Filled.Home,
            initialFocus = true,
        )
        InfoRow(stringResource(R.string.launcher_is_default), stringResource(if (isDefault) R.string.yes else R.string.no))
        if (settings.launcherMode && !isDefault) {
            NavRow(stringResource(R.string.launcher_make_default), { mode.requestDefaultHomeIntent()?.let(roleRequest::launch) }, icon = Icons.Filled.Home)
        }
        NavRow(stringResource(R.string.launcher_system_settings), { context.startActivity(mode.homeSettingsIntent()) }, icon = Icons.Filled.Settings)
    }
}
