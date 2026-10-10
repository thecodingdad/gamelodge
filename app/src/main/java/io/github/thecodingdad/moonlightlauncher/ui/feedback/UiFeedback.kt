package io.github.thecodingdad.moonlightlauncher.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class UiSound { FOCUS, CONFIRM, BACK, ERROR, LAUNCH, TOGGLE }

/** Timbre of the synthesised UI sounds. */
enum class SoundStyle { SOFT, CLICK, RETRO, GLASS }

enum class HapticStrength { LIGHT, MEDIUM, STRONG }

/** Everything the user can tune about sounds and vibration. */
data class FeedbackConfig(
    val soundsEnabled: Boolean = true,
    val volume: Float = 0.6f,
    val style: SoundStyle = SoundStyle.SOFT,
    /** Pitch shift in semitones (-6..+6). */
    val pitch: Int = 0,
    val mutedSounds: Set<UiSound> = emptySet(),
    val hapticsEnabled: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.MEDIUM,
    val hapticOnFocus: Boolean = true,
)

/** UI sounds and haptics. Sounds are synthesised once into cache files, no assets needed. */
class UiFeedback(private val context: Context) {
    var config = FeedbackConfig()
        private set

    /** Applies new settings; sounds are re-synthesised only when timbre or pitch change. */
    fun configure(new: FeedbackConfig) {
        val resynth = new.style != config.style || new.pitch != config.pitch
        config = new
        if (resynth && ids.isNotEmpty()) load()
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = HashMap<UiSound, Int>()
    private var lastFocusAt = 0L

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun load() {
        val dir = File(context.cacheDir, "ui_sounds").apply { mkdirs() }
        val variant = "${config.style.name.lowercase()}_${config.pitch}"
        UiSound.entries.forEach { sound ->
            val file = File(dir, "${sound.name.lowercase()}_${variant}_v2.wav")
            if (!file.exists()) file.writeBytes(wav(synth(sound)))
            ids.put(sound, pool.load(file.absolutePath, 1))?.let { pool.unload(it) }
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
        if (c.soundsEnabled && sound !in c.mutedSounds) ids[sound]?.let { pool.play(it, c.volume, c.volume, 0, 0, 1f) }
        if (c.hapticsEnabled && (sound != UiSound.FOCUS || c.hapticOnFocus)) haptic(sound)
    }

    /** Plays a sound regardless of its mute setting (preview in the settings). */
    fun preview(sound: UiSound) {
        ids[sound]?.let { pool.play(it, config.volume, config.volume, 0, 0, 1f) }
        if (config.hapticsEnabled) haptic(sound)
    }

    private fun haptic(sound: UiSound) {
        val v = vibrator ?: return
        // Durations per event; strength scales amplitude where the motor supports it.
        val millis = when (sound) {
            UiSound.FOCUS -> 8L
            UiSound.CONFIRM, UiSound.TOGGLE -> 16L
            UiSound.BACK -> 10L
            UiSound.LAUNCH -> 40L
            UiSound.ERROR -> 60L
        }
        val effect = if (v.hasAmplitudeControl()) {
            val amplitude = when (config.hapticStrength) {
                HapticStrength.LIGHT -> 60
                HapticStrength.MEDIUM -> 140
                HapticStrength.STRONG -> 255
            }
            if (sound == UiSound.ERROR) {
                VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), intArrayOf(0, amplitude, 0, amplitude), -1)
            } else {
                VibrationEffect.createOneShot(millis, amplitude)
            }
        } else {
            val strong = config.hapticStrength == HapticStrength.STRONG
            VibrationEffect.createPredefined(
                when (sound) {
                    UiSound.FOCUS -> VibrationEffect.EFFECT_TICK
                    UiSound.ERROR -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    UiSound.LAUNCH -> VibrationEffect.EFFECT_HEAVY_CLICK
                    else -> if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                },
            )
        }
        runCatching { v.vibrate(effect) }
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
        val length = if (style == SoundStyle.CLICK) seconds * 0.6 else if (style == SoundStyle.GLASS) seconds * 1.6 else seconds
        val total = (SAMPLE_RATE * length).toInt()
        val per = total / freqs.size
        val noise = java.util.Random(7)
        return ShortArray(total) { i ->
            val idx = (i / per).coerceAtMost(freqs.lastIndex)
            val t = (i - idx * per).toDouble() / SAMPLE_RATE
            val phase = 2 * PI * freqs[idx] * t
            val (wave, decay) = when (style) {
                SoundStyle.SOFT -> sin(phase) to 38.0
                SoundStyle.CLICK -> (sin(phase) * 0.6 + (noise.nextDouble() * 2 - 1) * 0.4) to 90.0
                SoundStyle.RETRO -> (if (sin(phase) >= 0) 0.55 else -0.55) to 22.0
                SoundStyle.GLASS -> (sin(phase) * 0.6 + sin(phase * 2.76) * 0.25 + sin(phase * 5.4) * 0.15) to 16.0
            }
            val envelope = exp(-t * decay) * (1 - exp(-t * 900))
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
