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
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Animated GIF and WebP for the image loader on the desktop (Coil decodes only their first frame
 * there). A stored animation is converted once, in the background, to what is actually shown:
 * the size it is drawn at (in steps, see [AnimationCache.width]), at most 30 frames a second,
 * every frame complete, kept next to the artwork. Its first loop is decoded from that file and
 * kept in memory (LZ4, [FrameMemory]); from then on playing only unpacks. Until the file exists
 * the original plays, scaled frame by frame. Nothing plays off screen or while [AnimationPlayback]
 * is paused.
 */
class AnimatedImageDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult {
        // Stored artwork gets a converted file next to it; an image still coming from the network doesn't.
        val target = source.fileOrNull()?.toFile()?.let { io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles.derivedFile(it, AnimationCache.SUFFIX) }
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
        val needed = min(1f, max(targetW.toFloat() / codec.width, targetH.toFloat() / codec.height))
        val neededWidth = ceil(codec.width * needed).toInt()
        // One file per animation: a large enough one is just drawn smaller.
        val converted = target?.let { AnimationCache.converted(it, neededWidth) }
        val image = if (converted != null) {
            val c = Codec.makeFromData(Data.makeFromBytes(converted))
            AnimatedImage(c, c.width, c.height, target, neededWidth, scaled = false)
        } else {
            val width = AnimationCache.width(neededWidth, codec.width)
            val height = max(1, (codec.height * width.toFloat() / codec.width).roundToInt())
            target?.let { AnimationCache.convert(it, data, width, height) }
            AnimatedImage(codec, width, height, target, neededWidth, scaled = true)
        }
        return DecodeResult(image, isSampled = image.width < codec.width)
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

    companion object {
        /** Frame decoding of all animated images shares a few threads, never the UI thread. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(3))

        /**
         * Frames get every zero colour channel lifted by one level (invisible): Intel Iris Xe under
         * Direct3D 12 leaves blocks of a new texture unwritten where they are pure black, which then
         * flicker with stale GPU memory. Set from the window's graphics adapter.
         */
        @Volatile var liftBlacks = true
    }
}

/**
 * One animated image at its display size. A background job decodes frame after frame on time
 * while the image is drawn (frames build on the previous one) and publishes each frame; drawing
 * only takes the latest one. Switches to the converted file as soon as it exists.
 */
private class AnimatedImage(
    codec: Codec,
    override val width: Int,
    override val height: Int,
    /** Where the converted file is (or will be); null: none for this image. */
    private val converted: File?,
    /** Width it is shown at: a converted file at least this wide replaces the original. */
    private val neededWidth: Int,
    scaled: Boolean,
) : coil3.Image {
    /** What plays: the original (frames scaled here) or the converted file (frames as they are). */
    private class Playing(val codec: Codec, val scaled: Boolean) {
        val durations = codec.framesInfo.map { it.duration.coerceAtLeast(MIN_FRAME_MS) }
    }

    @Volatile private var playing = Playing(codec, scaled)
    /** Bumped when a new frame is ready; read while drawing, so Compose redraws just this image. */
    private val version = mutableIntStateOf(0)
    @Volatile private var frame: Image? = null
    @Volatile private var lastDrawn = 0L
    private var job: Job? = null
    /** Frames replaced by a newer one, with the time: freed once surely no longer drawn. */
    private val retired = ArrayDeque<Pair<Long, Image>>()

    override val size: Long get() = width.toLong() * height * 4
    // Stateful (current frame): every request gets its own.
    override val shareable: Boolean get() = false

    init {
        val p = playing
        val full = Bitmap().apply { allocPixels(p.codec.imageInfo) }
        p.codec.readPixels(full, 0)
        frame = frameImage(full, p)
        full.close()
    }

    override fun draw(canvas: org.jetbrains.skia.Canvas) {
        version.intValue
        lastDrawn = System.nanoTime()
        // Read while drawing: resuming redraws the image, which starts it again.
        if (job?.isActive != true && !AnimationPlayback.paused.value) start()
        frame?.let { canvas.drawImageRect(it, Rect.makeWH(width.toFloat(), height.toFloat())) }
    }

    @Synchronized
    private fun start() {
        if (job?.isActive == true) return
        job = AnimatedImageDecoder.scope.launch {
            var p = playing
            var full = Bitmap().apply { allocPixels(p.codec.imageInfo) }
            var decoded = -1
            var next = 0
            var checkedAt = System.nanoTime()
            // The loop in memory: kept while it plays, built during its first loop from the file.
            var frames: KeptFrames? = null
            var noRoom = false
            try {
                while (isActive) {
                    // Off screen (not drawn for a while) or paused: stop; drawing again starts from here.
                    if (System.nanoTime() - lastDrawn > IDLE_NS || AnimationPlayback.paused.value) break
                    // The converted file is ready: play that from its start (checked once a second).
                    val file = converted
                    if (p.scaled && file != null && System.nanoTime() - checkedAt > CHECK_NS) {
                        checkedAt = System.nanoTime()
                        if (AnimationCache.ready(file, neededWidth)) AnimationCache.converted(file, neededWidth)?.let { bytes ->
                            p = Playing(Codec.makeFromData(Data.makeFromBytes(bytes)), scaled = false)
                            playing = p
                            full.close()
                            full = Bitmap().apply { allocPixels(p.codec.imageInfo) }
                            decoded = -1
                            next = 0
                        }
                    }
                    val started = System.nanoTime()
                    val keep = !p.scaled && !noRoom && p.codec.width == width && p.codec.height == height
                    val kept = frames
                    if (keep && kept != null && kept.complete) {
                        // Everything in memory: only unpack.
                        publish(kept.image(next))
                    } else {
                        p.codec.readPixels(full, next, if (next == 0) -1 else decoded)
                        if (keep) {
                            val f = frames ?: KeptFrames(full.imageInfo, p.durations.size).also { frames = it }
                            val pixels = f.pixelsOf(full)
                            if (!f.add(next, pixels)) {
                                // Doesn't fit: this one keeps decoding (nothing else is pushed out).
                                f.release()
                                frames = null
                                noRoom = true
                            }
                            publish(f.imageOf(pixels))
                        } else {
                            publish(frameImage(full, p))
                        }
                    }
                    decoded = next
                    // The next frame on the shared beat: all animations change together, so the window
                    // is redrawn at most TICK times a second, not once per animation and frame.
                    val due = started + p.durations[decoded] * 1_000_000L
                    delay(((onBeat(due) - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
                    next = (decoded + 1) % p.durations.size
                }
            } finally {
                full.close()
                frames?.release()
            }
        }
    }

    /**
     * Makes [image] the frame drawn. Each frame is native memory: the replaced ones are freed here,
     * not whenever the garbage collector gets to them (memory would climb by hundreds of MB a
     * second for a hero). Freed only after a while: the UI may still draw it.
     */
    private fun publish(image: Image) {
        frame?.let { retired.addLast(System.nanoTime() to it) }
        frame = image
        version.intValue++
        val now = System.nanoTime()
        while (retired.isNotEmpty() && now - retired.first().first > RETIRE_NS) retired.removeFirst().second.close()
    }

    /** A decoded frame as an image of the display size (scaled from the original if that plays). */
    private fun frameImage(full: Bitmap, p: Playing): Image {
        val sameSize = p.codec.width == width && p.codec.height == height
        if (!p.scaled && sameSize && !AnimatedImageDecoder.liftBlacks) return Image.makeFromBitmap(full)
        val source = Image.makeFromBitmap(full)
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImageRect(
            source, Rect.makeWH(p.codec.width.toFloat(), p.codec.height.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()),
            DOWNSCALE, null, true,
        )
        if (AnimatedImageDecoder.liftBlacks) surface.canvas.drawPaint(blackLift)
        return surface.makeImageSnapshot().also { source.close(); surface.close() }
    }

    private companion object {
        val blackLift = Paint().apply { color = 0xFF010101.toInt(); blendMode = BlendMode.LIGHTEN }

        /** Browsers treat shorter GIF frame times as 100 ms; 20 ms keeps fast animations smooth. */
        const val MIN_FRAME_MS = 20
        const val IDLE_NS = 1_000_000_000L
        const val RETIRE_NS = 500_000_000L
        const val CHECK_NS = 1_000_000_000L
        const val BEAT_NS = 1_000_000_000L / 30

        /** The first beat at or after [time] (System.nanoTime). */
        fun onBeat(time: Long): Long = (time + BEAT_NS - 1) / BEAT_NS * BEAT_NS
    }
}

/** Smooth downscaling (mipmaps): plain bilinear sampling flickers on large reductions. */
private val DOWNSCALE = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

/**
 * The converted animations, kept next to their artwork and removed with it (no size limit, so
 * nothing is converted twice): one file per animation, its frames at the largest size it is shown
 * at so far (in steps), at most [MAX_FPS], each frame complete, as an animated WebP. Converted
 * once, one at a time, on a thread of low priority; shown smaller, the GPU scales it.
 */
internal object AnimationCache {
    private const val MAX_FPS = 30
    private const val QUALITY = 90

    /** The converted file's name after the artwork's ([io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles.derivedFile]). */
    const val SUFFIX = ".played.webp"

    /** Widths come in steps of 25 %, so resizing the window doesn't convert again for every pixel. */
    private const val STEP = 1.25
    private const val SMALLEST = 64

    private val running = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "animation-convert").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** The step at or above [needed] pixels, at most the original's [original] width. */
    fun width(needed: Int, original: Int): Int {
        if (needed >= original) return original
        val n = ceil(ln(max(needed, SMALLEST).toDouble() / SMALLEST) / ln(STEP) - 1e-9).toInt()
        return min(original, (SMALLEST * STEP.pow(n)).roundToInt())
    }

    /**
     * The converted file's bytes if it is at least [minWidth] wide (marked as used, for the size
     * limit); a smaller one is replaced by the next conversion.
     */
    fun converted(f: File, minWidth: Int): ByteArray? {
        if (!f.isFile) return null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { canvasWidth(it) >= minWidth }
    }

    /** A converted file at least [minWidth] wide exists (reads only its header). */
    fun ready(f: File, minWidth: Int): Boolean = runCatching {
        if (!f.isFile) return false
        val header = ByteArray(30)
        val read = f.inputStream().use { it.readNBytes(header, 0, header.size) }
        read == header.size && canvasWidth(header) >= minWidth
    }.getOrDefault(false)

    /** The canvas width in an animated WebP's VP8X header (0 if it isn't one). */
    private fun canvasWidth(webp: ByteArray): Int {
        if (webp.size < 30 || String(webp, 12, 4, Charsets.US_ASCII) != "VP8X") return 0
        return ((webp[24].toInt() and 0xFF) or ((webp[25].toInt() and 0xFF) shl 8) or ((webp[26].toInt() and 0xFF) shl 16)) + 1
    }

    /** Converts [data] to [width]×[height] in the background (replacing a smaller one), once per size and session. */
    fun convert(target: File, data: ByteArray, width: Int, height: Int) {
        val job = "${target.name}-$width"
        if (job in failed || !running.add(job)) return
        worker.execute {
            try {
                val started = System.nanoTime()
                // Meanwhile converted as large or larger (another tile size): keep that one.
                if (ready(target, width)) return@execute
                val bytes = transcode(data, width, height)
                // Through a temporary file: a player never reads a half-written one.
                val temp = File(target.parentFile, "${target.name}.tmp").apply { writeBytes(bytes) }
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                Log.d("Animation converted to ${width}x$height: ${data.size / 1024} KB -> ${bytes.size / 1024} KB in ${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: Throwable) {
                failed += job
                Log.w("Animation not converted", e)
            } finally {
                running -= job
            }
        }
    }

    /** Decodes the original frame by frame and keeps one every 1/[MAX_FPS] s, scaled and encoded. */
    fun transcode(data: ByteArray, width: Int, height: Int): ByteArray {
        val codec = Codec.makeFromData(Data.makeFromBytes(data))
        val durations = codec.framesInfo.map { it.duration.coerceAtLeast(20) }
        val minFrame = 1000 / MAX_FPS
        val frames = ArrayList<AnimatedWebp.Frame>()
        val full = Bitmap().apply { allocPixels(codec.imageInfo) }
        try {
            var time = 0
            var lastStart = 0
            for (i in durations.indices) {
                codec.readPixels(full, i, if (i == 0) -1 else i - 1)
                // Frames closer than 1/MAX_FPS to the last kept one only extend it.
                if (frames.isEmpty() || time - lastStart >= minFrame) {
                    frames.lastOrNull()?.let { it.duration = time - lastStart }
                    frames += AnimatedWebp.Frame(encode(full, codec.width, codec.height, width, height), 0)
                    lastStart = time
                }
                time += durations[i]
            }
            frames.last().duration = time - lastStart
        } finally {
            full.close()
        }
        return AnimatedWebp.mux(width, height, frames)
    }

    private fun encode(full: Bitmap, sourceW: Int, sourceH: Int, width: Int, height: Int): ByteArray {
        val source = Image.makeFromBitmap(full)
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.drawImageRect(
                source, Rect.makeWH(sourceW.toFloat(), sourceH.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()),
                DOWNSCALE, null, true,
            )
            val snapshot = surface.makeImageSnapshot()
            try {
                return snapshot.encodeToData(EncodedImageFormat.WEBP, QUALITY)!!.bytes
            } finally {
                snapshot.close()
            }
        } finally {
            source.close()
            surface.close()
        }
    }

}

/**
 * Animated WebP from still WebP frames (Skia encodes stills only): the RIFF container with a
 * VP8X header, ANIM, and an ANMF chunk per frame carrying that frame's image chunks.
 */
internal object AnimatedWebp {
    /** A frame (a complete still WebP of the canvas size) and how long it shows, in ms. */
    class Frame(val webp: ByteArray, var duration: Int)

    fun mux(width: Int, height: Int, frames: List<Frame>): ByteArray {
        var alpha = false
        val anmf = frames.map { frame ->
            val chunks = imageChunks(frame.webp)
            if (chunks.any { it.first == "ALPH" || it.first == "VP8L" }) alpha = true
            val payload = ByteArrayOutputStream()
            payload.write(u24(0)); payload.write(u24(0)) // offset x / 2, y / 2
            payload.write(u24(width - 1)); payload.write(u24(height - 1))
            payload.write(u24(frame.duration.coerceIn(1, 0xFFFFFF)))
            payload.write(0x02) // complete frames: no blending with the previous one, no disposal
            chunks.forEach { (id, bytes) -> writeChunk(payload, id, bytes) }
            payload.toByteArray()
        }
        val body = ByteArrayOutputStream()
        val vp8x = ByteArrayOutputStream().apply {
            write(0x02 or if (alpha) 0x10 else 0) // animation, alpha
            write(ByteArray(3))
            write(u24(width - 1)); write(u24(height - 1))
        }.toByteArray()
        writeChunk(body, "VP8X", vp8x)
        writeChunk(body, "ANIM", byteArrayOf(0, 0, 0, 0, 0, 0)) // background (unused), loop forever
        anmf.forEach { writeChunk(body, "ANMF", it) }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(u32(4 + body.size()))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        body.writeTo(out)
        return out.toByteArray()
    }

    /** The image chunks of a still WebP (ALPH, VP8, VP8L), without its header. */
    private fun imageChunks(webp: ByteArray): List<Pair<String, ByteArray>> {
        val result = ArrayList<Pair<String, ByteArray>>()
        var pos = 12
        while (pos + 8 <= webp.size) {
            val id = String(webp, pos, 4, Charsets.US_ASCII)
            val size = (webp[pos + 4].toInt() and 0xFF) or ((webp[pos + 5].toInt() and 0xFF) shl 8) or
                ((webp[pos + 6].toInt() and 0xFF) shl 16) or ((webp[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "ALPH" || id == "VP8 " || id == "VP8L") result += id to webp.copyOfRange(pos + 8, pos + 8 + size)
            pos += 8 + size + (size and 1)
        }
        require(result.any { it.first == "VP8 " || it.first == "VP8L" }) { "no image data in the frame" }
        return result
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(u32(data.size))
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun u24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

/**
 * Memory for animation loops kept unpacked-ready ([AnimatedImage]): a fixed budget. An animation
 * that doesn't fit keeps decoding; nothing already kept is pushed out (no churn).
 */
internal object FrameMemory {
    private const val BUDGET = 500L * 1024 * 1024
    private var used = 0L

    @Synchronized
    fun reserve(bytes: Long): Boolean {
        if (used + bytes > BUDGET) return false
        used += bytes
        return true
    }

    @Synchronized
    fun release(bytes: Long) {
        used = (used - bytes).coerceAtLeast(0)
    }
}

/**
 * Animations pause while nobody looks: the window isn't active, or nothing was used for a while
 * (set by the window). Paused, each shows its current frame and frees its memory.
 */
object AnimationPlayback {
    val paused = androidx.compose.runtime.mutableStateOf(false)
}

/**
 * One loop of frames in memory: each as the difference to the one before (a key frame where
 * there is none), LZ4-packed; counted against [FrameMemory]. Unpacking keeps the frame shown.
 */
internal class KeptFrames(val info: org.jetbrains.skia.ImageInfo, count: Int) {
    private val rowBytes = info.minRowBytes
    private val packed = arrayOfNulls<ByteArray>(count)
    private val key = BooleanArray(count)
    private var bytes = 0L
    private var last: ByteArray? = null
    private var lastIndex = -1
    private var current: ByteArray? = null
    private var unpacked: ByteArray? = null

    val complete: Boolean get() = packed.all { it != null }

    /** The decoded frame's pixels, lifted for Intel graphics (see [AnimatedImageDecoder.liftBlacks]). */
    fun pixelsOf(full: Bitmap): ByteArray {
        val pixels = full.readPixels(info, rowBytes, 0, 0)!!
        if (AnimatedImageDecoder.liftBlacks) for (i in pixels.indices) if (pixels[i].toInt() == 0) pixels[i] = 1
        return pixels
    }

    fun imageOf(pixels: ByteArray): Image = Image.makeRaster(info, pixels, rowBytes)

    /** Keeps frame [index]; false when it doesn't fit into [FrameMemory]. */
    fun add(index: Int, pixels: ByteArray): Boolean {
        if (packed[index] != null) return true
        val previous = last
        val isKey = previous == null || lastIndex != (index - 1 + packed.size) % packed.size
        val data = if (isKey) Lz4.compress(pixels) else Lz4.compress(xor(pixels, previous!!))
        if (!FrameMemory.reserve(data.size.toLong())) return false
        packed[index] = data
        key[index] = isKey
        bytes += data.size
        last = pixels
        lastIndex = index
        current = pixels.copyOf()
        return true
    }

    /** Frame [index] (played in order after the one before). */
    fun image(index: Int): Image = Image.makeRaster(info, frame(index), rowBytes)

    /** The pixels of frame [index] (played in order after the one before); valid until the next call. */
    fun frame(index: Int): ByteArray {
        val out = unpacked ?: ByteArray(info.height * rowBytes).also { unpacked = it }
        Lz4.decompress(packed[index]!!, out)
        val frame = current ?: ByteArray(out.size).also { current = it }
        if (key[index]) System.arraycopy(out, 0, frame, 0, out.size) else for (i in frame.indices) frame[i] = (frame[i].toInt() xor out[i].toInt()).toByte()
        return frame
    }

    fun release() {
        FrameMemory.release(bytes)
        bytes = 0
        packed.fill(null)
        last = null
        current = null
        unpacked = null
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}
