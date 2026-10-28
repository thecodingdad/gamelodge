package io.github.thecodingdad.gamelodge.ha.mqtt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

actual fun jpegOf(bytes: ByteArray, maxSize: Int): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Decoded at a power-of-two fraction close above the target, scaled down exactly afterwards.
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return encode(bitmap, maxSize)
}

actual fun jpegOf(image: ImageBitmap, maxSize: Int): ByteArray? = encode(image.asAndroidBitmap(), maxSize)

private fun encode(bitmap: Bitmap, maxSize: Int): ByteArray {
    val scale = maxSize.toFloat() / max(bitmap.width, bitmap.height)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), true)
    } else bitmap
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
}

private const val JPEG_QUALITY = 85
