package io.github.thecodingdad.gamelodge.ui.shell

import io.github.thecodingdad.gamelodge.ui.components.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.ui.components.ArtworkImage
import io.github.thecodingdad.gamelodge.ui.components.heroModel
import io.github.thecodingdad.gamelodge.ui.input.PadBlocker
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

/** Shows the screensaver right away (Settings › Display); the launcher's frame listens. */
object ScreensaverControl {
    internal val requests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The screensaver is showing (reported to Home Assistant). */
    val active = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun show() {
        requests.tryEmit(Unit)
    }

    /** The input that asked for it (the button's press and release) must not dismiss it at once. */
    const val GRACE_MS = 800L
}

/**
 * Idle screen: slow artwork slideshow with clock. Content drifts a little on every slide so
 * nothing stays on the same OLED pixels (the clock fades to its new place). Any input only
 * dismisses it. The slides stand still and cross-fade; with [kenBurns] each one zooms slowly from
 * its start until it has faded out (in steps of ~100 ms with reduced motion), which redraws the
 * screen all the time.
 */
@Composable
fun Screensaver(games: List<LibraryEntry>, kenBurns: Boolean, onDismiss: () -> Unit) {
    val reduceMotion = io.github.thecodingdad.gamelodge.ui.theme.LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    val withArt = remember(games) { games.filter { it.heroModel() != null }.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    var drift by remember { mutableStateOf(0 to 0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { io.github.thecodingdad.gamelodge.ui.components.FocusSound.silence(); runCatching { focus.requestFocus() } }
    LaunchedEffect(withArt, kenBurns, reduceMotion) {
        while (true) {
            delay(SLIDE_MS.toLong())
            index = (index + 1) % withArt.size.coerceAtLeast(1)
            drift = Random.nextInt(-24, 25) to Random.nextInt(-16, 17)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }

    PadBlocker()
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                // Swallow the whole key press; dismiss on release so it never reaches the screen below.
                if (e.type == KeyEventType.KeyUp) onDismiss()
                true
            }
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val entry = withArt.getOrNull(index)
        Crossfade(entry, animationSpec = tween(FADE_MS), label = "screensaver") { e ->
            if (e != null) {
                // Its own zoom, on through its fade-out: the next slide's start doesn't reset it.
                val zoom = remember { Animatable(1f) }
                if (kenBurns) LaunchedEffect(Unit) {
                    val total = SLIDE_MS + FADE_MS
                    val end = 1f + ZOOM * total / SLIDE_MS
                    if (reduceMotion) for (step in 1..total / STEP_MS) {
                        delay(STEP_MS.toLong())
                        zoom.snapTo(1f + (end - 1f) * step * STEP_MS / total)
                    } else zoom.animateTo(end, tween(total, easing = LinearEasing))
                }
                ArtworkImage(
                    e.heroModel(), e.item.title,
                    // Only scaled in its layer: transparency there would draw it offscreen first, every frame.
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                    },
                    ContentScale.Crop, showTitleOnPlaceholder = false,
                )
            }
        }
        // Dimmed by a plain black veil (as the 70 % opacity did), and darker towards the clock.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black)))
        // The clock moves with every slide (OLED): it fades out and in at its new place.
        Crossfade(drift to entry, Modifier.align(Alignment.BottomStart), animationSpec = tween(FADE_MS), label = "screensaverClock") { (d, e) ->
            Column(Modifier.offset(x = d.first.dp, y = d.second.dp).padding(56.dp)) {
                Text(io.github.thecodingdad.gamelodge.ui.PlatformUi.current.formatTime(now), fontSize = 72.sp, color = Color.White.copy(alpha = 0.9f))
                Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.7f))
                e?.let { Text(it.item.title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 12.dp)) }
            }
        }
    }
}

private const val SLIDE_MS = 12_000
/** Cross-fade of slides and clock. */
private const val FADE_MS = 1500
/** How far a slide zooms in (Ken Burns), and the step with reduced motion. */
private const val ZOOM = 0.12f
private const val STEP_MS = 100
