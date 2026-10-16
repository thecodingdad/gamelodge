package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.ui.graphics.Color
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkPlatform
import io.github.thecodingdad.moonlightlauncher.ui.feedback.FeedbackOutput
import io.github.thecodingdad.moonlightlauncher.ui.feedback.HapticStrength
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** UI sounds through Java Sound; "vibration" is a short rumble on the controller in use. */
class DesktopFeedbackOutput(private val gamepads: () -> Gamepads?) : FeedbackOutput {
    private val ids = AtomicInteger()
    private val clips = ConcurrentHashMap<Int, Clip>()

    override fun load(file: File): Int {
        val id = ids.incrementAndGet()
        runCatching {
            val clip = AudioSystem.getClip()
            AudioSystem.getAudioInputStream(file).use { clip.open(it) }
            clips[id] = clip
        }.onFailure { Log.w("UI sound ${file.name} not loaded", it) }
        return id
    }

    override fun unload(id: Int) {
        clips.remove(id)?.close()
    }

    // Java Sound may block for milliseconds on stop/start: never on the UI thread (focus moves stutter).
    private val audio = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ui-sound").apply { isDaemon = true } }

    override fun play(id: Int, volume: Float) {
        val clip = clips[id] ?: return
        audio.execute { playNow(clip, volume) }
    }

    private fun playNow(clip: Clip, volume: Float) {
        runCatching {
            (clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.let { gain ->
                val db = if (volume <= 0.0001f) gain.minimum else (20 * log10(volume)).coerceIn(gain.minimum, gain.maximum)
                gain.value = db
            }
            clip.stop()
            clip.framePosition = 0
            clip.start()
        }
    }

    override fun haptic(sound: UiSound, strength: HapticStrength) {
        if (sound == UiSound.FOCUS) return // a rumble per focus step is too much on a pad
        val power = when (strength) {
            HapticStrength.LIGHT -> 0.25f
            HapticStrength.MEDIUM -> 0.45f
            HapticStrength.STRONG -> 0.8f
        }
        val millis = when (sound) {
            UiSound.LAUNCH -> 120
            UiSound.ERROR -> 160
            else -> 40
        }
        // The pads are busy polling now and then; the rumble waits for them off the UI thread.
        audio.execute { gamepads()?.vibrate(strength = power, millis = millis) }
    }
}

/** Cover colours from the image itself; generated tiles for PC games come with phase 2. */
object DesktopArtwork : ArtworkPlatform {
    /** Store covers are web addresses, host box art and own images files. */
    override fun imageModel(value: String): Any = if (value.startsWith("http://") || value.startsWith("https://")) value else File(value)

    override suspend fun accentColor(model: Any): Color? {
        val context = PlatformContext.INSTANCE
        // Small and not kept in the memory cache (it would push out images that are shown).
        val request = ImageRequest.Builder(context).data(model).size(64)
            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        val bitmap = result.image.toBitmap()
        // All pixels in one call (per-pixel reads cross into native code each time).
        val info = org.jetbrains.skia.ImageInfo.makeN32(bitmap.width, bitmap.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
        val bytes = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return null
        val n = bitmap.width * bitmap.height
        // N32 is BGRA on little-endian machines.
        return vibrantColor(IntArray(n) { i ->
            val o = i * 4
            ((bytes[o + 3].toInt() and 0xFF) shl 24) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)
        })
    }

    /**
     * The most vibrant common colour: pixels are grouped by hue, each group weighted by how
     * saturated and bright its pixels are; the strongest group's average wins.
     */
    fun vibrantColor(pixels: IntArray): Color? {
        val buckets = HashMap<Int, DoubleArray>() // hue bucket -> weight, r, g, b
        for (argb in pixels) {
            if ((argb ushr 24) < 128) continue
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val value = mx / 255.0
            val saturation = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            if (saturation < 0.25 || value < 0.25) continue
            val hue = hue(r, g, b, mx, mn)
            val weight = saturation * value
            val bucket = buckets.getOrPut((hue / 15).toInt()) { DoubleArray(4) }
            bucket[0] += weight
            bucket[1] += r * weight
            bucket[2] += g * weight
            bucket[3] += b * weight
        }
        val best = buckets.values.maxByOrNull { it[0] } ?: return null
        return Color((best[1] / best[0]).toInt(), (best[2] / best[0]).toInt(), (best[3] / best[0]).toInt())
    }

    private fun hue(r: Int, g: Int, b: Int, mx: Int, mn: Int): Double {
        if (mx == mn) return 0.0
        val d = (mx - mn).toDouble()
        val h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return if (h < 0) h + 360 else h
    }
}
