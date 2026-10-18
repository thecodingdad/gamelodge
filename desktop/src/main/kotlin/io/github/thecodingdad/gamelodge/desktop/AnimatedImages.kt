package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.mutableLongStateOf
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

/**
 * Animated GIF and WebP for the image loader on the desktop (Coil decodes only their first frame
 * there). Frames are decoded one after the other while the image is drawn, only the current one
 * is kept: animated artwork is often 10–30 MB with hundreds of frames.
 */
class AnimatedImageDecoder(private val source: coil3.decode.ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val data = source.use { it.source().readByteArray() }
        val codec = Codec.makeFromData(Data.makeFromBytes(data))
        if (codec.frameCount <= 1) {
            // A still image after all: decoded once, like Coil does.
            val bitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
            codec.readPixels(bitmap, 0)
            bitmap.setImmutable()
            return DecodeResult(bitmap.asImage(), isSampled = false)
        }
        return DecodeResult(AnimatedImage(codec), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source.source()
            val animated = source.rangeEquals(0, GIF87) || source.rangeEquals(0, GIF89) ||
                (source.rangeEquals(0, RIFF) && source.rangeEquals(8, WEBP) && isAnimatedWebp(source.peek()))
            if (!animated) return null
            return AnimatedImageDecoder(result.source)
        }

        /** An animated WebP has the VP8X header with the animation flag set. */
        private fun isAnimatedWebp(peek: okio.BufferedSource): Boolean = runCatching {
            peek.skip(12)
            if (!peek.rangeEquals(0, VP8X)) return false
            peek.skip(8)
            (peek.readByte().toInt() and 0x02) != 0
        }.getOrDefault(false)

        private companion object {
            val GIF87 = "GIF87a".encodeUtf8()
            val GIF89 = "GIF89a".encodeUtf8()
            val RIFF = "RIFF".encodeUtf8()
            val WEBP = "WEBP".encodeUtf8()
            val VP8X = "VP8X".encodeUtf8()
        }
    }
}

/** One animated image: draws the frame due now and keeps the animation ticking while it is drawn. */
private class AnimatedImage(private val codec: Codec) : coil3.Image {
    private val durations = codec.framesInfo.map { it.duration.coerceAtLeast(MIN_FRAME_MS) }
    private val total = durations.sum().coerceAtLeast(1)
    private val bitmap = Bitmap().apply { allocPixels(codec.imageInfo) }
    private var decoded = -1
    private var frame: Image? = null
    private val start = System.nanoTime()

    override val size: Long get() = width.toLong() * height * 4
    override val width: Int get() = codec.width
    override val height: Int get() = codec.height
    // Stateful (current frame): every request gets its own.
    override val shareable: Boolean get() = false

    override fun draw(canvas: Canvas) {
        // Reading the ticker in the draw phase lets Compose draw again on the next tick.
        AnimationTicker.tick.longValue
        AnimationTicker.keepRunning()
        val due = frameAt(((System.nanoTime() - start) / 1_000_000 % total).toInt())
        // Frames build on the previous one: decoded in order, wrapping to the first.
        while (decoded != due) {
            val next = (decoded + 1) % durations.size
            codec.readPixels(bitmap, next, if (next == 0) -1 else decoded)
            decoded = next
            frame = null
        }
        val image = frame ?: Image.makeFromBitmap(bitmap).also { frame = it }
        canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
    }

    private fun frameAt(ms: Int): Int {
        var t = ms
        durations.forEachIndexed { i, d -> if (t < d) return i else t -= d }
        return durations.lastIndex
    }

    private companion object {
        /** Browsers treat shorter GIF frame times as 100 ms; 20 ms keeps fast animations smooth. */
        const val MIN_FRAME_MS = 20
    }
}

/** Shared clock of the animated images: ticks while one was drawn within the last half second. */
private object AnimationTicker {
    val tick = mutableLongStateOf(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    @Volatile private var lastUse = 0L
    @Volatile private var running = false

    fun keepRunning() {
        lastUse = System.nanoTime()
        if (running) return
        running = true
        scope.launch {
            while (System.nanoTime() - lastUse < IDLE_NS) {
                delay(FRAME_MS)
                tick.longValue++
            }
            running = false
        }
    }

    private const val FRAME_MS = 16L
    private const val IDLE_NS = 500_000_000L
}
