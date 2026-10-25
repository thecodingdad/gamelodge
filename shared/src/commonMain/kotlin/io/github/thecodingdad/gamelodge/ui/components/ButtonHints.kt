package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

val LocalButtonLayout = staticCompositionLocalOf { ButtonLayout.XBOX }

/** One entry of the hint bar. [onClick] makes the hint usable by touch as well. */
/** [secondButton] shows a pair such as LT/RT for "previous/next". */
data class ButtonHint(
    val button: PadButton,
    val label: String,
    val onClick: (() -> Unit)? = null,
    val secondButton: PadButton? = null,
)

/** Bottom bar listing what each button does right now. Always visible, in every screen. */
@Composable
fun ButtonHintBar(hints: List<ButtonHint>, modifier: Modifier = Modifier, hideWhenCompact: Boolean = false, inPopup: Boolean = false) {
    // Button hints only with a controller connected (and the hint bar switched on), popups too.
    if (!LocalShowHintBar.current) return
    // Portrait/phone is mostly touch: the main hint bars are hidden there (more room for content).
    if (hideWhenCompact && LocalCompactWidth.current) return
    val compact = LocalCompactWidth.current
    val feedback = io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback.current
    // One line that never wraps; on narrow screens it scrolls instead.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            // At the screen edge it keeps a margin; in a popup the popup's own padding is enough.
            .padding(horizontal = if (inPopup) 0.dp else if (compact) 16.dp else 28.dp, vertical = if (inPopup) 0.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        abOrder(hints).forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    // Touch only: the hints describe buttons, they must not catch D-pad focus.
                    .focusProperties { canFocus = false }
                    .then(
                        if (hint.onClick != null) Modifier.handCursor().clickable {
                            // Sound and vibration like the button it stands for.
                            feedback?.play(if (hint.button == PadButton.B) io.github.thecodingdad.gamelodge.ui.feedback.UiSound.BACK else io.github.thecodingdad.gamelodge.ui.feedback.UiSound.CONFIRM)
                            hint.onClick.invoke()
                        } else Modifier,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                // A pair (previous/next) is one glyph "LB/RB" to save space.
                PadGlyph(hint.button, second = hint.secondButton)
                Spacer(Modifier.size(8.dp))
                Text(hint.label, style = MaterialTheme.typography.labelMedium, color = LauncherTheme.colors.onSurfaceMuted, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Controller button glyph in the style of the configured layout. */
@Composable
fun PadGlyph(button: PadButton, modifier: Modifier = Modifier, second: PadButton? = null) {
    val layout = LocalButtonLayout.current
    val (first, color) = glyphFor(button, layout)
    val label = second?.let { "$first/${glyphFor(it, layout).first}" } ?: first
    val isRound = second == null && button in listOf(
        PadButton.A, PadButton.B, PadButton.X, PadButton.Y,
        PadButton.RS_LEFT, PadButton.RS_RIGHT, PadButton.RS_UP, PadButton.RS_DOWN,
    )
    Box(
        modifier = modifier
            .then(if (isRound) Modifier.size(26.dp) else Modifier.widthIn(min = 34.dp))
            .clip(if (isRound) CircleShape else RoundedCornerShape(7.dp))
            .background(color)
            .padding(horizontal = if (isRound) 0.dp else 6.dp, vertical = if (isRound) 0.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun glyphFor(button: PadButton, layout: ButtonLayout): Pair<String, Color> {
    val shoulder = Color(0xFF3A3F4E)
    return when (button) {
        // Logical buttons: A = confirm, B = back. In Nintendo layout they are labelled accordingly.
        PadButton.A -> if (layout == ButtonLayout.NINTENDO) "A" to Color(0xFFE5484D) else "A" to Color(0xFF2FA84F)
        PadButton.B -> if (layout == ButtonLayout.NINTENDO) "B" to Color(0xFFE6B422) else "B" to Color(0xFFD9363E)
        PadButton.X -> if (layout == ButtonLayout.NINTENDO) "X" to Color(0xFF2D7FF9) else "X" to Color(0xFF2D6FD8)
        PadButton.Y -> if (layout == ButtonLayout.NINTENDO) "Y" to Color(0xFF2FA84F) else "Y" to Color(0xFFE6B422)
        PadButton.L1 -> "LB" to shoulder
        PadButton.R1 -> "RB" to shoulder
        PadButton.L2 -> "LT" to shoulder
        PadButton.R2 -> "RT" to shoulder
        PadButton.START -> "☰" to shoulder
        PadButton.SELECT -> "⧉" to shoulder
        // Right stick: a round "R", like the face buttons.
        PadButton.RS_LEFT, PadButton.RS_RIGHT, PadButton.RS_UP, PadButton.RS_DOWN -> "R" to shoulder
    }
}

/** B always right after A (whatever order a screen lists them in), the rest keeps its order. */
internal fun abOrder(hints: List<ButtonHint>): List<ButtonHint> {
    val a = hints.indexOfFirst { it.button == io.github.thecodingdad.gamelodge.ui.input.PadButton.A }
    val b = hints.indexOfFirst { it.button == io.github.thecodingdad.gamelodge.ui.input.PadButton.B }
    if (a < 0 || b < 0 || b == a + 1) return hints
    val rest = hints.toMutableList().apply { removeAt(b) }
    return rest.apply { add(rest.indexOf(hints[a]) + 1, hints[b]) }
}
