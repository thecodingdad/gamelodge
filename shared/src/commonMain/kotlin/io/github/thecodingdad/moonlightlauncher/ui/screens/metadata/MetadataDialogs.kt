package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

import io.github.thecodingdad.moonlightlauncher.ui.components.mouseDragScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
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
import io.github.thecodingdad.moonlightlauncher.resources.*

/** Search IGDB/SteamGridDB and pick the right game; the choice is locked. */
@Composable
fun FixMetadataDialog(item: LibraryItemEntity?, onDismiss: () -> Unit) {
    val metadata = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.metadata
    val scope = rememberCoroutineScope()
    Modal(visible = item != null, onDismiss = onDismiss, title = stringResource(Res.string.metadata_fix_title), maxWidth = 860.dp) {
        val target = item ?: return@Modal
        var query by remember(target.id) { mutableStateOf(target.metadataQuery ?: io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer.searchTerm(target.title)) }
        var results by remember(target.id) { mutableStateOf<List<MetadataCandidate>?>(null) }
        var applying by remember(target.id) { mutableStateOf(false) }
        LaunchedEffect(query, target.id) {
            results = null
            results = runCatching { metadata.search(query) }.getOrDefault(emptyList())
        }

        if (!metadata.isConfigured) {
            Text(stringResource(Res.string.metadata_not_configured), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
            return@Modal
        }
        LauncherTextField(stringResource(Res.string.hint_search), query, onValueChange = { q -> query = q })
        Spacer(Modifier.height(14.dp))
        // Local copy: the list content is evaluated lazily and may run after a new search reset the state.
        val found = results
        val foundState = io.github.thecodingdad.moonlightlauncher.ui.input.rememberStickLazyListState()
        when {
            applying || found == null -> CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            found.isEmpty() -> Text(stringResource(Res.string.metadata_no_results), color = LauncherTheme.colors.onSurfaceMuted)
            else -> LazyColumn(state = foundState, modifier = Modifier.heightIn(max = 340.dp).mouseDragScroll(foundState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
fun ArtworkPickerDialog(item: LibraryItemEntity?, targets: List<io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity> = emptyList(), onDismiss: () -> Unit) {
    val metadata = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.metadata
    val scope = rememberCoroutineScope()
    // An own image from the device's storage, copied into the launcher (the document may not stay readable).
    val filesDir = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.platform.filesDir
    var ownKind by remember { mutableStateOf<ArtworkKind?>(null) }
    val pickOwnImage = io.github.thecodingdad.moonlightlauncher.ui.PlatformUi.current.rememberImagePicker { address ->
        val target = item
        val kind = ownKind
        if (address == null || target == null || kind == null) return@rememberImagePicker
        scope.launch {
            copyOwn(filesDir, address, target.id, kind)?.let { metadata.setOwnArtwork(target.id, kind, it.absolutePath) }
            onDismiss()
        }
    }
    // Constant height (the whole screen but a margin): switching tabs doesn't make it jump.
    Modal(visible = item != null, onDismiss = onDismiss, title = stringResource(Res.string.artwork_title), maxWidth = 1000.dp, modifier = Modifier.fillMaxHeight()) {
        val target = item ?: return@Modal
        // Animated cover / background have their own tabs (while animated artwork is wanted).
        val animatedOn = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.settings.settings.collectAsState(null).value?.animatedArtwork ?: true
        val kinds = ArtworkKind.entries.filter { animatedOn || !it.animated }
        var kindIndex by remember(target.id) { mutableStateOf(0) }
        val kind = kinds[kindIndex.coerceIn(kinds.indices)]
        androidx.compose.runtime.SideEffect { ownKind = kind }
        var options by remember(target.id, kind) { mutableStateOf<List<ArtworkOption>?>(null) }
        // The host's box art and a cover next to the ROM come first (they are what the game had before).
        val hostNames = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.hosts.hosts.collectAsState().value
        val local = if (kind != ArtworkKind.COVER) emptyList() else targets.mapNotNull { t ->
            val path = t.boxArtPath ?: return@mapNotNull null
            when (t.type) {
                io.github.thecodingdad.moonlightlauncher.data.db.TargetType.REMOTE -> ArtworkOption(path, thumbOf(path), ArtworkSource.HOST, detail = hostNames[t.hostUuid]?.name)
                io.github.thecodingdad.moonlightlauncher.data.db.TargetType.ROM -> ArtworkOption(path, thumbOf(path), ArtworkSource.ROM)
                else -> null
            }
        }.distinctBy { it.url }
        LaunchedEffect(target.id, kind) { options = local + runCatching { metadata.artworkOptions(target, kind) }.getOrDefault(emptyList()) }
        // LB/RB like the library's filters: focus stays in the list (first image of the new tab)
        // or, while that is empty or loading, on the tab itself; it never gets lost.
        val tabFocus = remember(kinds.size) { List(kinds.size) { androidx.compose.ui.focus.FocusRequester() } }
        val firstChoice = remember { androidx.compose.ui.focus.FocusRequester() }
        var inGrid by remember { mutableStateOf(false) }
        var refocus by remember { mutableStateOf(false) }
        // Where the focus was when switching (the old list is gone a moment later).
        var fromGrid by remember { mutableStateOf(false) }
        fun switchTo(index: Int) {
            fromGrid = inGrid
            kindIndex = index
            refocus = true
        }
        PadAction(PadButton.L1) { switchTo((kindIndex - 1).mod(kinds.size)) }
        PadAction(PadButton.R1) { switchTo((kindIndex + 1).mod(kinds.size)) }
        LaunchedEffect(kindIndex, options, refocus) {
            if (!refocus) return@LaunchedEffect
            val loaded = options
            kotlinx.coroutines.delay(30)
            val toGrid = fromGrid && !loaded.isNullOrEmpty()
            runCatching { if (toGrid) firstChoice.requestFocus() else tabFocus[kindIndex.coerceIn(tabFocus.indices)].requestFocus() }
            // While the list loads the tab holds the focus; it moves on into the list once there.
            if (loaded != null || !fromGrid) refocus = false
        }
        // The right stick scrolls this list (not the popup behind).
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        io.github.thecodingdad.moonlightlauncher.ui.input.RegisterStickScroll(gridState)
        LaunchedEffect(kindIndex) { gridState.scrollToItem(0) }
        val wide = kind.base == ArtworkKind.HERO
        val square = kind == ArtworkKind.LOGO || kind == ArtworkKind.ICON
        val ratio = when (kind.base) {
            ArtworkKind.HERO -> 1920f / 620f
            ArtworkKind.LOGO -> 5f / 2f
            ArtworkKind.ICON -> 1f
            else -> 2f / 3f
        }

        // Tabs and hints stay; current image and choices scroll together.
        Column(Modifier.weight(1f)) {
            io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
                kinds.forEachIndexed { index, k ->
                    io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(
                        kindLabel(k), selected = index == kindIndex, onClick = { switchTo(index) },
                        modifier = Modifier.focusRequester(tabFocus[index]),
                    )
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
            // One scroll area: the current image first, then the choices (tabs and hints stay).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 260.dp else if (square) 150.dp else 120.dp),
                state = gridState,
                // Takes all the room between tabs and hints, which sit at the bottom.
                modifier = Modifier.weight(1f).onFocusChanged { inGrid = it.hasFocus }.mouseDragScroll(gridState),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "current", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    // Text and buttons beside the image; on narrow screens (portrait) below it.
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        val image: @Composable () -> Unit = {
                        current?.let { url ->
                            ArtworkImage(
                                io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkFiles.model(url), target.title,
                                Modifier.height(if (wide) 90.dp else 120.dp).aspectRatio(ratio).clip(RoundedCornerShape(8.dp)),
                                contentScale = if (square) ContentScale.Fit else ContentScale.Crop, showTitleOnPlaceholder = false,
                            )
                        }
                        }
                        val controls: @Composable () -> Unit = {
                            Text(
                                stringResource(
                                    when {
                                        manual && current == null -> if (kind.animated) Res.string.artwork_none_chosen else Res.string.artwork_removed
                                        manual -> Res.string.artwork_manual
                                        else -> Res.string.artwork_auto
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
                            )
                            // Wraps on narrow (portrait) screens.
                            io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
                                io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                                    stringResource(Res.string.artwork_own), icon = androidx.compose.material.icons.Icons.Filled.Image,
                                    onClick = { pickOwnImage() },
                                )
                                // No image of this kind (animated: the still one shows instead).
                                if (current != null) {
                                    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                                        stringResource(Res.string.artwork_none), icon = androidx.compose.material.icons.Icons.Filled.Delete,
                                        onClick = { scope.launch { metadata.clearArtwork(target.id, kind); onDismiss() } },
                                    )
                                }
                                if (manual) {
                                    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                                        stringResource(Res.string.artwork_reset_auto), icon = androidx.compose.material.icons.Icons.Filled.Restore,
                                        onClick = { scope.launch { metadata.resetArtwork(target.id, kind); onDismiss() } },
                                    )
                                }
                            }
                        }
                        if (maxWidth >= 640.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                image()
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                image()
                                controls()
                            }
                        }
                    }
                }
                when {
                    !metadata.isConfigured && list.isNullOrEmpty() -> item(key = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(Res.string.metadata_not_configured), color = LauncherTheme.colors.onSurfaceMuted)
                    }
                    list == null -> item(key = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        // A grid cell spans the full width: the spinner keeps its own size, centred.
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.size(40.dp))
                        }
                    }
                    list.isEmpty() -> item(key = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(Res.string.metadata_no_results), color = LauncherTheme.colors.onSurfaceMuted)
                    }
                    else -> items(list.size, key = { i -> list[i].url }) { i ->
                        val option = list[i]
                        FocusSurface(
                            onClick = {
                                scope.launch {
                                    // A local file (host box art cache, ROM folder) is copied: the original may go away.
                                    if (option.url.startsWith("http")) metadata.setArtwork(target.id, kind, option)
                                    else copyOwn(filesDir, option.url, target.id, kind)
                                        ?.let { metadata.setOwnArtwork(target.id, kind, it.absolutePath) }
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(ratio).then(if (i == 0) Modifier.focusRequester(firstChoice) else Modifier),
                            color = LauncherTheme.colors.surfaceRaised,
                        ) {
                            // Fills the tile whatever size the preview has (small thumbnails too).
                            ArtworkImage(
                                option.thumb, target.title, Modifier.fillMaxSize().padding(if (square) 12.dp else 0.dp),
                                contentScale = if (square) ContentScale.Fit else ContentScale.Crop,
                                showTitleOnPlaceholder = false,
                            )
                            Text(
                                listOfNotNull(
                                    sourceLabel(option),
                                    stringResource(Res.string.artwork_animated).takeIf { option.animated },
                                    option.bytes?.let { io.github.thecodingdad.moonlightlauncher.util.formatFileSize(it) },
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
            ButtonHintBar(listOf(ButtonHint(PadButton.A, stringResource(Res.string.hint_select)), ButtonHint(PadButton.L1, stringResource(Res.string.artwork_switch_kind), secondButton = PadButton.R1), ButtonHint(PadButton.B, stringResource(Res.string.action_close), onClick = onDismiss)), inPopup = true)
        }
    }
}

@Composable
private fun kindLabel(kind: ArtworkKind) = stringResource(
    when (kind) {
        ArtworkKind.COVER -> Res.string.artwork_cover
        ArtworkKind.ANIMATED_COVER -> Res.string.artwork_animated_cover
        ArtworkKind.HERO -> Res.string.artwork_hero
        ArtworkKind.ANIMATED_HERO -> Res.string.artwork_animated_hero
        ArtworkKind.LOGO -> Res.string.artwork_logo
        ArtworkKind.ICON -> Res.string.artwork_icon
    },
)

@Composable
private fun sourceLabel(option: ArtworkOption): String {
    val name = when (option.source) {
        ArtworkSource.HOST -> stringResource(Res.string.artwork_source_host)
        ArtworkSource.ROM -> stringResource(Res.string.artwork_source_rom)
        ArtworkSource.COMPANION -> stringResource(Res.string.metadata_source_companion)
        ArtworkSource.IGDB -> "IGDB"
        ArtworkSource.STEAMGRIDDB -> "SteamGridDB"
    }
    return listOfNotNull(name, option.detail).joinToString(" · ")
}

/** A local file path as something the image loader takes. */
private fun thumbOf(path: String) = if (path.startsWith("/")) "file://$path" else path

/** Copies an image (picked file, local path, platform address) into the launcher's own-artwork folder. */
private suspend fun copyOwn(filesDir: java.io.File, address: String, itemId: String, kind: ArtworkKind): java.io.File? =
    io.github.thecodingdad.moonlightlauncher.ui.PlatformUi.current.copyImage(
        address, java.io.File(filesDir, "own-artwork"), "${itemId.hashCode().toUInt()}-${kind.name.lowercase()}-${System.currentTimeMillis()}",
    )

/** Detail page: confirm a match that is waiting for review. */
@Composable
fun ReviewConfirmAction(entry: io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry, onConfirm: () -> Unit) {
    if (entry.item.metadataState != io.github.thecodingdad.moonlightlauncher.data.db.MetadataState.REVIEW) return
    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
        stringResource(Res.string.metadata_confirm), onClick = onConfirm,
        icon = androidx.compose.material.icons.Icons.Filled.Check,
    )
}

/** Start tab tile of an app: SteamGridDB landscape images, an own image, or the default tile. */
@Composable
fun AppTileDialog(entry: io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?, onDismiss: () -> Unit) {
    val launcherContainer = io.github.thecodingdad.moonlightlauncher.LocalContainer.current
    val metadata = launcherContainer.metadata
    val scope = rememberCoroutineScope()
    val target = entry
    val pickImage = io.github.thecodingdad.moonlightlauncher.ui.PlatformUi.current.rememberImagePicker { address ->
        if (address == null || target == null) return@rememberImagePicker
        scope.launch {
            // Copy into the launcher's storage: the picked file may not stay readable.
            val dir = java.io.File(launcherContainer.platform.filesDir, "tiles")
            val file = io.github.thecodingdad.moonlightlauncher.ui.PlatformUi.current.copyImage(address, dir, "${target.item.id}-${System.currentTimeMillis()}")
            if (file != null) metadata.setTile(target.item.id, file.absolutePath)
            onDismiss()
        }
    }
    Modal(visible = target != null, onDismiss = onDismiss, title = stringResource(Res.string.app_tile_title), maxWidth = 1000.dp) {
        val e = target ?: return@Modal
        var options by remember(e.item.id) { mutableStateOf<List<SgdbAsset>?>(null) }
        LaunchedEffect(e.item.id) { options = metadata.tileOptions(e.item) }
        Text(stringResource(Res.string.app_tile_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        Spacer(Modifier.height(12.dp))
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
            io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                stringResource(Res.string.app_tile_own), onClick = { pickImage() },
                icon = androidx.compose.material.icons.Icons.Filled.Image,
            )
            if (e.item.tileUrl != null) {
                io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                    stringResource(Res.string.app_tile_reset), onClick = { scope.launch { metadata.setTile(e.item.id, null); onDismiss() } },
                    icon = androidx.compose.material.icons.Icons.Filled.Restore,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val list = options
        val tileGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        when {
            list == null -> CircularProgressIndicator(color = LauncherTheme.colors.accent)
            list.isEmpty() -> Text(stringResource(Res.string.app_tile_none), color = LauncherTheme.colors.onSurfaceMuted)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                state = tileGrid,
                modifier = Modifier.heightIn(max = 380.dp).mouseDragScroll(tileGrid),
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
                        ArtworkImage(asset.thumb ?: asset.url, e.item.title, Modifier.fillMaxSize(), showTitleOnPlaceholder = false)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ButtonHintBar(listOf(ButtonHint(PadButton.A, stringResource(Res.string.hint_select)), ButtonHint(PadButton.B, stringResource(Res.string.action_close), onClick = onDismiss)), inPopup = true)
    }
}
