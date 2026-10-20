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
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted) }
        }
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
 * Live preview of a tab's tiles with the current settings: real games of the library, scaled
 * down; it can't be focused or tapped.
 */
@Composable
fun TabPreview(layout: TabLayout, apps: Boolean = false, startTab: Boolean = false, height: Dp = 220.dp) {
    val entries by io.github.thecodingdad.gamelodge.LocalContainer.current.library.entries.collectAsState()
    val sample = androidx.compose.runtime.remember(entries, apps) {
        entries.filter { (it.item.kind == ItemKind.APP) == apps && !it.item.hidden }
            .sortedByDescending { it.item.coverUrl != null || it.localTarget != null }.take(12)
    }
    val colors = LauncherTheme.colors
    val scale = 0.5f
    Box(
        // As high as the tiles need (at most [height]).
        Modifier.fillMaxWidth().heightIn(min = 60.dp, max = height).clip(RoundedCornerShape(16.dp)).background(colors.background)
            .border(1.dp, colors.surfaceRaised, RoundedCornerShape(16.dp))
            .focusProperties { canFocus = false }
            // Look, don't touch: taps and drags stop here.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } },
    ) {
        if (sample.isEmpty()) return@Box
        androidx.compose.runtime.CompositionLocalProvider(
            io.github.thecodingdad.gamelodge.ui.theme.LocalLauncherDimens provides io.github.thecodingdad.gamelodge.ui.theme.dimensFor(layout.cardSize),
        ) {
            Scaled(scale, Modifier.padding(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (startTab) 16.dp else 18.dp)) {
                    sample.forEach { e ->
                        when {
                            apps && layout.appsStyle == AppsStyle.BANNERS ->
                                io.github.thecodingdad.gamelodge.ui.components.AppTileCard(e, {}, layout = layout)
                            apps -> io.github.thecodingdad.gamelodge.ui.components.AppTile(e, {}, layout = layout)
                            else -> io.github.thecodingdad.gamelodge.ui.components.CoverCard(
                                e, {}, layout = layout,
                                width = LauncherTheme.dimens.coverWidth * when {
                                    !startTab -> layout.format.widthFactor
                                    layout.format == io.github.thecodingdad.gamelodge.data.settings.TileFormat.PORTRAIT -> 0.82f
                                    layout.format == io.github.thecodingdad.gamelodge.data.settings.TileFormat.SQUARE -> 1f
                                    else -> 1.6f / io.github.thecodingdad.gamelodge.ui.components.APP_LANDSCAPE_RATIO * io.github.thecodingdad.gamelodge.ui.components.GAME_LANDSCAPE_RATIO
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [content] laid out at 1 / [scale] of the room and drawn scaled down to fit it (left aligned, cut at the right). */
@Composable
private fun Scaled(scale: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val inner = Constraints(maxHeight = (constraints.maxHeight / scale).toInt())
        val placeable = measurables.first().measure(inner)
        layout(constraints.maxWidth, (placeable.height * scale).toInt().coerceAtMost(constraints.maxHeight)) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }
        }
    }
}
