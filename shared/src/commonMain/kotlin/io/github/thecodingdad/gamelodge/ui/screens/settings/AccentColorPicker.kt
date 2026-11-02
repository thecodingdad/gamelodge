package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Swatches: 18 hues in a light and a deep tone, plus greys. */
internal val swatches: List<Int> = buildList {
    for (tone in listOf(0.55f to 1f, 0.8f to 0.82f)) for (i in 0 until 18) add(Color.hsv(i * 20f, tone.first, tone.second).toArgb())
    addAll(listOf(0xFFFFFFFF, 0xFFC7CBD6, 0xFF8E93A3, 0xFF5B6070).map { it.toInt() })
}

/** Small preview of a colour (in rows and on swatches). */
@Composable
fun ColorDot(color: Int, size: Int = 28) {
    Box(
        Modifier.size(size.dp).background(Color(color), CircleShape)
            .border(2.dp, LauncherTheme.colors.onSurface.copy(alpha = 0.25f), CircleShape),
    )
}

/**
 * Picks the fixed accent: a grid of swatches and hue / saturation / brightness sliders for an own
 * colour. Every change applies right away, so the whole launcher previews it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentColorPicker(
    color: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(Res.string.setting_accent_color),
    /** Set: an "automatic" choice on top (selected when [automatic]). */
    onAutomatic: (() -> Unit)? = null,
    automatic: Boolean = false,
    autoTitle: String = stringResource(Res.string.accent_text_auto),
    autoSub: String? = stringResource(Res.string.accent_text_auto_sub),
) {
    // The sliders keep their own values (a grey loses its hue when converted back and forth).
    var hsv by remember { mutableStateOf(toHsv(color)) }
    Modal(visible = true, onDismiss = onDismiss, title = title, maxWidth = 760.dp) {
        // Scrolls on small screens (controller, touch and mouse wheel).
        androidx.compose.foundation.layout.Column(
            Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        if (onAutomatic != null) {
            SwitchRow(
                autoTitle, automatic,
                { on -> if (on) onAutomatic() else onChange(color) },
                subtitle = autoSub, initialFocus = automatic,
            )
        }
        SectionHeader(stringResource(Res.string.accent_picker_presets))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            swatches.forEachIndexed { i, c ->
                val selected = !automatic && c == color
                FocusSurface(
                    onClick = { hsv = toHsv(c); onChange(c) },
                    shape = CircleShape,
                    color = Color(c),
                    focusScale = 1.15f,
                    modifier = Modifier.size(48.dp)
                        .restoreFocusIf(!automatic && (selected || (i == 0 && swatches.none { it == color }))),
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check, null,
                            tint = if (Color(c).luminance() > 0.5f) Color.Black else Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
        SectionHeader(stringResource(Res.string.accent_picker_custom))
        SliderRow(
            stringResource(Res.string.accent_hue), hsv.first.roundToInt(), 0..355, 5,
            onChange = { h -> hsv = hsv.copy(first = h.toFloat()); onChange(hsvColor(hsv)) },
            valueLabel = { "$it°" },
        )
        SliderRow(
            stringResource(Res.string.accent_saturation), (hsv.second * 100).roundToInt(), 0..100, 5,
            onChange = { s -> hsv = hsv.copy(second = s / 100f); onChange(hsvColor(hsv)) },
            valueLabel = { "$it %" },
        )
        SliderRow(
            stringResource(Res.string.accent_brightness), (hsv.third * 100).roundToInt(), 20..100, 5,
            onChange = { v -> hsv = hsv.copy(third = v / 100f); onChange(hsvColor(hsv)) },
            valueLabel = { "$it %" },
        )
        }
    }
}

private fun hsvColor(hsv: Triple<Float, Float, Float>) = Color.hsv(hsv.first.coerceIn(0f, 359.9f), hsv.second.coerceIn(0f, 1f), hsv.third.coerceIn(0f, 1f)).toArgb()

private fun toHsv(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0) it + 360f else it }
    return Triple(h, if (mx == 0f) 0f else d / mx, mx)
}
