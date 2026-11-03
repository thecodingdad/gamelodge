package io.github.thecodingdad.gamelodge

import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.thecodingdad.gamelodge.system.TvSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The handheld's screen while the launcher runs on the TV: plain black at the lowest brightness
 * (practically off on OLED), kept awake so the TV output continues. Input that reaches it anyway
 * (the handheld's display had the focus) goes on to the launcher on the TV, which gets the focus
 * back. It must stay focusable: a resumed activity without a focusable window makes Android wait
 * for one with every key and report the app as not responding.
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // Keys and controller sticks: to the launcher on the TV (which takes the focus again).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val tv = TvSession.launcherOnTv ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) TvSession.focusTv(this)
        return tv.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val tv = TvSession.launcherOnTv ?: return super.dispatchGenericMotionEvent(event)
        return tv.dispatchGenericMotionEvent(event)
    }

    // Touching the black screen does nothing (and doesn't wake anything up).
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = true

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }
}
