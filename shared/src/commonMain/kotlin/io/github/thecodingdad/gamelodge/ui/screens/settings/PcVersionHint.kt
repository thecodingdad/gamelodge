package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.QrCard
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

/** Where GameLodge for Windows is downloaded. */
const val RELEASES_PAGE = "https://github.com/thecodingdad/gamelodge/releases"

/**
 * Below adding a host: GameLodge for Windows on the streaming PC brings more, with a popup that says
 * what and where to get it. Gone once a GameLodge on a PC is paired.
 */
@Composable
fun PcVersionHint() {
    val servers by io.github.thecodingdad.gamelodge.LocalContainer.current.servers.servers.collectAsState()
    if (servers.any { it.isCompanion }) return
    var open by remember { mutableStateOf(false) }
    Text(stringResource(Res.string.pc_version_hint), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
    LauncherButton(stringResource(Res.string.pc_version_more), { open = true }, icon = Icons.Filled.Computer)
    if (!open) return
    Modal(visible = true, onDismiss = { open = false }, title = stringResource(Res.string.pc_version_title), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Column(Modifier.weight(1f, fill = false).popupHeightIn(520.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.pc_version_text), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
            Text(stringResource(Res.string.pc_version_steps), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCard(RELEASES_PAGE, 140.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.pc_version_qr_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    Text(RELEASES_PAGE, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                }
            }
        }
        LauncherButton(
            stringResource(Res.string.guide_open_page), { io.github.thecodingdad.gamelodge.ui.PlatformUi.current.openUrl(RELEASES_PAGE) },
            Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.OpenInNew, primary = true,
        )
        LauncherButton(stringResource(Res.string.action_close), { open = false }, Modifier.fillMaxWidth())
    }
}
