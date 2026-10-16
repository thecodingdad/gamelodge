package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.thecodingdad.moonlightlauncher.resources.*
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PowerSettingsNew
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import org.jetbrains.compose.resources.stringResource

/** Settings → Start & tray. */
@Composable
fun StartupPage(startup: Startup, window: WindowControls?) {
    val settings by startup.settings.collectAsState()
    SettingsPage(stringResource(Res.string.settings_startup)) {
        SectionHeader(stringResource(Res.string.startup_window))
        SwitchRow(
            stringResource(Res.string.startup_fullscreen), settings.startFullscreen, { on -> startup.update { it.copy(startFullscreen = on) } },
            subtitle = stringResource(Res.string.startup_fullscreen_sub), initialFocus = true,
        )
        if (window != null) {
            val fullscreen by window.fullscreen.collectAsState()
            ButtonRow {
                LauncherButton(
                    stringResource(if (fullscreen) Res.string.qt_to_window else Res.string.qt_to_fullscreen), onClick = window.toggleFullscreen,
                    icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                )
                LauncherButton(stringResource(Res.string.qt_quit), onClick = window.exit, icon = Icons.Filled.PowerSettingsNew)
            }
        }
        SectionHeader(stringResource(Res.string.startup_start))
        SwitchRow(
            stringResource(Res.string.startup_with_windows), settings.startWithWindows, { on -> startup.update { it.copy(startWithWindows = on) } },
            subtitle = stringResource(Res.string.startup_with_windows_sub),
        )
        SwitchRow(
            stringResource(Res.string.startup_tray), settings.closeToTray, { on -> startup.update { it.copy(closeToTray = on) } },
            subtitle = stringResource(Res.string.startup_tray_sub),
        )
    }
}
