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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.thecodingdad.moonlightlauncher.metadata.ArtworkOption
import io.github.thecodingdad.moonlightlauncher.metadata.ArtworkSource
import io.github.thecodingdad.moonlightlauncher.metadata.SgdbAsset
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        var query by remember(target.id) { mutableStateOf(target.metadataQuery ?: io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer.searchTerm(target.title)) }
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
        // Local copy: the list content is evaluated lazily and may run after a new search reset the state.
        val found = results
        when {
            applying || found == null -> CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            found.isEmpty() -> Text(stringResource(R.string.metadata_no_results), color = LauncherTheme.colors.onSurfaceMuted)
            else -> LazyColumn(state = io.github.thecodingdad.moonlightlauncher.ui.input.rememberStickLazyListState(), modifier = Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found, key = { c -> "${c.source}:${c.id}" }) { c ->
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

/**
 * Choose cover, background, logo or icon (L1/R1 switches kind): the current image on top with
 * "back to automatic", below everything the sources offer (companion, IGDB, SteamGridDB).
 */
@Composable
fun ArtworkPickerDialog(item: LibraryItemEntity?, onDismiss: () -> Unit) {
    val metadata = LocalContext.current.container.metadata
    val scope = rememberCoroutineScope()
    Modal(visible = item != null, onDismiss = onDismiss, title = stringResource(R.string.artwork_title), maxWidth = 1000.dp) {
        val target = item ?: return@Modal
        // Animated cover / background have their own tabs (while animated artwork is wanted).
        val animatedOn = LocalContext.current.container.settings.settings.collectAsState(null).value?.animatedArtwork ?: true
        val kinds = ArtworkKind.entries.filter { animatedOn || !it.animated }
        var kindIndex by remember(target.id) { mutableStateOf(0) }
        val kind = kinds[kindIndex.coerceIn(kinds.indices)]
        var options by remember(target.id, kind) { mutableStateOf<List<ArtworkOption>?>(null) }
        LaunchedEffect(target.id, kind) { options = runCatching { metadata.artworkOptions(target, kind) }.getOrDefault(emptyList()) }
        PadAction(PadButton.L1) { kindIndex = (kindIndex - 1).mod(kinds.size) }
        PadAction(PadButton.R1) { kindIndex = (kindIndex + 1).mod(kinds.size) }
        val wide = kind.base == ArtworkKind.HERO
        val square = kind == ArtworkKind.LOGO || kind == ArtworkKind.ICON
        val ratio = when (kind.base) {
            ArtworkKind.HERO -> 1920f / 620f
            ArtworkKind.LOGO -> 5f / 2f
            ArtworkKind.ICON -> 1f
            else -> 2f / 3f
        }

        // Tabs and hints stay; current image and choices scroll together.
        Column(Modifier.weight(1f, fill = false)) {
            io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
                kinds.forEachIndexed { index, k ->
                    io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(kindLabel(k), selected = index == kindIndex, onClick = { kindIndex = index })
                }
            }
            Spacer(Modifier.height(14.dp))
            // The current image of this tab, picked by hand or automatic.
            val current = when (kind) {
                ArtworkKind.COVER -> target.coverUrl
                ArtworkKind.ANIMATED_COVER -> target.animatedCoverUrl
                ArtworkKind.HERO -> target.heroUrl
                ArtworkKind.ANIMATED_HERO -> target.animatedHeroUrl
                ArtworkKind.LOGO -> target.logoUrl
                ArtworkKind.ICON -> target.iconUrl
            }
            val manual = kind.field in target.manual
            val list = options
            val full = androidx.compose.foundation.lazy.grid.GridItemSpan(Int.MAX_VALUE)
            // One scroll area: the current image first, then the choices (tabs and hints stay).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 260.dp else if (square) 150.dp else 120.dp),
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "current", span = { full }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        current?.let { url ->
                            ArtworkImage(
                                io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkFiles.model(url), target.title,
                                Modifier.height(if (wide) 90.dp else 120.dp).aspectRatio(ratio).clip(RoundedCornerShape(8.dp)),
                                contentScale = if (square) ContentScale.Fit else ContentScale.Crop, showTitleOnPlaceholder = false,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(if (manual) R.string.artwork_manual else R.string.artwork_auto),
                                style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
                            )
                            if (manual) {
                                io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                                    stringResource(R.string.artwork_reset_auto), icon = androidx.compose.material.icons.Icons.Filled.Restore,
                                    onClick = { scope.launch { metadata.resetArtwork(target.id, kind); onDismiss() } },
                                )
                            }
                        }
                    }
                }
                when {
                    !metadata.isConfigured && list.isNullOrEmpty() -> item(key = "status", span = { full }) {
                        Text(stringResource(R.string.metadata_not_configured), color = LauncherTheme.colors.onSurfaceMuted)
                    }
                    list == null -> item(key = "status", span = { full }) { CircularProgressIndicator(color = LauncherTheme.colors.accent) }
                    list.isEmpty() -> item(key = "status", span = { full }) {
                        Text(stringResource(R.string.metadata_no_results), color = LauncherTheme.colors.onSurfaceMuted)
                    }
                    else -> items(list, key = { o -> o.url }) { option ->
                        FocusSurface(
                            onClick = { scope.launch { metadata.setArtwork(target.id, kind, option); onDismiss() } },
                            modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                            color = LauncherTheme.colors.surfaceRaised,
                        ) {
                            ArtworkImage(
                                option.thumb, target.title, Modifier.padding(if (square) 12.dp else 0.dp),
                                contentScale = if (square) ContentScale.Fit else ContentScale.Crop,
                                showTitleOnPlaceholder = false,
                            )
                            Text(
                                listOfNotNull(
                                    sourceLabel(option.source),
                                    stringResource(R.string.artwork_animated).takeIf { option.animated },
                                    option.bytes?.let { android.text.format.Formatter.formatShortFileSize(LocalContext.current, it) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                                    .background(androidx.compose.ui.graphics.Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ButtonHintBar(listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_select)), ButtonHint(PadButton.L1, stringResource(R.string.artwork_switch_kind), secondButton = PadButton.R1), ButtonHint(PadButton.B, stringResource(R.string.action_close), onClick = onDismiss)))
        }
    }
}

@Composable
private fun kindLabel(kind: ArtworkKind) = stringResource(
    when (kind) {
        ArtworkKind.COVER -> R.string.artwork_cover
        ArtworkKind.ANIMATED_COVER -> R.string.artwork_animated_cover
        ArtworkKind.HERO -> R.string.artwork_hero
        ArtworkKind.ANIMATED_HERO -> R.string.artwork_animated_hero
        ArtworkKind.LOGO -> R.string.artwork_logo
        ArtworkKind.ICON -> R.string.artwork_icon
    },
)

@Composable
private fun sourceLabel(source: ArtworkSource) = when (source) {
    ArtworkSource.COMPANION -> stringResource(R.string.metadata_source_companion)
    ArtworkSource.IGDB -> "IGDB"
    ArtworkSource.STEAMGRIDDB -> "SteamGridDB"
}

/** Detail page: confirm a match that is waiting for review. */
@Composable
fun ReviewConfirmAction(entry: io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry, onConfirm: () -> Unit) {
    if (entry.item.metadataState != io.github.thecodingdad.moonlightlauncher.data.db.MetadataState.REVIEW) return
    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
        stringResource(R.string.metadata_confirm), onClick = onConfirm,
        icon = androidx.compose.material.icons.Icons.Filled.Check,
    )
}

/** Start tab tile of an app: SteamGridDB landscape images, an own image, or the default tile. */
@Composable
fun AppTileDialog(entry: io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val metadata = context.container.metadata
    val scope = rememberCoroutineScope()
    val target = entry
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Copy into the app's storage: the picked document may not stay readable.
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = java.io.File(context.filesDir, "tiles").apply { mkdirs() }
                val out = java.io.File(dir, "${target.item.id}-${System.currentTimeMillis()}.img")
                context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }?.let { out }
            }
            if (file != null) metadata.setTile(target.item.id, file.absolutePath)
            onDismiss()
        }
    }
    Modal(visible = target != null, onDismiss = onDismiss, title = stringResource(R.string.app_tile_title), maxWidth = 1000.dp) {
        val e = target ?: return@Modal
        var options by remember(e.item.id) { mutableStateOf<List<SgdbAsset>?>(null) }
        LaunchedEffect(e.item.id) { options = metadata.tileOptions(e.item) }
        Text(stringResource(R.string.app_tile_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        Spacer(Modifier.height(12.dp))
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
            io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                stringResource(R.string.app_tile_own), onClick = { pickImage.launch(arrayOf("image/*")) },
                icon = androidx.compose.material.icons.Icons.Filled.Image,
            )
            if (e.item.tileUrl != null) {
                io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                    stringResource(R.string.app_tile_reset), onClick = { scope.launch { metadata.setTile(e.item.id, null); onDismiss() } },
                    icon = androidx.compose.material.icons.Icons.Filled.Restore,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val list = options
        when {
            list == null -> CircularProgressIndicator(color = LauncherTheme.colors.accent)
            list.isEmpty() -> Text(stringResource(R.string.app_tile_none), color = LauncherTheme.colors.onSurfaceMuted)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                modifier = Modifier.heightIn(max = 380.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { a -> a.id }) { asset ->
                    FocusSurface(
                        onClick = { scope.launch { metadata.setTile(e.item.id, asset.url); onDismiss() } },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        color = LauncherTheme.colors.surfaceRaised,
                    ) {
                        ArtworkImage(asset.thumb ?: asset.url, e.item.title, Modifier, showTitleOnPlaceholder = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ButtonHintBar(listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_select)), ButtonHint(PadButton.B, stringResource(R.string.action_close), onClick = onDismiss)))
    }
}
