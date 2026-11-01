package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import io.github.thecodingdad.gamelodge.data.settings.TabLayout
import io.github.thecodingdad.gamelodge.data.settings.TileBadge
import io.github.thecodingdad.gamelodge.data.settings.TileInfo
import io.github.thecodingdad.gamelodge.data.settings.TileTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/**
 * Requests focus once if this element is the one the user left from. Used so that
 * returning from the detail screen lands exactly where the user was.
 */
@Composable
fun Modifier.restoreFocusIf(shouldFocus: Boolean, trigger: Any? = null): Modifier {
    val requester = remember { FocusRequester() }
    val target by androidx.compose.runtime.rememberUpdatedState(shouldFocus)
    // Only when the element appears (screen opened, filter switched, back from details) or the
    // user picks up the controller. Not whenever it becomes the remembered element: during fast
    // navigation late requests of two elements would keep stealing focus from each other.
    // [trigger]: asks again when it changes (e.g. the library's filter, whose grid stays).
    val controllerMode = isControllerMode()
    LaunchedEffect(controllerMode, trigger) {
        // The element the user just touched wins over the remembered one.
        if (controllerMode && target && !TouchFocus.justRestored()) {
            FocusSound.silence()
            runCatching { requester.requestFocus() }
        }
    }
    return this.focusRequester(requester)
}

/** Animated covers on every visible tile, not only on the focused one (display settings). */
val LocalAnimatedTilesAlways = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A game tile in the tab's [layout] (shape, title, extra info, badges). [animated]: animated
 * covers are wanted; they play on the focused tile (controller; touch has no focus) or, if so
 * set, on all.
 */
@Composable
fun CoverCard(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LauncherTheme.dimens.coverWidth,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    animated: Boolean = false,
    layout: TabLayout = TabLayout(title = TileTitle.OFF),
) {
    val style = LocalTileStyle.current
    val format = layout.format
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            shape = layout.corners.shape,
            focusScale = style.scale(),
            glow = style.glow,
            modifier = Modifier.fillMaxWidth().aspectRatio(format.aspectRatio(entry.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP)),
        ) { focused ->
            val play = animated && (LocalAnimatedTilesAlways.current || (focused && isControllerMode()))
            // A placeholder names the game only while no title shows elsewhere.
            TileImage(entry, format, Modifier.fillMaxSize(), animated = play, showTitleOnPlaceholder = layout.title == TileTitle.OFF)
            // Owned but not installed on the host: dimmed, playing it starts the installation.
            if (entry.notInstalled && style.dimNotInstalled) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            TileOverlay(entry, layout)
            RunningMark(entry, layout.corners.shape)
        }
        TileTexts(entry, layout)
    }
}

/** Title / extra info on the tile, the progress bar and the badges (inside the tile's box). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileOverlay(entry: LibraryEntry, layout: TabLayout) {
    val info = tileInfo(entry, layout.info)
    val progress = tileProgress(entry, layout.info)
    if (layout.title == TileTitle.ON_TILE) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = if (progress != null) 12.dp else 8.dp),
        ) {
            Text(entry.item.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            info?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1) }
        }
    } else if (layout.title == TileTitle.OFF && info != null) {
        // No line below the tile: the extra info sits on it.
        Text(
            info, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, end = 6.dp, bottom = if (progress != null) 12.dp else 6.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    progress?.let { p ->
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66000000))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(LauncherTheme.colors.accent))
        }
    }
    TileBadges(entry, layout.badges)
}

/** Title and extra info below a tile, as the layout wants them. */
@Composable
private fun TileTexts(entry: LibraryEntry, layout: TabLayout) {
    if (layout.title != TileTitle.BELOW) return
    Text(
        entry.item.title,
        style = MaterialTheme.typography.labelMedium,
        color = LauncherTheme.colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    tileInfo(entry, layout.info)?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = LauncherTheme.colors.onSurfaceMuted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** The extra info as text (play time, last played), or null. */
@Composable
fun tileInfo(entry: LibraryEntry, info: TileInfo): String? = when (info) {
    TileInfo.PLAYTIME -> entry.item.playtimeSeconds.takeIf { it > 0 }?.let { io.github.thecodingdad.gamelodge.ui.screens.stats.formatDuration(it) }
    TileInfo.LAST_PLAYED -> entry.item.lastPlayedAt?.let { lastPlayedLabel(it) }
    TileInfo.PROGRESS, TileInfo.NONE -> null
}

/** Play time against the time to beat (main story), when the progress is wanted and known. */
fun tileProgress(entry: LibraryEntry, info: TileInfo): Float? {
    if (info != TileInfo.PROGRESS || !entry.item.timesToBeatCurrent) return null
    val main = entry.item.timeToBeatMain?.takeIf { it > 0 } ?: return null
    return (entry.item.playtimeSeconds.toFloat() / main).coerceIn(0f, 1f)
}

/** "Today", "Yesterday", "3 days ago" or the date. */
@Composable
private fun lastPlayedLabel(at: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now(zone)).toInt()
    return when {
        days <= 0 -> org.jetbrains.compose.resources.stringResource(Res.string.tile_today)
        days == 1 -> org.jetbrains.compose.resources.stringResource(Res.string.tile_yesterday)
        days < 30 -> org.jetbrains.compose.resources.stringResource(Res.string.tile_days_ago, days)
        else -> java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT).format(day)
    }
}

/** The badges of a tile: favourite (top left), where it runs and not installed (top right). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileBadges(entry: LibraryEntry, badges: Set<TileBadge>) {
    if (TileBadge.FAVORITE in badges && entry.item.favorite) {
        // Below the "running" badge while the game runs.
        val below = LocalRunningItems.current.isRunning(entry.item.id)
        Icon(
            Icons.Filled.Favorite, null, tint = Color(0xFFFF5C8A),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = if (below) 34.dp else 8.dp).size(18.dp),
        )
    }
    SourceBadges(entry, Modifier.align(Alignment.TopEnd).padding(6.dp), badges)
}

/**
 * Columns of a cover grid. Grid cells always fill the width, so the card size picks the number of
 * columns: medium as many as fit [mediumCell], small one more, large one fewer. This way every
 * size looks different on every screen (a plain minimum width often gives the same count).
 */
class CoverColumns(private val mediumCell: Dp, private val step: Int) : androidx.compose.foundation.lazy.grid.GridCells {
    override fun androidx.compose.ui.unit.Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val medium = ((availableSize + spacing) / (mediumCell.roundToPx() + spacing)).coerceAtLeast(1)
        val count = (medium + step).coerceAtLeast(1)
        val total = availableSize - spacing * (count - 1)
        val cell = total / count
        val rest = total % count
        return List(count) { cell + if (it < rest) 1 else 0 }
    }

    override fun equals(other: Any?) = other is CoverColumns && other.mediumCell == mediumCell && other.step == step
    override fun hashCode() = 31 * mediumCell.hashCode() + step
}

/** Landscape game tiles: the shape of Steam / SteamGridDB banners (920×430). */
const val GAME_LANDSCAPE_RATIO = 92f / 43f

/** App tiles: the shape of Android TV banners. */
const val APP_LANDSCAPE_RATIO = 16f / 9f

/** Width : height of a tile in [this] format ([app]: landscape app tiles keep 16:9). */
fun io.github.thecodingdad.gamelodge.data.settings.TileFormat.aspectRatio(app: Boolean = false): Float = when (this) {
    io.github.thecodingdad.gamelodge.data.settings.TileFormat.PORTRAIT -> 2f / 3f
    io.github.thecodingdad.gamelodge.data.settings.TileFormat.LANDSCAPE -> if (app) APP_LANDSCAPE_RATIO else GAME_LANDSCAPE_RATIO
    io.github.thecodingdad.gamelodge.data.settings.TileFormat.SQUARE -> 1f
}

/** Tile width relative to a cover's, so all formats have a similar weight on screen. */
val io.github.thecodingdad.gamelodge.data.settings.TileFormat.widthFactor: Float get() = when (this) {
    io.github.thecodingdad.gamelodge.data.settings.TileFormat.PORTRAIT -> 1f
    io.github.thecodingdad.gamelodge.data.settings.TileFormat.LANDSCAPE -> 2.1f
    io.github.thecodingdad.gamelodge.data.settings.TileFormat.SQUARE -> 1.25f
}

/**
 * Cover grid columns for the chosen card size and tile [format]; [compact]: narrow screens
 * (phone upright). [smaller]: that many more columns for every size.
 */
@Composable
fun coverColumns(
    compact: Boolean,
    format: io.github.thecodingdad.gamelodge.data.settings.TileFormat = io.github.thecodingdad.gamelodge.data.settings.TileFormat.PORTRAIT,
    smaller: Int = 0,
): androidx.compose.foundation.lazy.grid.GridCells {
    val medium = io.github.thecodingdad.gamelodge.ui.theme.dimensFor(io.github.thecodingdad.gamelodge.data.settings.CardSize.MEDIUM).coverWidth
    val current = LauncherTheme.dimens.coverWidth
    val step = when {
        current < medium -> 1
        current > medium -> -1
        else -> 0
    }
    return CoverColumns((if (compact) medium * 0.62f else medium + 8.dp) * format.widthFactor, step + smaller)
}

/** Landscape (16:9) tile of an app: app rows on the start tab, the apps tab as banners. */
@Composable
fun AppTileCard(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = LauncherTheme.dimens.coverWidth * 1.6f,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    layout: TabLayout = TabLayout(title = TileTitle.OFF, badges = setOf(TileBadge.FAVORITE)),
) {
    val style = LocalTileStyle.current
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            shape = layout.corners.shape,
            focusScale = style.scale(),
            glow = style.glow,
            modifier = Modifier.fillMaxWidth().aspectRatio(APP_LANDSCAPE_RATIO),
        ) {
            AppTileImage(entry, Modifier.fillMaxSize())
            TileOverlay(entry, layout)
            RunningMark(entry, layout.corners.shape)
        }
        TileTexts(entry, layout)
    }
}

@Composable
fun SourceBadges(entry: LibraryEntry, modifier: Modifier = Modifier, badges: Set<TileBadge> = TileBadge.entries.toSet()) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (TileBadge.LOCAL in badges && entry.localTarget != null) Badge(io.github.thecodingdad.gamelodge.ui.PlatformUi.current.localIcon)
        if (TileBadge.ROM in badges && entry.romTargets.isNotEmpty()) Badge(Icons.Filled.SportsEsports)
        if (TileBadge.HOST in badges && entry.remoteTargets.isNotEmpty()) Badge(Icons.Filled.Cloud)
        if (TileBadge.NOT_INSTALLED in badges && entry.notInstalled) Badge(Icons.Filled.Download)
    }
}

@Composable
private fun Badge(icon: ImageVector) {
    Box(
        Modifier.size(24.dp).background(Color(0xAA000000), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Square tile for apps: Android icon for local apps, host artwork for remote ones. */
@Composable
fun AppTile(
    entry: LibraryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    layout: TabLayout = TabLayout(badges = emptySet()),
) {
    // Narrow screens: smaller tiles so four fit next to each other.
    val size = if (LocalCompactWidth.current) LauncherTheme.dimens.iconTile * 0.6f else LauncherTheme.dimens.iconTile
    val colors = LauncherTheme.colors
    val style = LocalTileStyle.current
    val onTile = layout.title == TileTitle.ON_TILE
    Column(modifier.width(size + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            modifier = Modifier.size(size),
            color = colors.surfaceRaised,
            shape = layout.corners.shape,
            focusScale = style.scale(1.1f),
            glow = style.glow,
        ) {
            val pkg = entry.localTarget?.packageName
            if (pkg != null) {
                // With the title on the tile the icon moves up a little.
                AppIcon(pkg, Modifier.align(if (onTile) Alignment.TopCenter else Alignment.Center).padding(top = if (onTile) size * 0.12f else 0.dp).size(size * if (onTile) 0.5f else 0.62f))
                if (onTile) {
                    Text(
                        entry.item.title, style = MaterialTheme.typography.labelSmall, color = colors.onSurface, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            } else {
                ArtworkImage(entry.coverModel(), entry.item.title, Modifier.fillMaxSize(), showTitleOnPlaceholder = true)
                if (onTile) TileOverlay(entry, layout.copy(info = TileInfo.NONE))
            }
            if (pkg != null || !onTile) TileBadges(entry, layout.badges)
            RunningMark(entry, layout.corners.shape)
        }
        if (layout.title == TileTitle.BELOW) {
            Text(
                entry.item.title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Large landscape card (resume card, carousel highlight). */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.PlayArrow,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LauncherTheme.colors
    FocusSurface(onClick = onClick, onFocused = onFocused, onLongClick = onLongClick, modifier = modifier, focusScale = 1.04f) {
        ArtworkImage(image, title, Modifier.fillMaxSize(), showTitleOnPlaceholder = false)
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.onAccent)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            }
        }
    }
}

/** Shape helper used by several screens. */
val CardShape = RoundedCornerShape(14.dp)
