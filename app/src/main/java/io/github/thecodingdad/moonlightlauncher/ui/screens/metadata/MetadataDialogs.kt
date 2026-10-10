package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.metadata.ArtworkKind
import io.github.thecodingdad.moonlightlauncher.metadata.MetadataCandidate
import io.github.thecodingdad.moonlightlauncher.metadata.SgdbAsset
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHintBar
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

/** Search IGDB/SteamGridDB and pick the right game; the choice is locked. */
@Composable
fun FixMetadataDialog(item: LibraryItemEntity?, onDismiss: () -> Unit) {
    val metadata = LocalContext.current.container.metadata
    val scope = rememberCoroutineScope()
    Modal(visible = item != null, onDismiss = onDismiss, title = stringResource(R.string.metadata_fix_title), maxWidth = 860.dp) {
        val target = item ?: return@Modal
        var query by remember(target.id) { mutableStateOf(target.metadataQuery ?: target.title) }
        var results by remember(target.id) { mutableStateOf<List<MetadataCandidate>?>(null) }
        var applying by remember(target.id) { mutableStateOf(false) }
        LaunchedEffect(query, target.id) {
            results = null
            results = runCatching { metadata.search(query) }.getOrDefault(emptyList())
        }

        if (!metadata.isConfigured) {
            Text(stringResource(R.string.metadata_not_configured), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
            return@Modal
        }
        LauncherTextField(stringResource(R.string.hint_search), query, onValueChange = { q -> query = q })
        Spacer(Modifier.height(14.dp))
        when {
            applying || results == null -> CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            results!!.isEmpty() -> Text(stringResource(R.string.metadata_no_results), color = LauncherTheme.colors.onSurfaceMuted)
            else -> LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results!!, key = { c -> "${c.source}:${c.id}" }) { c ->
                    FocusSurface(
                        onClick = {
                            applying = true
                            scope.launch {
                                metadata.applyCandidate(target.id, c)
                                applying = false
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = LauncherTheme.colors.surfaceRaised,
                        focusScale = 1.02f,
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(c.coverUrl, c.name, Modifier.width(48.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)))
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
                                Text(
                                    listOfNotNull(c.year?.toString(), c.source.name).joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
                                )
                            }
                            Text("${(c.score * 100).toInt()} %", style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurfaceMuted)
                        }
                    }
                }
            }
        }
    }
}

/** Choose cover, background or logo from SteamGridDB alternatives (L1/R1 switches kind). */
@Composable
fun ArtworkPickerDialog(item: LibraryItemEntity?, onDismiss: () -> Unit) {
    val metadata = LocalContext.current.container.metadata
    val scope = rememberCoroutineScope()
    Modal(visible = item != null, onDismiss = onDismiss, title = stringResource(R.string.artwork_title), maxWidth = 1000.dp) {
        val target = item ?: return@Modal
        val kinds = listOf(ArtworkKind.COVER, ArtworkKind.HERO, ArtworkKind.LOGO)
        var kindIndex by remember(target.id) { mutableStateOf(0) }
        val kind = kinds[kindIndex]
        var options by remember(target.id, kind) { mutableStateOf<List<SgdbAsset>?>(null) }
        LaunchedEffect(target.id, kind) { options = runCatching { metadata.artworkOptions(target, kind) }.getOrDefault(emptyList()) }
        PadAction(PadButton.L1) { kindIndex = (kindIndex - 1).mod(kinds.size) }
        PadAction(PadButton.R1) { kindIndex = (kindIndex + 1).mod(kinds.size) }

        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
            kinds.forEachIndexed { index, k ->
                io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(
                    stringResource(
                        when (k) {
                            ArtworkKind.COVER -> R.string.artwork_cover
                            ArtworkKind.HERO -> R.string.artwork_hero
                            ArtworkKind.LOGO -> R.string.artwork_logo
                            ArtworkKind.ICON -> R.string.artwork_logo
                        },
                    ),
                    selected = index == kindIndex,
                    onClick = { kindIndex = index },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val list = options
        when {
            !metadata.sgdb.isConfigured -> Text(stringResource(R.string.metadata_sgdb_required), color = LauncherTheme.colors.onSurfaceMuted)
            list == null -> CircularProgressIndicator(color = LauncherTheme.colors.accent)
            list.isEmpty() -> Text(stringResource(R.string.metadata_no_results), color = LauncherTheme.colors.onSurfaceMuted)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (kind == ArtworkKind.COVER) 120.dp else 260.dp),
                modifier = Modifier.heightIn(max = 380.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { a -> a.id }) { asset ->
                    FocusSurface(
                        onClick = { scope.launch { metadata.setArtwork(target.id, kind, asset); onDismiss() } },
                        modifier = Modifier.fillMaxWidth().aspectRatio(if (kind == ArtworkKind.COVER) 2f / 3f else 1920f / 620f),
                        color = LauncherTheme.colors.surfaceRaised,
                    ) {
                        ArtworkImage(
                            asset.thumb ?: asset.url, target.title, Modifier.padding(if (kind == ArtworkKind.LOGO) 12.dp else 0.dp),
                            contentScale = if (kind == ArtworkKind.LOGO) ContentScale.Fit else ContentScale.Crop,
                            showTitleOnPlaceholder = false,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ButtonHintBar(listOf(ButtonHint(PadButton.L1, stringResource(R.string.artwork_switch_kind)), ButtonHint(PadButton.B, stringResource(R.string.action_close), onClick = onDismiss)))
    }
}

/** Second button row on the detail page: fix match, change artwork, confirm a review match. */
@Composable
fun MetadataActions(
    entry: io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry,
    onFix: () -> Unit,
    onArtwork: () -> Unit,
    onConfirm: () -> Unit,
) {
    io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
        if (entry.item.metadataState == io.github.thecodingdad.moonlightlauncher.data.db.MetadataState.REVIEW) {
            io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                stringResource(R.string.metadata_confirm), onClick = onConfirm,
                icon = androidx.compose.material.icons.Icons.Filled.Check,
            )
        }
        io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
            stringResource(R.string.metadata_fix_title), onClick = onFix,
            icon = androidx.compose.material.icons.Icons.Filled.Search,
        )
        io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
            stringResource(R.string.artwork_title), onClick = onArtwork,
            icon = androidx.compose.material.icons.Icons.Filled.Image,
        )
    }
}
