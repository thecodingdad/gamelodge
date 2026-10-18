package io.github.thecodingdad.gamelodge.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.screens.settings.LocalSettingsNavigator
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.settingsTargetAvailable
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

/** Nothing in the library yet: where games come from, with the way there. */
@Composable
fun EmptyLibrary() {
    val navigate = LocalSettingsNavigator.current
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.home_empty_title), style = MaterialTheme.typography.headlineMedium, color = LauncherTheme.colors.onSurface)
        Text(
            stringResource(Res.string.home_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = LauncherTheme.colors.onSurfaceMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        ButtonRow {
            LauncherButton(
                stringResource(Res.string.settings_hosts), onClick = { navigate(SettingsTarget.Page(SettingsPageId.HOSTS), null) },
                icon = Icons.Filled.Computer, primary = true, modifier = Modifier.restoreFocusIf(true),
            )
            val sources = SettingsTarget.Page(SettingsPageId.GAME_SOURCES)
            if (settingsTargetAvailable(sources)) {
                LauncherButton(stringResource(Res.string.settings_game_sources), onClick = { navigate(sources, null) }, icon = Icons.Filled.SportsEsports)
            }
        }
    }
}
