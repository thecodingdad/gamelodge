package io.github.thecodingdad.gamelodge.ha.mqtt

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

actual fun jpegOf(bytes: ByteArray, maxSize: Int): ByteArray? =
    runCatching { Image.makeFromEncoded(bytes) }.getOrNull()?.let { encode(it, maxSize) }

actual fun jpegOf(image: ImageBitmap, maxSize: Int): ByteArray? = encode(Image.makeFromBitmap(image.asSkiaBitmap()), maxSize)

private fun encode(image: Image, maxSize: Int): ByteArray? {
    if (image.width <= 0 || image.height <= 0) return null
    val scale = min(1f, maxSize.toFloat() / max(image.width, image.height))
    val w = max(1, (image.width * scale).roundToInt())
    val h = max(1, (image.height * scale).roundToInt())
    val surface = Surface.makeRasterN32Premul(w, h)
    // JPEG has no transparency: transparent parts become black.
    surface.canvas.clear(Color.BLACK)
    surface.canvas.drawImageRect(
        image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()),
        SamplingMode.LINEAR, null, true,
    )
    return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes
}

private const val JPEG_QUALITY = 85
