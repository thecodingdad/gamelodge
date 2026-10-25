package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * Backdrop blur without the GPU blur filter: Intel Iris Xe under Direct3D 12 leaves blocks of the
 * filter's offscreen texture unwritten (stripes and blocks of stale memory). When a popup opens
 * the content is captured once (a recorded picture, played back on the CPU), blurred on the CPU at
 * a quarter of its size and faded in on top. The frozen picture is invisible under the blur.
 */
@Composable
fun snapshotBlur(active: Boolean, radius: Dp, full: Dp): Modifier {
    val layer = rememberGraphicsLayer()
    var blurred by remember { mutableStateOf<ImageBitmap?>(null) }
    val sigma = remember { floatArrayOf(0f) }
    LaunchedEffect(active) {
        // Closing keeps the image while it fades out; opening captures afresh.
        if (!active) return@LaunchedEffect
        blurred = null
        // The layer holds what was drawn last: wait for a frame if nothing was drawn yet.
        while (layer.size.width == 0) androidx.compose.runtime.withFrameNanos { }
        val shot = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@LaunchedEffect
        blurred = withContext(Dispatchers.Default) { runCatching { blur(shot, sigma[0]) }.getOrNull() }
    }
    val fraction = if (full.value > 0f) (radius / full).coerceIn(0f, 1f) else 0f
    return Modifier.drawWithContent {
        sigma[0] = full.toPx() * 0.57735f + 0.5f
        layer.record { this@drawWithContent.drawContent() }
        val image = blurred
        if (image == null || fraction < 1f) drawLayer(layer)
        if (image != null && fraction > 0f) {
            drawImage(
                image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero,
                alpha = fraction, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
    }
}

/** [shot] blurred by [sigma] (in full-size pixels), returned at a quarter of the size. */
private fun blur(shot: ImageBitmap, sigma: Float): ImageBitmap {
    val scale = 4
    val w = (shot.width / scale).coerceAtLeast(1)
    val h = (shot.height / scale).coerceAtLeast(1)
    val source = Image.makeFromBitmap(shot.asSkiaBitmap())
    val surface = Surface.makeRasterN32Premul(w, h)
    // Scaled down first, then blurred: the edges clamp, so no dark rim comes in.
    val small = Surface.makeRasterN32Premul(w, h).apply {
        canvas.drawImageRect(source, Rect.makeWH(shot.width.toFloat(), shot.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
    }.makeImageSnapshot()
    val s = (sigma / scale).coerceAtLeast(0.5f)
    Paint().use { paint ->
        paint.imageFilter = ImageFilter.makeBlur(s, s, FilterTileMode.CLAMP)
        surface.canvas.drawImage(small, 0f, 0f, paint)
    }
    return surface.makeImageSnapshot().toComposeImageBitmap()
}
