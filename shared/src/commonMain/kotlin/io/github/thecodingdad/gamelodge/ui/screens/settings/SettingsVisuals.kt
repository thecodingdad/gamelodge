package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.settings.AppsStyle
import io.github.thecodingdad.gamelodge.data.settings.TabLayout
import io.github.thecodingdad.gamelodge.data.settings.ThemeMode
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.components.widthFactor
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/** A small outlined rectangle in a tile's shape (width : height [ratio]), e.g. in segments and tabs. */
@Composable
fun ShapeMark(ratio: Float, modifier: Modifier = Modifier) {
    val height = if (ratio < 1f) 16.dp else if (ratio == 1f) 13.dp else 10.dp
    Box(modifier.height(height).aspectRatio(ratio).border(2.dp, LocalContentColor.current, RoundedCornerShape(2.dp)))
}

/**
 * Several on / off choices as chips below a title (e.g. which badges tiles show). Each chip is
 * its own focus stop (D-pad left / right), A or a tap switches it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipToggleRow(
    title: String,
    options: List<T>,
    selected: Set<T>,
    label: @Composable (T) -> String,
    icon: (T) -> ImageVector,
    onToggle: (T, Boolean) -> Unit,
    subtitle: String? = null,
) {
    val colors = LauncherTheme.colors
    val texts: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted) }
        }
    }
    val chips: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val on = option in selected
                val content = if (on) colors.onAccent else colors.onSurface
                FocusSurface(
                    onClick = { onToggle(option, !on) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) colors.accent else colors.surfaceRaised,
                    focusScale = 1.06f,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (on) Icons.Filled.Check else icon(option), null, tint = content, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label(option), style = MaterialTheme.typography.labelLarge, color = content)
                    }
                }
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        // Next to the title while the chips fit in one line there (the title keeps 40 %); else below it.
        androidx.compose.ui.layout.SubcomposeLayout { constraints ->
            val chipsWidth = subcompose("measure", chips).first().measure(Constraints()).width
            val beside = chipsWidth <= constraints.maxWidth * 0.6f
            val content = subcompose("content$beside") {
                if (beside) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        texts(Modifier.weight(1f).padding(end = 12.dp))
                        chips()
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        texts(Modifier)
                        chips()
                    }
                }
            }.first().measure(constraints.copy(minHeight = 0))
            layout(content.width, content.height) { content.place(0, 0) }
        }
    }
}

/** A row of choices shown as small pictures with a label (themes); the chosen one ticked. */
@Composable
fun <T> PictureChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    picture: @Composable (T) -> Unit,
    initialFocus: Boolean = false,
) {
    val colors = LauncherTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            options.forEachIndexed { i, option ->
                val on = option == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusSurface(
                        onClick = { onSelect(option) },
                        modifier = Modifier.width(120.dp).aspectRatio(1.5f)
                            .then(if (on) Modifier.border(3.dp, colors.accent, RoundedCornerShape(12.dp)) else Modifier)
                            .restoreFocus(initialFocus && on),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceRaised,
                    ) {
                        picture(option)
                        if (on) {
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Text(label(option), style = MaterialTheme.typography.labelMedium, color = if (on) colors.onSurface else colors.onSurfaceMuted)
                }
            }
        }
    }
}

@Composable
private fun Modifier.restoreFocus(on: Boolean): Modifier = restoreFocusIf(on)

/** Miniature of a theme: background, a surface row with text bars and the accent. */
@Composable
fun ThemePicture(mode: ThemeMode) {
    val (bg, surface, text) = when (mode) {
        ThemeMode.DARK -> Triple(Color(0xFF0E1015), Color(0xFF222632), Color(0xFFF2F3F7))
        ThemeMode.BLACK -> Triple(Color.Black, Color(0xFF17171B), Color(0xFFF2F3F7))
        ThemeMode.LIGHT -> Triple(Color(0xFFF3F4F8), Color.White, Color(0xFF14161C))
    }
    val accent = LauncherTheme.colors.accent
    Column(Modifier.fillMaxWidth().fillMaxHeight().background(bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(36.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
        repeat(2) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(surface).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(text.copy(alpha = 0.7f)))
            }
        }
    }
}

/**
 * The accent colours as dots in a row (the chosen one ringed) and a dot for more colours (the
 * full picker).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentSwatchRow(title: String, color: Int, onPick: (Int) -> Unit, onMore: () -> Unit, subtitle: String? = null) {
    val colors = LauncherTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted) }
        }
        // Ten colours spread over the whole palette; the chosen one is always among them.
        val step = (swatches.size / 10).coerceAtLeast(1)
        val dots = swatches.filterIndexed { i, _ -> i % step == 0 }.take(10).let { if (color in it) it else it.take(9) + color }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            dots.forEach { c ->
                val on = c == color
                FocusSurface(
                    onClick = { onPick(c) },
                    shape = CircleShape,
                    color = Color(c),
                    focusScale = 1.15f,
                    modifier = Modifier.size(40.dp).then(if (on) Modifier.border(3.dp, colors.onSurface, CircleShape) else Modifier),
                ) {
                    if (on) Icon(Icons.Filled.Check, null, tint = if (Color(c).luminanceValue() > 0.5f) Color.Black else Color.White, modifier = Modifier.align(Alignment.Center).size(20.dp))
                }
            }
            FocusSurface(onClick = onMore, shape = CircleShape, color = colors.surfaceRaised, focusScale = 1.15f, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Palette, null, tint = colors.onSurface, modifier = Modifier.align(Alignment.Center).size(20.dp))
            }
        }
    }
}

private fun Color.luminanceValue() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * One tile of a tab in its real size and look (focused, so zoom and glow show), standing for an
 * installed game / app: artwork of one from the library (a placeholder while it is empty), shown
 * as a favourite with play time and progress so every setting can be seen.
 */
@Composable
fun TilePreviewDialog(layout: TabLayout, apps: Boolean, startTab: Boolean, onDismiss: () -> Unit) {
    val entries by io.github.thecodingdad.gamelodge.LocalContainer.current.library.entries.collectAsState()
    val placeholder = org.jetbrains.compose.resources.stringResource(
        if (apps) Res.string.tile_preview_sample_app else Res.string.tile_preview_sample_game,
    )
    val sample = androidx.compose.runtime.remember(entries, apps, placeholder) { sampleEntry(entries, apps, placeholder) }
    // The tile alone over the blurred screen; a tap on it or B closes it.
    io.github.thecodingdad.gamelodge.ui.components.Spotlight(visible = true, onDismiss = onDismiss) {
        androidx.compose.runtime.CompositionLocalProvider(
            io.github.thecodingdad.gamelodge.ui.theme.LocalLauncherDimens provides io.github.thecodingdad.gamelodge.ui.theme.dimensFor(layout.cardSize),
        ) {
            val coverWidth = LauncherTheme.dimens.coverWidth
            Box(Modifier.padding(28.dp), contentAlignment = Alignment.Center) {
                when {
                    apps && layout.appsStyle == AppsStyle.BANNERS -> io.github.thecodingdad.gamelodge.ui.components.AppTileCard(sample, onDismiss, layout = layout, width = coverWidth * 1.6f)
                    apps -> io.github.thecodingdad.gamelodge.ui.components.AppTile(sample, onDismiss, layout = layout)
                    else -> io.github.thecodingdad.gamelodge.ui.components.CoverCard(
                        sample, onDismiss, layout = layout,
                        // As wide as in the tab: the start tab's rows, the library grid's cells.
                        width = coverWidth * when {
                            !startTab -> layout.format.widthFactor
                            layout.format == io.github.thecodingdad.gamelodge.data.settings.TileFormat.PORTRAIT -> 0.82f
                            layout.format == io.github.thecodingdad.gamelodge.data.settings.TileFormat.SQUARE -> 1f
                            else -> 1.6f / io.github.thecodingdad.gamelodge.ui.components.APP_LANDSCAPE_RATIO * io.github.thecodingdad.gamelodge.ui.components.GAME_LANDSCAPE_RATIO
                        },
                    )
                }
            }
        }
        Text(
            org.jetbrains.compose.resources.stringResource(Res.string.tile_preview_close),
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f),
        )
    }
}

/** The preview's tile: artwork of a library entry (else none), installed, favourite, played. */
private fun sampleEntry(entries: List<io.github.thecodingdad.gamelodge.data.db.LibraryEntry>, apps: Boolean, placeholder: String): io.github.thecodingdad.gamelodge.data.db.LibraryEntry {
    val base = if (apps) {
        entries.firstOrNull { it.item.kind == ItemKind.APP && !it.item.hidden && it.localTarget != null }
            ?: entries.firstOrNull { it.item.kind == ItemKind.APP && !it.item.hidden }
    } else {
        val games = entries.filter { it.item.kind == ItemKind.GAME && !it.item.hidden && it.item.coverUrl != null }
        games.firstOrNull { it.item.heroUrl != null && it.item.logoUrl != null } ?: games.firstOrNull()
    }
    val now = System.currentTimeMillis()
    val item = (base?.item ?: io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity(
        id = "preview", title = placeholder, normalizedTitle = placeholder.lowercase(), kind = if (apps) ItemKind.APP else ItemKind.GAME, addedAt = now,
    )).copy(
        favorite = true, playtimeSeconds = 12 * 3600L, lastPlayedAt = now - 24 * 3600_000L,
        igdbId = base?.item?.igdbId ?: -1, timeToBeatIgdbId = base?.item?.igdbId ?: -1, timeToBeatMain = 20 * 3600,
    )
    // Installed everywhere it runs.
    val targets = base?.targets?.map { it.copy(installed = true) }.orEmpty()
    return io.github.thecodingdad.gamelodge.data.db.LibraryEntry(item, targets)
}
