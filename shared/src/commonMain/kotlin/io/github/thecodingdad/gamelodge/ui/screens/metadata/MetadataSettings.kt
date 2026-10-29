package io.github.thecodingdad.gamelodge.ui.screens.metadata

import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import io.github.thecodingdad.gamelodge.ui.screens.settings.ActionRow
import io.github.thecodingdad.gamelodge.ui.components.StatusCard
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
    /** Games in the library and how many of them have metadata. */
    games: Int = 0,
    gamesWithMetadata: Int = 0,
) {
    val container = io.github.thecodingdad.gamelodge.LocalContainer.current
    val metadata = container.metadata
    val scope = rememberCoroutineScope()
    val progress by metadata.progress.collectAsState()
    val error by metadata.lastError.collectAsState()
    val stored by androidx.compose.runtime.produceState(0L, progress) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles.sizeBytes() }
    }
    SettingsPage(stringResource(Res.string.settings_metadata)) {
        Text(stringResource(Res.string.metadata_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        val colors = LauncherTheme.colors
        val running = progress
        val storedText = stringResource(Res.string.metadata_artwork_storage, io.github.thecodingdad.gamelodge.util.formatFileSize(stored))
        when {
            running != null -> StatusCard(
                stringResource(Res.string.metadata_progress, running.done, running.total), colors.accent, Icons.Filled.CloudDownload, busy = true,
            )
            error != null -> StatusCard(stringResource(Res.string.metadata_auth_error, error!!), colors.danger, Icons.Filled.ErrorOutline)
            !metadata.isConfigured -> StatusCard(
                stringResource(Res.string.metadata_status_no_source), colors.warning, Icons.Filled.Image,
                details = listOf(stringResource(Res.string.metadata_status_no_source_sub)),
            )
            else -> StatusCard(
                stringResource(Res.string.metadata_status_complete, gamesWithMetadata, games),
                if (reviewCount > 0) colors.warning else colors.success, if (reviewCount > 0) Icons.Filled.Checklist else Icons.Filled.CheckCircle,
                details = listOfNotNull(stringResource(Res.string.metadata_review_count, reviewCount).takeIf { reviewCount > 0 }, storedText),
            ) {
                LauncherButton(stringResource(Res.string.metadata_fetch_now), icon = Icons.Filled.CloudDownload, onClick = {
                    metadata.refreshPending(byHand = true)
                    // Also what changed on the hosts (a cover picked on another device).
                    scope.launch { container.library.checkHostChanges() }
                }, primary = reviewCount == 0)
                if (reviewCount > 0) LauncherButton(stringResource(Res.string.metadata_review_list), icon = Icons.Filled.Checklist, onClick = openReview, primary = true)
            }
        }
        running?.let {
            LinearProgressIndicator(progress = { it.done.toFloat() / it.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
        }
        SwitchRow(stringResource(Res.string.metadata_auto), settings.autoMetadata, { v -> update { it.copy(autoMetadata = v) } }, initialFocus = true)

        SourcesSection(settings.metadataSources)

        SectionHeader(stringResource(Res.string.metadata_maintenance))
        ActionRow(
            stringResource(Res.string.metadata_refetch_all), { scope.launch { metadata.resetAutomatic() } },
            stringResource(Res.string.metadata_refetch_all_sub), Icons.Filled.Refresh, hold = true,
        )
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_game_sources), SettingsTarget.Page(SettingsPageId.GAME_SOURCES)),
            SeeAlsoLink(stringResource(Res.string.settings_data), SettingsTarget.Page(SettingsPageId.DATA)),
        )
    }
}
