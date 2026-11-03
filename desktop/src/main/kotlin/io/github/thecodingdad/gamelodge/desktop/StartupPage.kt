package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import org.jetbrains.compose.resources.stringResource

/** Settings → Start & tray. */
@Composable
fun StartupPage(startup: Startup) {
    val settings by startup.settings.collectAsState()
    SettingsPage(stringResource(Res.string.settings_startup)) {
        SectionHeader(stringResource(Res.string.startup_window))
        SwitchRow(
            stringResource(Res.string.startup_fullscreen), settings.startFullscreen, { on -> startup.update { it.copy(startFullscreen = on) } },
            subtitle = stringResource(Res.string.startup_fullscreen_sub), initialFocus = true,
        )
        SectionHeader(stringResource(Res.string.startup_start))
        SwitchRow(
            stringResource(Res.string.startup_with_windows), settings.startWithWindows, { on -> startup.update { it.copy(startWithWindows = on) } },
            subtitle = stringResource(Res.string.startup_with_windows_sub),
        )
        SwitchRow(
            stringResource(Res.string.startup_tray), settings.closeToTray, { on -> startup.update { it.copy(closeToTray = on) } },
            subtitle = stringResource(Res.string.startup_tray_sub),
        )
        SeeAlso(SeeAlsoLink(stringResource(Res.string.settings_streaming_host), SettingsTarget.Page(SettingsPageId.STREAMING_HOST)))
    }
}
