package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.BuildConfig
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.dpadScroll
import io.github.thecodingdad.moonlightlauncher.ui.input.RegisterStickScroll
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val context = LocalContext.current
    var showing by remember { mutableStateOf<LicenseText?>(null) }
    SettingsPage(stringResource(R.string.settings_about)) {
        InfoRow(stringResource(R.string.launcher_name), BuildConfig.VERSION_NAME)
        NavRow(
            stringResource(R.string.about_license), { showing = LicenseText("GNU General Public License v3.0", "gpl-3.0") },
            icon = Icons.Filled.Gavel, trailing = "GPLv3",
        )
        NavRow(
            stringResource(R.string.about_source), { openUrl(context, SOURCE_URL) },
            subtitle = SOURCE_URL.removePrefix("https://"), icon = Icons.AutoMirrored.Filled.OpenInNew,
        )
        NavRow(
            stringResource(R.string.about_open_source), openLicenses,
            subtitle = stringResource(R.string.about_open_source_sub), icon = Icons.Filled.Description,
        )

        SectionHeader(stringResource(R.string.about_data_sources))
        NavRow("SteamGridDB", { openUrl(context, "https://www.steamgriddb.com") }, stringResource(R.string.about_source_sgdb), Icons.AutoMirrored.Filled.OpenInNew)
        NavRow("IGDB.com", { openUrl(context, "https://www.igdb.com") }, stringResource(R.string.about_source_igdb), Icons.AutoMirrored.Filled.OpenInNew)
        NavRow("ES-DE", { openUrl(context, "https://es-de.org") }, stringResource(R.string.about_source_esde), Icons.AutoMirrored.Filled.OpenInNew)
    }
    LicenseTextModal(showing) { showing = null }
}

/** Every bundled open source component; A shows its license text. */
@Composable
fun OpenSourceLicenses() {
    val context = LocalContext.current
    val entries = remember {
        runCatching {
            Json.decodeFromString<List<LicenseEntry>>(context.assets.open("licenses/licenses.json").use { it.readBytes().decodeToString() })
        }.getOrDefault(emptyList())
    }
    var showing by remember { mutableStateOf<LicenseText?>(null) }
    SettingsPage(stringResource(R.string.about_open_source)) {
        Text(stringResource(R.string.about_open_source_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        entries.forEach { e ->
            NavRow(e.name, { showing = LicenseText(e.name, e.text) }, subtitle = "© ${e.copyright}", trailing = e.license)
        }
    }
    LicenseTextModal(showing) { showing = null }
}

/** Full license text; D-pad and right stick scroll it. */
@Composable
private fun LicenseTextModal(license: LicenseText?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    Modal(visible = license != null, onDismiss = onDismiss, title = license?.title, maxWidth = 900.dp, initialFocus = focus) {
        val l = license ?: return@Modal
        val text = remember(l.file) {
            runCatching { context.assets.open("licenses/${l.file}.txt").use { it.readBytes().decodeToString() } }.getOrDefault("")
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
                .verticalScroll(scroll),
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
