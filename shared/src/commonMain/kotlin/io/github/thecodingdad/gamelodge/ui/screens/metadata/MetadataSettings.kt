package io.github.thecodingdad.gamelodge.ui.screens.metadata

import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

@Composable
fun MetadataSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    reviewCount: Int,
    openReview: () -> Unit,
) {
    val container = io.github.thecodingdad.gamelodge.LocalContainer.current
    val metadata = container.metadata
    val scope = rememberCoroutineScope()
    val progress by metadata.progress.collectAsState()
    val error by metadata.lastError.collectAsState()
    SettingsPage(stringResource(Res.string.settings_metadata)) {
        Text(stringResource(Res.string.metadata_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SwitchRow(stringResource(Res.string.metadata_auto), settings.autoMetadata, { v -> update { it.copy(autoMetadata = v) } }, initialFocus = true)

        if (reviewCount > 0) {
            NavRow(stringResource(Res.string.metadata_review_list), openReview, stringResource(Res.string.metadata_review_count, reviewCount), Icons.Filled.Checklist)
        }
        progress?.let {
            Text(stringResource(Res.string.metadata_progress, it.done, it.total), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
            LinearProgressIndicator(progress = { it.done.toFloat() / it.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent)
        }
        error?.let { Text(stringResource(Res.string.metadata_auth_error, it), color = LauncherTheme.colors.danger) }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(Res.string.metadata_fetch_now), icon = Icons.Filled.CloudDownload, onClick = metadata::refreshPending)
            HoldToConfirmButton(stringResource(Res.string.metadata_refetch_all), onConfirmed = { scope.launch { metadata.resetAutomatic() } }, icon = Icons.Filled.Refresh)
        }

        SourcesSection(settings.metadataSources)

        val stored by androidx.compose.runtime.produceState(0L, progress) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles.sizeBytes() }
        }
        Text(
            stringResource(Res.string.metadata_artwork_storage, io.github.thecodingdad.gamelodge.util.formatFileSize(stored)),
            style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
        )
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_game_sources), SettingsTarget.Page(SettingsPageId.GAME_SOURCES)),
            SeeAlsoLink(stringResource(Res.string.settings_data), SettingsTarget.Page(SettingsPageId.DATA)),
        )
    }
}
