package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
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
    val metadata = container.metadata
    val scope = rememberCoroutineScope()
    val progress by metadata.progress.collectAsState()
    val error by metadata.lastError.collectAsState()
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

        SourcesSection(settings.metadataSources)

        val context = LocalContext.current
        val stored by androidx.compose.runtime.produceState(0L, progress) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkFiles.sizeBytes() }
        }
        Text(
            stringResource(R.string.metadata_artwork_storage, android.text.format.Formatter.formatShortFileSize(context, stored)),
            style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
        )
    }
}
