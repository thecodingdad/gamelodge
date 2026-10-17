package io.github.thecodingdad.gamelodge.ui.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File

/** UI sounds through a SoundPool, haptics through the device's vibrator. */
class AndroidFeedbackOutput(context: Context) : FeedbackOutput {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    override fun load(file: File): Int = pool.load(file.absolutePath, 1)

    override fun unload(id: Int) {
        pool.unload(id)
    }

    override fun play(id: Int, volume: Float) {
        pool.play(id, volume, volume, 0, 0, 1f)
    }

    override fun haptic(sound: UiSound, strength: HapticStrength) {
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
            val amplitude = when (strength) {
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
            val strong = strength == HapticStrength.STRONG
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
}
