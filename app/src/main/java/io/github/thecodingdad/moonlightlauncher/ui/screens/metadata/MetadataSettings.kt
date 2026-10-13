package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

@Composable
fun MetadataSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    reviewCount: Int,
    openReview: () -> Unit,
) {
    val container = LocalContext.current.container
    val secrets = container.secrets
    val metadata = container.metadata
    val scope = rememberCoroutineScope()
    val progress by metadata.progress.collectAsState()
    val error by metadata.lastError.collectAsState()
    // Re-read secrets after edits; they are not observable.
    var revision by remember { mutableStateOf(0) }
    val sgdbKey = remember(revision) { secrets.get(SecretStore.Keys.SGDB_API_KEY).orEmpty() }
    val igdbId = remember(revision) { secrets.get(SecretStore.Keys.IGDB_CLIENT_ID).orEmpty() }
    val igdbSecret = remember(revision) { secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET).orEmpty() }
    fun save(key: String, value: String) {
        secrets.put(key, value.trim())
        if (key != SecretStore.Keys.SGDB_API_KEY) secrets.put(SecretStore.Keys.IGDB_ACCESS_TOKEN, null)
        revision++
        metadata.refreshPending()
    }

    SettingsPage(stringResource(R.string.settings_metadata)) {
        Text(stringResource(R.string.metadata_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SwitchRow(stringResource(R.string.metadata_auto), settings.autoMetadata, { v -> update { it.copy(autoMetadata = v) } }, initialFocus = true)

        if (reviewCount > 0) {
            NavRow(stringResource(R.string.metadata_review_list), openReview, stringResource(R.string.metadata_review_count, reviewCount), Icons.Filled.Checklist)
        }
        progress?.let {
            Text(stringResource(R.string.metadata_progress, it.done, it.total), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
            LinearProgressIndicator(progress = { it.done.toFloat() / it.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent)
        }
        error?.let { Text(stringResource(R.string.metadata_auth_error, it), color = LauncherTheme.colors.danger) }
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(R.string.metadata_fetch_now), icon = Icons.Filled.CloudDownload, onClick = metadata::refreshPending)
            HoldToConfirmButton(stringResource(R.string.metadata_refetch_all), onConfirmed = { scope.launch { metadata.resetAutomatic() } }, icon = Icons.Filled.Refresh)
        }

        SectionHeader("SteamGridDB")
        Text(stringResource(R.string.metadata_sgdb_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        LauncherTextField(stringResource(R.string.metadata_api_key), sgdbKey, { save(SecretStore.Keys.SGDB_API_KEY, it) }, kind = TextKind.PASSWORD)
        if (sgdbKey.isNotBlank()) ConnectionStatus("SteamGridDB", revision) { metadata.testSgdb() }

        SectionHeader("IGDB (Twitch)")
        Text(stringResource(R.string.metadata_igdb_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        LauncherTextField(stringResource(R.string.metadata_client_id), igdbId, { save(SecretStore.Keys.IGDB_CLIENT_ID, it) })
        LauncherTextField(stringResource(R.string.metadata_client_secret), igdbSecret, { save(SecretStore.Keys.IGDB_CLIENT_SECRET, it) }, kind = TextKind.PASSWORD)
        if (igdbId.isNotBlank() && igdbSecret.isNotBlank()) ConnectionStatus("IGDB", revision) { metadata.testIgdb() }
        // Granted outside the app: re-check on every resume.
        var usageTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { usageTick++; onPauseOrDispose { } }
        val context = LocalContext.current
        // Play time of local apps and games from Android (usage access is granted in system settings).
        val usage = container.usage
        val usageGranted = remember(usageTick) { usage.granted }
        SectionHeader(stringResource(R.string.usage_section))
        NavRow(
            stringResource(R.string.usage_access),
            {
                val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            },
            subtitle = stringResource(if (usageGranted) R.string.usage_access_on else R.string.usage_access_off),
            icon = Icons.Filled.BarChart,
        )
    }
}

/** Whether a provider accepts the key(s), checked again after every change ([revision]). */
@Composable
private fun ConnectionStatus(provider: String, revision: Int, test: suspend () -> Boolean?) {
    var status by remember { mutableStateOf<Boolean?>(null) }
    var checked by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(revision) { checked = false; status = test(); checked = true }
    Text(
        when {
            !checked -> stringResource(R.string.metadata_status_checking, provider)
            status == true -> stringResource(R.string.metadata_status_ok, provider)
            status == false -> stringResource(R.string.metadata_status_rejected, provider)
            else -> stringResource(R.string.metadata_status_unknown, provider)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            !checked -> LauncherTheme.colors.onSurfaceMuted
            status == true -> LauncherTheme.colors.success
            status == false -> LauncherTheme.colors.danger
            else -> LauncherTheme.colors.onSurfaceMuted
        },
    )
}
