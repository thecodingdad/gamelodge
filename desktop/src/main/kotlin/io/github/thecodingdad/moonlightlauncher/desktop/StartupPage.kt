package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.thecodingdad.moonlightlauncher.resources.*
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import org.jetbrains.compose.resources.stringResource

/** Settings → Start & tray. */
@Composable
fun StartupPage(startup: Startup) {
    val settings by startup.settings.collectAsState()
    SettingsPage(stringResource(Res.string.settings_startup)) {
        SwitchRow(
            stringResource(Res.string.startup_with_windows), settings.startWithWindows, { on -> startup.update { it.copy(startWithWindows = on) } },
            subtitle = stringResource(Res.string.startup_with_windows_sub), initialFocus = true,
        )
        SwitchRow(
            stringResource(Res.string.startup_tray), settings.closeToTray, { on -> startup.update { it.copy(closeToTray = on) } },
            subtitle = stringResource(Res.string.startup_tray_sub),
        )
    }
}
