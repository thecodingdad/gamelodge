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

/** UI sounds and haptics. Sounds are synthesised once into cache files, no assets needed. */
class UiFeedback(private val context: Context) {
    var soundsEnabled = true
    var volume = 0.6f
    var hapticsEnabled = true

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
        UiSound.entries.forEach { sound ->
            val file = File(dir, "${sound.name.lowercase()}_v1.wav")
            if (!file.exists()) file.writeBytes(wav(synth(sound)))
            ids[sound] = pool.load(file.absolutePath, 1)
        }
    }

    fun play(sound: UiSound) {
        if (sound == UiSound.FOCUS) {
            // Fast stick scrolling would otherwise turn into a buzz.
            val now = System.currentTimeMillis()
            if (now - lastFocusAt < 45) return
            lastFocusAt = now
        }
        if (soundsEnabled) ids[sound]?.let { pool.play(it, volume, volume, 0, 0, 1f) }
        if (hapticsEnabled) haptic(sound)
    }

    private fun haptic(sound: UiSound) {
        val effect = when (sound) {
            UiSound.FOCUS -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            UiSound.CONFIRM, UiSound.TOGGLE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            UiSound.LAUNCH -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            UiSound.ERROR -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            UiSound.BACK -> return
        }
        runCatching { vibrator?.vibrate(effect) }
    }

    private fun synth(sound: UiSound): ShortArray = when (sound) {
        UiSound.FOCUS -> tone(listOf(1800.0), 0.025, 0.18)
        UiSound.CONFIRM -> tone(listOf(880.0, 1320.0), 0.09, 0.35)
        UiSound.BACK -> tone(listOf(660.0, 440.0), 0.08, 0.3)
        UiSound.TOGGLE -> tone(listOf(1100.0), 0.05, 0.3)
        UiSound.ERROR -> tone(listOf(220.0, 180.0), 0.16, 0.4)
        UiSound.LAUNCH -> tone(listOf(523.25, 659.25, 783.99, 1046.5), 0.32, 0.4)
    }

    /** Short sequence of decaying sine "plinks", one per frequency, evenly spread. */
    private fun tone(freqs: List<Double>, seconds: Double, amp: Double): ShortArray {
        val total = (SAMPLE_RATE * seconds).toInt()
        val per = total / freqs.size
        return ShortArray(total) { i ->
            val idx = (i / per).coerceAtMost(freqs.lastIndex)
            val t = (i - idx * per).toDouble() / SAMPLE_RATE
            val envelope = exp(-t * 38) * (1 - exp(-t * 900))
            (sin(2 * PI * freqs[idx] * t) * envelope * amp * Short.MAX_VALUE).toInt().toShort()
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
