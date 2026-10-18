package io.github.thecodingdad.gamelodge.ui.screens.settings

import io.github.thecodingdad.gamelodge.ui.components.handCursor
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.alpha
import io.github.thecodingdad.gamelodge.resources.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import io.github.thecodingdad.gamelodge.ui.input.repeatCount
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiSound
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/** One step of a sub page's path; tapping it goes there. */
class Crumb(val label: String, val onClick: () -> Unit)

/** Where a sub page sits ("Settings › Hosts") and how to leave it; none on the main tabs. */
class PagePath(val parents: List<Crumb>, val onBack: () -> Unit)

val LocalPagePath = androidx.compose.runtime.staticCompositionLocalOf<PagePath?> { null }

/**
 * Page heading. On sub pages the path ("Settings › Appearance", parents can be tapped); when
 * it doesn't fit, only "‹ Appearance" with the arrow going back.
 */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier) {
    val colors = LauncherTheme.colors
    val style = MaterialTheme.typography.headlineMedium
    val path = LocalPagePath.current
    if (path == null) {
        Text(title, style = style, color = colors.onSurface, modifier = modifier)
        return
    }
    // Taps only: controllers go back with B, the path takes no focus.
    // Keyed by a stable value, not the (new every recomposition) lambda, so a tap isn't lost.
    // Going up the path sounds (and vibrates) like the back arrow.
    val feedback = LocalUiFeedback.current
    fun Modifier.tap(key: Any, action: () -> Unit) = handCursor().pointerInput(key) {
        detectTapGestures { feedback?.play(UiSound.BACK); action() }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val full = (path.parents.map { it.label } + title).joinToString(SEPARATOR)
        val fits = remember(full, constraints.maxWidth) {
            measurer.measure(full, style, maxLines = 1).size.width <= constraints.maxWidth
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (fits) {
                path.parents.forEach { crumb ->
                    Text(crumb.label, style = style, color = colors.onSurfaceMuted, maxLines = 1, modifier = Modifier.tap(crumb.label, crumb.onClick))
                    Text(SEPARATOR, style = style, color = colors.onSurfaceMuted, maxLines = 1)
                }
                Text(title, style = style, color = colors.onSurface, maxLines = 1)
            } else {
                androidx.compose.foundation.layout.Row(
                    Modifier.tap("back", path.onBack),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
                        tint = colors.onSurface, modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        title, style = style, color = colors.onSurface, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val SEPARATOR = "  ›  "

/**
 * Settings page: the title (path) stays fixed at the top, the content below scrolls; the first
 * focusable element gets initial focus.
 */
@Composable
fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    val horizontal = if (io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current) 20.dp else 48.dp
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Limited width, centred on wide screens; the title lines up with the entries.
        PageTitle(title, Modifier.padding(horizontal = horizontal).padding(top = 16.dp, bottom = 12.dp).widthIn(max = 900.dp).fillMaxWidth())
        io.github.thecodingdad.gamelodge.ui.components.ScrollAreaColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            inner = Modifier.padding(horizontal = horizontal).focusRestorer(),
        ) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = LauncherTheme.colors.accentText,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
            .searchHighlight(text, RoundedCornerShape(6.dp), focus = false)
            .padding(horizontal = 4.dp),
    )
}

/**
 * A search hit with this [title] scrolls the element into view, focuses it ([focus]) and pulses
 * an accent outline and tint over it in [shape] (drawn on top, so it shows with focus too).
 */
@Composable
fun Modifier.searchHighlight(
    title: String?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(LauncherTheme.dimens.cornerRadius),
    focus: Boolean = true,
): Modifier {
    val highlight = rememberSearchHighlight(title, focus)
    val accent = LauncherTheme.colors.accent
    return then(highlight.modifier).drawWithContent {
        drawContent()
        val amount = highlight.amount
        if (amount > 0f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, accent.copy(alpha = amount * 0.25f))
            drawOutline(outline, accent.copy(alpha = amount), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
    }
}

/** Where a settings search hit leads: the title of the setting to show once its page opens. */
object SearchHighlight {
    /** One request: the setting's title, when it was made, and whether a row took it. */
    internal class Request(val title: String, val at: Long) {
        // Plain field: taking the request must not change the state the rows' effects are keyed on.
        var taken = false
    }

    internal var current by androidx.compose.runtime.mutableStateOf<Request?>(null)

    fun request(title: String?) {
        current = title?.let { Request(it, System.currentTimeMillis()) }
    }
}

internal class HighlightState(val modifier: Modifier, private val anim: androidx.compose.animation.core.Animatable<Float, *>) {
    val amount: Float get() = anim.value
}

/**
 * The row whose [title] a search hit asked for scrolls into view, takes the focus ([focus]) and
 * lights up briefly in the accent colour.
 */
@Composable
private fun rememberSearchHighlight(title: String?, focus: Boolean = true): HighlightState {
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val request = SearchHighlight.current?.takeIf { title != null && it.title == title }
    androidx.compose.runtime.LaunchedEffect(request) {
        if (request == null || request.taken) return@LaunchedEffect
        // Only what can take the focus (a setting) uses the request up; a section title lights up along.
        if (focus) request.taken = true
        // A stale request (the page had no such row) is dropped.
        if (System.currentTimeMillis() - request.at > HIGHLIGHT_REQUEST_MS) return@LaunchedEffect
        // Let the page come in and place its initial focus first.
        kotlinx.coroutines.delay(HIGHLIGHT_DELAY_MS)
        requester.bringIntoView()
        if (focus) runCatching { focusRequester.requestFocus() }
        repeat(2) {
            anim.animateTo(1f, androidx.compose.animation.core.tween(220))
            anim.animateTo(0.25f, androidx.compose.animation.core.tween(380))
        }
        anim.animateTo(1f, androidx.compose.animation.core.tween(220))
        kotlinx.coroutines.delay(600)
        anim.animateTo(0f, androidx.compose.animation.core.tween(900))
    }
    val modifier = Modifier.bringIntoViewRequester(requester).let { if (focus) it.focusRequester(focusRequester) else it }
    return HighlightState(modifier, anim)
}

private const val HIGHLIGHT_DELAY_MS = 350L
private const val HIGHLIGHT_REQUEST_MS = 10_000L

@Composable
private fun RowShell(
    onClick: () -> Unit,
    modifier: Modifier,
    initialFocus: Boolean,
    /** Title of the row: a search hit with this title scrolls here and lights the row up. */
    title: String? = null,
    content: @Composable () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp).restoreFocusIf(initialFocus).searchHighlight(title),
        color = io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: LauncherTheme.colors.surface,
        focusScale = 1.02f,
    ) {
        // Vertically centred: rows have a minimum height, short content must not stick to the top.
        Box(Modifier.align(Alignment.CenterStart).padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun Texts(title: String, subtitle: String?, icon: ImageVector?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = LauncherTheme.colors.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            }
        }
    }
}

/** Opens a sub page or performs an action. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    initialFocus: Boolean = false,
    trailing: String? = null,
    /** Shown before the arrow, e.g. a colour preview. */
    trailingContent: (@Composable () -> Unit)? = null,
    /** Off for rows that only lead to a setting (search hits), so they don't take its highlight. */
    highlightable: Boolean = true,
) {
    RowShell(onClick, Modifier, initialFocus, title.takeIf { highlightable }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Texts(title, subtitle, icon, Modifier.weight(1f))
            // Kept short so the title keeps its room.
            if (trailing != null) Text(
                trailing, style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurfaceMuted,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
            )
            trailingContent?.let { Box(Modifier.padding(end = 8.dp)) { it() } }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    initialFocus: Boolean = false,
    /** Off: shown dimmed and not switchable (e.g. a store that isn't installed). */
    enabled: Boolean = true,
) {
    RowShell({ if (enabled) onChange(!checked) }, if (enabled) Modifier else Modifier.alpha(0.45f), initialFocus, title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Texts(title, subtitle, icon, Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = null, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = LauncherTheme.colors.accent),
            )
        }
    }
}

/** Rows narrower than this put their control below the texts. */
private val NARROW_ROW = 520.dp

/** Cycles through [options] with A, D-pad left/right or touch on the arrows. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    initialFocus: Boolean = false,
) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    val feedback = LocalUiFeedback.current
    fun step(delta: Int) {
        feedback?.play(UiSound.TOGGLE)
        onSelect(options[(index + delta).mod(options.size)])
    }
    RowShell(
        onClick = { step(1) },
        title = title,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        },
        initialFocus = initialFocus,
    ) {
        val value = label(options[index])
        // The title and its description keep their room: the value takes at most 40 % of the row
        // (wrapping), and on narrow rows it moves below the texts.
        androidx.compose.foundation.layout.BoxWithConstraints {
            val narrow = maxWidth < NARROW_ROW
            val valueMax = if (narrow) maxWidth - 112.dp else maxWidth * 0.4f
            val control: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Touch: the arrows step in their direction (the tap doesn't reach the row).
                    ChoiceArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { step(-1) }
                    Text(
                        value,
                        style = MaterialTheme.typography.labelLarge,
                        color = LauncherTheme.colors.onSurface,
                        maxLines = 2,
                        modifier = Modifier.widthIn(min = 96.dp, max = valueMax).padding(horizontal = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    ChoiceArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight) { step(1) }
                }
            }
            if (narrow) {
                Column {
                    Texts(title, subtitle, icon)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { control() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Texts(title, subtitle, icon, Modifier.weight(1f))
                    control()
                }
            }
        }
    }
}

@Composable
private fun ChoiceArrow(icon: ImageVector, onTap: () -> Unit) {
    // Keyed on Unit: a new lambda per recomposition would restart the detector mid-tap (the row's
    // press feedback recomposes), and the tap got lost or went to the row instead.
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).handCursor().pointerInput(Unit) { detectTapGestures { tap() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = LauncherTheme.colors.onSurfaceMuted)
    }
}

/** Value slider controlled by D-pad left/right (hold to repeat) or touch drag. */
@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
    valueLabel: (Int) -> String = { it.toString() },
    icon: ImageVector? = null,
) {
    RowShell(
        onClick = {},
        title = title,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // Holding the key repeats; repeats accelerate after the first few steps.
            val factor = if (e.repeatCount > 6) 4 else 1
            when (e.key) {
                Key.DirectionLeft -> { onChange((value - step * factor).coerceIn(range)); true }
                Key.DirectionRight -> { onChange((value + step * factor).coerceIn(range)); true }
                else -> false
            }
        },
        initialFocus = false,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Texts(title, null, icon, Modifier.weight(1f))
                Text(valueLabel(value), style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurface)
            }
            androidx.compose.material3.Slider(
                value = value.toFloat(),
                onValueChange = { onChange(((it / step).toInt() * step).coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.fillMaxWidth().height(32.dp).handCursor(),
            )
        }
    }
}

/** Plain info line (versions, status). */
@Composable
fun InfoRow(title: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: LauncherTheme.colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
    }
}

/** Opens a settings page, the controllers or a host's settings; [highlight] lights that setting up there. */
val LocalSettingsNavigator = androidx.compose.runtime.staticCompositionLocalOf<(SettingsTarget, String?) -> Unit> { { _, _ -> } }

/** A "see also" link: [title] of the page or setting, where it is ([target]) and what lights up there. */
class SeeAlsoLink(val title: String, val target: SettingsTarget, val highlight: String? = null, val subtitle: String? = null)

/** Related settings elsewhere, at the end of a page; links to pages this platform lacks are left out. */
@Composable
fun SeeAlso(vararg links: SeeAlsoLink?) {
    val shown = links.filterNotNull().filter { settingsTargetAvailable(it.target) }
    if (shown.isEmpty()) return
    val navigate = LocalSettingsNavigator.current
    SectionHeader(org.jetbrains.compose.resources.stringResource(Res.string.see_also))
    shown.forEach { link ->
        NavRow(
            link.title, { navigate(link.target, link.highlight) }, link.subtitle, highlightable = false,
        )
    }
}

/**
 * An action on a settings page (clear, search, download …): icon, title and what it does, no arrow.
 * [hold] makes it a hold-to-confirm action for destructive ones.
 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    hold: Boolean = false,
) {
    if (hold) {
        io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton(
            title, onConfirmed = onClick, modifier = Modifier.searchHighlight(title), icon = icon, subtitle = subtitle, row = true, enabled = enabled,
        )
        return
    }
    RowShell(if (enabled) onClick else ({}), if (enabled) Modifier else Modifier.alpha(0.45f), false, title) {
        Texts(title, subtitle, icon)
    }
}
