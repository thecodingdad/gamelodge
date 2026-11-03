package io.github.thecodingdad.gamelodge.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Where the SteamGridDB key is made. */
const val SGDB_KEY_PAGE = "https://www.steamgriddb.com/profile/preferences/api"
/** Where the IGDB (Twitch) app with client id and secret is made. */
const val IGDB_KEY_PAGE = "https://dev.twitch.tv/console/apps"

/**
 * Step by step how to get the SteamGridDB key ([sgdb]) or the IGDB client id and secret, with the
 * page as QR code (easier on a phone) and a button that opens it here.
 */
@Composable
fun KeyGuideDialog(sgdb: Boolean, onDismiss: () -> Unit) {
    val url = if (sgdb) SGDB_KEY_PAGE else IGDB_KEY_PAGE
    val steps: List<StringResource> = if (sgdb) {
        listOf(Res.string.guide_sgdb_1, Res.string.guide_sgdb_2, Res.string.guide_sgdb_3, Res.string.guide_sgdb_4)
    } else {
        listOf(Res.string.guide_igdb_1, Res.string.guide_igdb_2, Res.string.guide_igdb_3, Res.string.guide_igdb_4, Res.string.guide_igdb_5)
    }
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(if (sgdb) Res.string.guide_title_sgdb else Res.string.guide_title_igdb), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Column(Modifier.weight(1f, fill = false).popupHeightIn(520.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.accentText)
                    Text(stringResource(step), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCard(url, 140.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.guide_qr_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    Text(url, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                }
            }
        }
        LauncherButton(
            stringResource(Res.string.guide_open_page), { io.github.thecodingdad.gamelodge.ui.PlatformUi.current.openUrl(url) },
            Modifier.fillMaxWidth(), Icons.AutoMirrored.Filled.OpenInNew, primary = true,
        )
        LauncherButton(stringResource(Res.string.action_close), onDismiss, Modifier.fillMaxWidth())
    }
}
