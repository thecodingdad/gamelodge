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

/**
 * Idle screen: slow artwork slideshow with clock. Content drifts a little on every slide so
 * nothing stays on the same OLED pixels. Any input only dismisses it.
 */
@Composable
fun Screensaver(games: List<LibraryEntry>, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    val withArt = remember(games) { games.filter { it.heroModel() != null }.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    var drift by remember { mutableStateOf(0 to 0) }
    val zoom = remember { Animatable(1f) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(withArt) {
        while (true) {
            zoom.snapTo(1f)
            zoom.animateTo(1.12f, tween(SLIDE_MS, easing = LinearEasing))
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
        Crossfade(entry, animationSpec = tween(1500), label = "screensaver") { e ->
            if (e != null) {
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
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = drift.first.dp, y = drift.second.dp)
                .padding(56.dp),
        ) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), fontSize = 72.sp, color = Color.White.copy(alpha = 0.9f))
            Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)), style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.7f))
            entry?.let { Text(it.item.title, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

private const val SLIDE_MS = 12_000
