package io.github.thecodingdad.moonlightlauncher.ui.feedback

import androidx.compose.runtime.staticCompositionLocalOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class UiSound { FOCUS, CONFIRM, BACK, ERROR, LAUNCH, TOGGLE }

/** Timbre of the synthesised UI sounds; [scale] lowers the base frequencies of the duller styles. */
enum class SoundStyle(val scale: Double = 1.0) { MUFFLED(0.45), WOOD(0.35), DEEP(0.25), SOFT, CLICK, RETRO, GLASS }

enum class HapticStrength { LIGHT, MEDIUM, STRONG }

/** Everything the user can tune about sounds and vibration. */
data class FeedbackConfig(
    val soundsEnabled: Boolean = true,
    val volume: Float = 0.6f,
    val style: SoundStyle = SoundStyle.MUFFLED,
    /** Pitch shift in semitones (-6..+6). */
    val pitch: Int = 0,
    val mutedSounds: Set<UiSound> = emptySet(),
    val hapticsEnabled: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.MEDIUM,
    val hapticOnFocus: Boolean = true,
)

/** Plays the synthesised sounds and drives the vibration motor of the platform. */
interface FeedbackOutput {
    /** Loads a WAV file; returns a handle for [play]. */
    fun load(file: File): Int
    fun unload(id: Int)
    fun play(id: Int, volume: Float)
    /** Short pulse for [sound] (no-op without a motor). */
    fun haptic(sound: UiSound, strength: HapticStrength)
}

/** UI sounds and haptics. Sounds are synthesised once into cache files, no assets needed. */
class UiFeedback(private val cacheDir: File, private val output: FeedbackOutput) {
    var config = FeedbackConfig()
        private set

    /** Applies new settings; sounds are re-synthesised only when timbre or pitch change. */
    fun configure(new: FeedbackConfig) {
        val resynth = new.style != config.style || new.pitch != config.pitch
        config = new
        if (resynth && ids.isNotEmpty()) load()
    }

    private val ids = HashMap<UiSound, Int>()
    private var lastFocusAt = 0L

    fun load() {
        val dir = File(cacheDir, "ui_sounds").apply { mkdirs() }
        val variant = "${config.style.name.lowercase()}_${config.pitch}"
        UiSound.entries.forEach { sound ->
            val file = File(dir, "${sound.name.lowercase()}_${variant}_v2.wav")
            if (!file.exists()) file.writeBytes(wav(synth(sound)))
            ids.put(sound, output.load(file))?.let { output.unload(it) }
        }
    }

    fun play(sound: UiSound) {
        if (sound == UiSound.FOCUS) {
            // Fast stick scrolling would otherwise turn into a buzz.
            val now = System.currentTimeMillis()
            if (now - lastFocusAt < 45) return
            lastFocusAt = now
        }
        val c = config
        if (c.soundsEnabled && sound !in c.mutedSounds) ids[sound]?.let { output.play(it, c.volume) }
        if (c.hapticsEnabled && (sound != UiSound.FOCUS || c.hapticOnFocus)) output.haptic(sound, c.hapticStrength)
    }

    /** Plays a sound regardless of its mute setting (preview in the settings). */
    fun preview(sound: UiSound) {
        ids[sound]?.let { output.play(it, config.volume) }
        if (config.hapticsEnabled) output.haptic(sound, config.hapticStrength)
    }

    private fun synth(sound: UiSound): ShortArray {
        val p = Math.pow(2.0, config.pitch / 12.0)
        fun f(vararg hz: Double) = hz.map { it * p }
        return when (sound) {
            UiSound.FOCUS -> tone(f(1800.0), 0.025, 0.18)
            UiSound.CONFIRM -> tone(f(880.0, 1320.0), 0.09, 0.35)
            UiSound.BACK -> tone(f(660.0, 440.0), 0.08, 0.3)
            UiSound.TOGGLE -> tone(f(1100.0), 0.05, 0.3)
            UiSound.ERROR -> tone(f(220.0, 180.0), 0.16, 0.4)
            UiSound.LAUNCH -> tone(f(523.25, 659.25, 783.99, 1046.5), 0.32, 0.4)
        }
    }

    /** Short sequence of decaying "plinks", one per frequency, evenly spread; timbre per style. */
    private fun tone(freqs: List<Double>, seconds: Double, amp: Double): ShortArray {
        val style = config.style
        val length = when (style) {
            SoundStyle.CLICK -> seconds * 0.6
            SoundStyle.GLASS -> seconds * 1.6
            SoundStyle.DEEP -> seconds * 1.3
            else -> seconds
        }
        val total = (SAMPLE_RATE * length).toInt()
        val per = total / freqs.size
        val noise = java.util.Random(7)
        // Small speakers hardly reproduce anything below ~140 Hz.
        val scaled = freqs.map { (it * style.scale).coerceAtLeast(140.0) }
        return ShortArray(total) { i ->
            val idx = (i / per).coerceAtMost(scaled.lastIndex)
            val t = (i - idx * per).toDouble() / SAMPLE_RATE
            val phase = 2 * PI * scaled[idx] * t
            // Pitch that starts higher and quickly falls to the base (knock/drop), integrated into the phase.
            fun drop(amount: Double, rate: Double) = 2 * PI * scaled[idx] * (t + amount / rate * (1 - exp(-t * rate)))
            val (wave, decay) = when (style) {
                SoundStyle.MUFFLED -> (sin(phase) * 0.85 + sin(phase * 2) * 0.15) to 42.0
                SoundStyle.WOOD -> drop(0.6, 70.0).let { sin(it) * 0.8 + sin(it * 2.3) * 0.2 } to 60.0
                SoundStyle.DEEP -> sin(drop(0.35, 30.0)) to 24.0
                SoundStyle.SOFT -> sin(phase) to 38.0
                SoundStyle.CLICK -> (sin(phase) * 0.6 + (noise.nextDouble() * 2 - 1) * 0.4) to 90.0
                SoundStyle.RETRO -> (if (sin(phase) >= 0) 0.55 else -0.55) to 22.0
                SoundStyle.GLASS -> (sin(phase) * 0.6 + sin(phase * 2.76) * 0.25 + sin(phase * 5.4) * 0.15) to 16.0
            }
            // The dull styles fade in a little slower so the onset doesn't click.
            val attack = if (style.scale < 1.0) 350.0 else 900.0
            val envelope = exp(-t * decay) * (1 - exp(-t * attack))
            (wave * envelope * amp * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun wav(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE = 44100
    }
}

val LocalUiFeedback = staticCompositionLocalOf<UiFeedback?> { null }
