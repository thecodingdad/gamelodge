package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.mutableIntStateOf
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Animated GIF and WebP for the image loader on the desktop (Coil decodes only their first frame
 * there). Frames are decoded in the background, scaled once to the size the image is shown at,
 * and only while the image is on screen; a new frame redraws only that image.
 */
class AnimatedImageDecoder(private val source: ImageSource, private val options: Options) : Decoder {
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
        // The size it is shown at (Coil's target size), never larger than the image itself.
        val targetW = options.size.width.pxOrElse { codec.width }
        val targetH = options.size.height.pxOrElse { codec.height }
        val scale = min(1f, max(targetW.toFloat() / codec.width, targetH.toFloat() / codec.height))
        val width = max(1, (codec.width * scale).roundToInt())
        val height = max(1, (codec.height * scale).roundToInt())
        return DecodeResult(AnimatedImage(codec, width, height), isSampled = scale < 1f)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source.source()
            val animated = source.rangeEquals(0, GIF87) || source.rangeEquals(0, GIF89) ||
                (source.rangeEquals(0, RIFF) && source.rangeEquals(8, WEBP) && isAnimatedWebp(source.peek()))
            if (!animated) return null
            return AnimatedImageDecoder(result.source, options)
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

    internal companion object {
        /** Frame decoding of all animated images shares a few threads, never the UI thread. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(3))
    }
}

/**
 * One animated image at its display size. A background job decodes frame after frame on time
 * while the image is drawn (frames build on the previous one) and publishes each scaled frame;
 * drawing only takes the latest one.
 */
private class AnimatedImage(private val codec: Codec, override val width: Int, override val height: Int) : coil3.Image {
    private val durations = codec.framesInfo.map { it.duration.coerceAtLeast(MIN_FRAME_MS) }
    /** Bumped when a new frame is ready; read while drawing, so Compose redraws just this image. */
    private val version = mutableIntStateOf(0)
    @Volatile private var frame: Image? = null
    @Volatile private var lastDrawn = 0L
    private var job: Job? = null

    override val size: Long get() = width.toLong() * height * 4
    // Stateful (current frame): every request gets its own.
    override val shareable: Boolean get() = false

    init {
        frame = decodeFrames(0, null).second
    }

    override fun draw(canvas: Canvas) {
        version.intValue
        lastDrawn = System.nanoTime()
        if (job?.isActive != true) start()
        frame?.let { canvas.drawImageRect(it, Rect.makeWH(width.toFloat(), height.toFloat())) }
    }

    @Synchronized
    private fun start() {
        if (job?.isActive == true) return
        job = AnimatedImageDecoder.scope.launch {
            val full = Bitmap().apply { allocPixels(codec.imageInfo) }
            var decoded = -1
            var next = 0
            while (isActive) {
                // Off screen (not drawn for a while): stop; drawing again starts from here.
                if (System.nanoTime() - lastDrawn > IDLE_NS) break
                val started = System.nanoTime()
                codec.readPixels(full, next, if (next == 0) -1 else decoded)
                decoded = next
                frame = scaled(full)
                version.intValue++
                val spent = (System.nanoTime() - started) / 1_000_000
                delay((durations[decoded] - spent).coerceAtLeast(1))
                next = (decoded + 1) % durations.size
            }
        }
    }

    /** First frame for the initial draw (synchronous, in the decoder's coroutine). */
    private fun decodeFrames(index: Int, prior: Bitmap?): Pair<Bitmap, Image> {
        val full = prior ?: Bitmap().apply { allocPixels(codec.imageInfo) }
        codec.readPixels(full, index)
        return full to scaled(full)
    }

    private fun scaled(full: Bitmap): Image {
        val source = Image.makeFromBitmap(full)
        if (width == codec.width && height == codec.height) return source
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImageRect(
            source, Rect.makeWH(codec.width.toFloat(), codec.height.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()),
            SamplingMode.LINEAR, null, true,
        )
        return surface.makeImageSnapshot().also { source.close(); surface.close() }
    }

    private companion object {
        /** Browsers treat shorter GIF frame times as 100 ms; 20 ms keeps fast animations smooth. */
        const val MIN_FRAME_MS = 20
        const val IDLE_NS = 1_000_000_000L
    }
}
