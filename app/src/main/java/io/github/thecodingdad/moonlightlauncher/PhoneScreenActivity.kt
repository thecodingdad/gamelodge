package io.github.thecodingdad.moonlightlauncher

import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.thecodingdad.moonlightlauncher.system.TvSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The handheld's screen while the launcher runs on the TV: plain black at the lowest brightness
 * (practically off on OLED), kept awake so the TV output continues. It never takes the input
 * focus, so a controller keeps driving the launcher on the TV.
 */
class PhoneScreenActivity : ComponentActivity() {
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            // TV unplugged: the launcher comes back to this screen by itself.
            if (!TvSession.active) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        // Launcher back on the handheld (setting off, TV unplugged): done here.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (!TvSession.active) finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening this screen moved the input focus here: hand it back to the TV.
        TvSession.focusTv(this)
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }
}
