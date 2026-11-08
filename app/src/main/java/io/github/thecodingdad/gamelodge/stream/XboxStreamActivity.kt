package io.github.thecodingdad.gamelodge.stream

import android.annotation.SuppressLint
import android.app.Activity
import io.github.thecodingdad.gamelodge.resources.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.input.AndroidCombos
import io.github.thecodingdad.gamelodge.input.handle
import io.github.thecodingdad.gamelodge.util.Log
import io.github.thecodingdad.gamelodge.xbox.XboxPadState
import io.github.thecodingdad.gamelodge.xbox.XboxStreamController
import io.github.thecodingdad.gamelodge.xbox.XboxStreamState
import io.github.thecodingdad.gamelodge.xbox.XboxStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Plays an Xbox home stream: the player page (xstreaming-player, assets/xbox-player) in a WebView,
 * the controllers from here (the page never reads them itself, so focus never matters).
 * The session was set up by the [LaunchCoordinator]; it ends when this window closes.
 *
 * Guide and the launcher's button combinations work as in PC streams (Guide: back to GameLodge,
 * hold Start: quick menu); View + Menu together press the Xbox button on the console.
 */
class XboxStreamActivity : androidx.activity.ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var web: WebView
    private lateinit var progress: android.view.View
    private var stream: XboxStreamController? = null
    private var everConnected = false

    /** Controllers by their slot on the console (first input takes the next free one, up to four). */
    private val slots = LinkedHashMap<Int, Int>()
    private val pads = HashMap<Int, Pad>()

    private class Pad(val index: Int) {
        val buttons = HashSet<Int>()
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f; var lt = 0f; var rt = 0f
        var hatX = 0f; var hatY = 0f

        fun state(): XboxPadState {
            val view = KeyEvent.KEYCODE_BUTTON_SELECT in buttons
            val menu = KeyEvent.KEYCODE_BUTTON_START in buttons
            // View + Menu together: the Xbox button on the console (the Guide stays GameLodge's).
            val nexus = view && menu
            return XboxPadState(
                index = index,
                a = KeyEvent.KEYCODE_BUTTON_A in buttons, b = KeyEvent.KEYCODE_BUTTON_B in buttons,
                x = KeyEvent.KEYCODE_BUTTON_X in buttons, y = KeyEvent.KEYCODE_BUTTON_Y in buttons,
                lb = KeyEvent.KEYCODE_BUTTON_L1 in buttons, rb = KeyEvent.KEYCODE_BUTTON_R1 in buttons,
                view = view && !nexus, menu = menu && !nexus, nexus = nexus,
                leftThumb = KeyEvent.KEYCODE_BUTTON_THUMBL in buttons, rightThumb = KeyEvent.KEYCODE_BUTTON_THUMBR in buttons,
                up = KeyEvent.KEYCODE_DPAD_UP in buttons || hatY < -0.5f,
                down = KeyEvent.KEYCODE_DPAD_DOWN in buttons || hatY > 0.5f,
                left = KeyEvent.KEYCODE_DPAD_LEFT in buttons || hatX < -0.5f,
                right = KeyEvent.KEYCODE_DPAD_RIGHT in buttons || hatX > 0.5f,
                lx = lx, ly = ly, rx = rx, ry = ry,
                lt = maxOf(lt, if (KeyEvent.KEYCODE_BUTTON_L2 in buttons) 1f else 0f),
                rt = maxOf(rt, if (KeyEvent.KEYCODE_BUTTON_R2 in buttons) 1f else 0f),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = XboxStreams.current
        if (controller == null) {
            finish()
            return
        }
        stream = controller
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(false)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            webChromeClient = WebChromeClient()
            // The page's controllers come from here: it must not take the keys itself.
            isFocusable = false
            isFocusableInTouchMode = false
            addJavascriptInterface(Bridge(), "GameLodgeBridge")
        }
        // The launcher's start screen (artwork, step) until the stream's first frame is there.
        val entry = container.launcher.streamingEntry
        progress = if (entry == null) ProgressBar(this).apply { isIndeterminate = true } else androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                io.github.thecodingdad.gamelodge.util.ActivityLocale {
                    val settings by container.settings.settings.collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(settings) {
                        io.github.thecodingdad.gamelodge.ui.shell.StreamProgressContent(
                            entry = entry,
                            step = org.jetbrains.compose.resources.stringResource(Res.string.step_connecting_stream),
                            detail = null,
                            timerKey = Unit,
                            showTimer = true,
                            onCancel = { finish() },
                        )
                    }
                }
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val full = progress !is ProgressBar
            addView(progress, if (full) FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            else FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        }
        setContentView(root)
        hideSystemBars()

        controller.send = { text -> main.post { if (!isDestroyed) web.evaluateJavascript("window.gameLodgeReceive(${JSONObject.quote(text)})", null) } }
        controller.onRumble = { index, strong, weak, ms -> main.post { rumble(index, strong, weak, ms) } }
        scope.launch {
            controller.state.collect { state ->
                when (state) {
                    XboxStreamState.Connected -> everConnected = true
                    is XboxStreamState.Failed -> {
                        if (!everConnected) container.launcher.reportFailure(LaunchError.START_FAILED, state.message)
                        finish()
                    }
                    XboxStreamState.Ended -> finish()
                    XboxStreamState.Connecting -> Unit
                }
            }
        }
        scope.launch { controller.live.collect { if (it) progress.visibility = View.GONE } }
        web.loadUrl("file:///android_asset/xbox-player/index.html")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(text: String) {
            stream?.onMessage(text)
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------- controllers

    private fun isPad(device: InputDevice?): Boolean = device != null &&
        (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    private fun pad(deviceId: Int): Pad? {
        slots[deviceId]?.let { return pads[it] }
        val free = (0 until MAX_PADS).firstOrNull { it !in slots.values } ?: return null
        slots[deviceId] = free
        return Pad(free).also { pads[free] = it }
    }

    // RestrictedApi: lint takes the super call for androidx.core's restricted overload (it's Activity's).
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isPad(event.device) && !KeyEvent.isGamepadButton(event.keyCode)) {
            // Back (touch gesture, remote) leaves the stream.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) finish()
            return event.keyCode == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(event)
        }
        // GameLodge's combinations first (Guide: back to GameLodge, hold Start: quick menu).
        if (io.github.thecodingdad.gamelodge.input.ButtonCombos.handle(event) { action, controller -> AndroidCombos.run(this, action, controller) }) return true
        // While the start screen shows, B cancels the start (as on the launcher's start screen).
        if (progress.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) return true
        val p = pad(event.deviceId) ?: return true
        val changed = when (event.action) {
            KeyEvent.ACTION_DOWN -> p.buttons.add(event.keyCode)
            KeyEvent.ACTION_UP -> p.buttons.remove(event.keyCode)
            else -> false
        }
        if (changed) sendPads()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isPad(event.device) || event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        val p = pad(event.deviceId) ?: return true
        val device = event.device
        fun axis(a: Int): Float {
            val range = device?.getMotionRange(a, event.source) ?: return 0f
            val v = event.getAxisValue(a)
            return if (kotlin.math.abs(v) <= range.flat) 0f else v
        }
        p.lx = axis(MotionEvent.AXIS_X); p.ly = axis(MotionEvent.AXIS_Y)
        p.rx = axis(MotionEvent.AXIS_Z); p.ry = axis(MotionEvent.AXIS_RZ)
        p.lt = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)).coerceIn(0f, 1f)
        p.rt = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)).coerceIn(0f, 1f)
        p.hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X); p.hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        sendPads()
        return true
    }

    private fun sendPads() {
        val s = stream ?: return
        if (pads.isEmpty()) return
        s.pads(pads.values.map { it.state() })
    }

    private fun rumble(index: Int, strong: Float, weak: Float, durationMs: Int) {
        if (durationMs <= 0) return
        val deviceId = slots.entries.firstOrNull { it.value == index }?.key ?: slots.keys.firstOrNull() ?: return
        val device = InputDevice.getDevice(deviceId) ?: return
        val amplitude = (maxOf(strong, weak) * 255).toInt().coerceIn(0, 255)
        if (amplitude == 0) return
        val effect = VibrationEffect.createOneShot(durationMs.toLong().coerceAtMost(2_000), amplitude)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) device.vibratorManager.defaultVibrator.vibrate(effect)
            else @Suppress("DEPRECATION") device.vibrator.vibrate(effect)
        }.onFailure { Log.w("Rumble: ${it.message}") }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        val s = stream
        if (s != null && isFinishing) {
            s.end()
            if (XboxStreams.current === s) XboxStreams.current = null
            container.launcher.streamEnded()
        }
        if (::web.isInitialized) {
            web.removeJavascriptInterface("GameLodgeBridge")
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val MAX_PADS = 4

        fun start(context: Context) {
            context.startActivity(Intent(context, XboxStreamActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
        }

        /** Ends the running Xbox stream (quick menu): its window closes, the session ends. */
        fun finishRunning() {
            XboxStreams.current?.end()
        }
    }
}
