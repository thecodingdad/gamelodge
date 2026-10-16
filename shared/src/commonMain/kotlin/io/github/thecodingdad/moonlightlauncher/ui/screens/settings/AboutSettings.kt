package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import io.github.thecodingdad.moonlightlauncher.ui.components.mouseDragScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.dpadScroll
import io.github.thecodingdad.moonlightlauncher.ui.input.RegisterStickScroll
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.thecodingdad.moonlightlauncher.resources.*

private const val SOURCE_URL = "https://github.com/thecodingdad/moonlight-launcher"

/** One bundled component and its license (assets/licenses/licenses.json, maintained by hand). */
@Serializable
private data class LicenseEntry(
    val name: String,
    val copyright: String,
    val license: String,
    /** License text file in assets/licenses (without ".txt"). */
    val text: String,
    val url: String? = null,
)

/** A license text to show: title and asset file. */
private data class LicenseText(val title: String, val file: String)

@Composable
fun AboutSettings(openLicenses: () -> Unit) {
    val version = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.platform.versionName
    var showing by remember { mutableStateOf<LicenseText?>(null) }
    SettingsPage(stringResource(Res.string.settings_about)) {
        InfoRow(stringResource(Res.string.launcher_name), version)
        NavRow(
            stringResource(Res.string.about_license), { showing = LicenseText("GNU General Public License v3.0", "gpl-3.0") },
            icon = Icons.Filled.Gavel, trailing = "GPLv3",
        )
        NavRow(
            stringResource(Res.string.about_source), { openUrl(SOURCE_URL) },
            subtitle = SOURCE_URL.removePrefix("https://"), icon = Icons.AutoMirrored.Filled.OpenInNew,
        )
        NavRow(
            stringResource(Res.string.about_open_source), openLicenses,
            subtitle = stringResource(Res.string.about_open_source_sub), icon = Icons.Filled.Description,
        )

        SectionHeader(stringResource(Res.string.about_data_sources))
        NavRow("SteamGridDB", { openUrl("https://www.steamgriddb.com") }, stringResource(Res.string.about_source_sgdb), Icons.AutoMirrored.Filled.OpenInNew)
        NavRow("IGDB.com", { openUrl("https://www.igdb.com") }, stringResource(Res.string.about_source_igdb), Icons.AutoMirrored.Filled.OpenInNew)
        NavRow("ES-DE", { openUrl("https://es-de.org") }, stringResource(Res.string.about_source_esde), Icons.AutoMirrored.Filled.OpenInNew)
    }
    LicenseTextModal(showing) { showing = null }
}

/** Every bundled open source component; A shows its license text. */
@Composable
fun OpenSourceLicenses() {
    val entries by androidx.compose.runtime.produceState(emptyList<LicenseEntry>()) {
        value = runCatching {
            Json.decodeFromString<List<LicenseEntry>>(Res.readBytes("files/licenses/licenses.json").decodeToString())
        }.getOrDefault(emptyList())
    }
    var showing by remember { mutableStateOf<LicenseText?>(null) }
    SettingsPage(stringResource(Res.string.about_open_source)) {
        Text(stringResource(Res.string.about_open_source_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        entries.forEach { e ->
            NavRow(e.name, { showing = LicenseText(e.name, e.text) }, subtitle = "© ${e.copyright}", trailing = e.license)
        }
    }
    LicenseTextModal(showing) { showing = null }
}

/** Full license text; D-pad and right stick scroll it. */
@Composable
private fun LicenseTextModal(license: LicenseText?, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Modal(visible = license != null, onDismiss = onDismiss, title = license?.title, maxWidth = 900.dp, initialFocus = focus) {
        val l = license ?: return@Modal
        val text by androidx.compose.runtime.produceState("", l.file) {
            value = runCatching { Res.readBytes("files/licenses/${l.file}.txt").decodeToString() }.getOrDefault("")
        }
        val scroll = rememberScrollState()
        RegisterStickScroll(scroll)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = LauncherTheme.colors.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .focusRequester(focus)
                .dpadScroll(scroll)
                .focusable()
                .mouseDragScroll(scroll)
                .verticalScroll(scroll),
        )
    }
}

private fun openUrl(url: String) = io.github.thecodingdad.moonlightlauncher.ui.PlatformUi.current.openUrl(url)
