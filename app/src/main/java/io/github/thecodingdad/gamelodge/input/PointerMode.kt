package io.github.thecodingdad.gamelodge.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import io.github.thecodingdad.gamelodge.R
import kotlin.math.abs
import kotlin.math.sign

/**
 * GameLodge's mouse emulation. One state for everything: in the stream it's the stream's mouse
 * (Artemis), elsewhere a pointer above every app, moved with the left stick. A taps, X
 * long-presses, B goes back, the right stick and the D-pad scroll. Taps and swipes are gestures of
 * the accessibility service (Android only lets services inject them); the pointer window takes the
 * controller's input while it's on, so the app below doesn't see it. Over GameLodge's own windows
 * the pointer hovers like a mouse.
 */
object PointerMode {
    private var view: PointerView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Mouse emulation is on (stream or pointer, whichever is in front). */
    @Volatile var enabled = false
        private set

    /** Pointer speed factor (1 = default) and curve exponent, from the settings. */
    @Volatile var speed = 1f
    @Volatile var exponent = 2.4f

    private val streamInFront: Boolean get() = io.github.thecodingdad.gamelodge.CurrentActivity.resumed() is com.limelight.Game

    /** Switches the mouse emulation; [context] shows the notice. */
    fun toggle(context: Context) {
        val on = !enabled
        if (on && !streamInFront && ComboAccessibilityService.instance == null) {
            Toast.makeText(context, R.string.pointer_needs_service, Toast.LENGTH_LONG).show()
            return
        }
        enabled = on
        com.limelight.StreamControl.setMouseEmulation(on)
        sync()
        Toast.makeText(context, if (on) R.string.pointer_on else R.string.pointer_off, Toast.LENGTH_SHORT).show()
    }

    /** Shows the pointer while the emulation is on and no stream is in front (that has its own). */
    fun sync() {
        main.post {
            if (enabled && !streamInFront) start() else stop()
        }
    }

    private fun start() {
        if (view != null) return
        val service = ComboAccessibilityService.instance ?: return
        val wm = service.getSystemService(WindowManager::class.java)
        val pointer = PointerView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(pointer, params) }.onFailure { return }
        pointer.requestFocus()
        view = pointer
    }

    private fun stop() {
        val pointer = view ?: return
        view = null
        pointer.release()
        runCatching { pointer.context.getSystemService(WindowManager::class.java).removeView(pointer) }
    }

    private class PointerView(private val service: AccessibilityService) : View(service), Choreographer.FrameCallback {
        private val density = resources.displayMetrics.density
        private var x = resources.displayMetrics.widthPixels / 2f
        private var y = resources.displayMetrics.heightPixels / 2f
        private var moveX = 0f
        private var moveY = 0f
        private var scrollX = 0f
        private var scrollY = 0f
        private var lastFrame = 0L
        private var scrolling = false
        private var released = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path().apply {
            val s = density
            moveTo(0f, 0f); lineTo(0f, 20 * s); lineTo(5 * s, 15 * s); lineTo(9 * s, 23 * s)
            lineTo(12 * s, 22 * s); lineTo(8 * s, 14 * s); lineTo(15 * s, 14 * s); close()
        }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun release() {
            released = true
            Choreographer.getInstance().removeFrameCallback(this)
            hover(MotionEvent.ACTION_HOVER_EXIT)
        }

        private var hoverTarget: android.app.Activity? = null

        /** Mouse hover over GameLodge's own windows (other apps can't be given hover events). */
        private fun hover(action: Int) {
            val activity = io.github.thecodingdad.gamelodge.CurrentActivity.resumed()?.takeIf { it !is com.limelight.Game }
            if (activity !== hoverTarget) {
                hoverTarget?.let { send(it, MotionEvent.ACTION_HOVER_EXIT) }
                hoverTarget = activity
                if (action != MotionEvent.ACTION_HOVER_EXIT) activity?.let { send(it, MotionEvent.ACTION_HOVER_ENTER) }
            }
            activity?.let { send(it, action) }
            if (action == MotionEvent.ACTION_HOVER_EXIT) hoverTarget = null
        }

        private fun send(activity: android.app.Activity, action: Int) {
            val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
            val now = android.os.SystemClock.uptimeMillis()
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x - origin[0]; this.y = y - origin[1] })
            val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            runCatching { activity.dispatchGenericMotionEvent(event) }
            event.recycle()
        }

        override fun onDraw(canvas: Canvas) {
            val origin = IntArray(2).also { getLocationOnScreen(it) }
            canvas.save()
            canvas.translate(x - origin[0], y - origin[1])
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
            canvas.restore()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (released) return
            val dt = if (lastFrame == 0L) 0f else ((frameTimeNanos - lastFrame) / 1e9f).coerceAtMost(0.05f)
            lastFrame = frameTimeNanos
            if (moveX != 0f || moveY != 0f) {
                val w = resources.displayMetrics.widthPixels.toFloat()
                val h = resources.displayMetrics.heightPixels.toFloat()
                // Along the stick's direction; slow near the centre, full speed at the edge.
                val magnitude = kotlin.math.hypot(moveX, moveY).coerceAtMost(1f)
                val step = SPEED_DP * density * speed * Math.pow(magnitude.toDouble(), exponent.toDouble()).toFloat() * dt / magnitude
                x = (x + moveX * step).coerceIn(0f, w - 1)
                y = (y + moveY * step).coerceIn(0f, h - 1)
                invalidate()
                hover(MotionEvent.ACTION_HOVER_MOVE)
            }
            if (!scrolling && (abs(scrollX) > 0.3f || abs(scrollY) > 0.3f)) scroll(scrollX, scrollY)
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun deadzone(v: Float) = if (abs(v) < DEADZONE) 0f else v

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
                return super.onGenericMotionEvent(event)
            }
            moveX = deadzone(event.getAxisValue(MotionEvent.AXIS_X))
            moveY = deadzone(event.getAxisValue(MotionEvent.AXIS_Y))
            scrollX = deadzone(event.getAxisValue(MotionEvent.AXIS_Z))
            scrollY = deadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
            // A D-pad reported as hat axes scrolls like the right stick.
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hatX != 0f || hatY != 0f) {
                scrollX = hatX
                scrollY = hatY
            }
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (event.repeatCount > 0) return isPointerKey(keyCode)
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> tap(TAP_MS)
                KeyEvent.KEYCODE_BUTTON_X -> tap(LONG_PRESS_MS)
                KeyEvent.KEYCODE_BUTTON_B -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_DPAD_UP -> scroll(0f, -1f)
                KeyEvent.KEYCODE_DPAD_DOWN -> scroll(0f, 1f)
                KeyEvent.KEYCODE_DPAD_LEFT -> scroll(-1f, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> scroll(1f, 0f)
                else -> return false
            }
            return true
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = isPointerKey(keyCode)

        private fun isPointerKey(keyCode: Int) = keyCode in setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        private fun tap(duration: Long) {
            val path = Path().apply { moveTo(x, y) }
            service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
        }

        /** One swipe at the pointer, against the direction (content follows the finger); the next once it's done. */
        private fun scroll(dx: Float, dy: Float) {
            val distance = SCROLL_DP * density
            val w = resources.displayMetrics.widthPixels.toFloat()
            val h = resources.displayMetrics.heightPixels.toFloat()
            val endX = (x - dx.sign * distance * (abs(dx) > 0.3f).toFloat()).coerceIn(0f, w - 1)
            val endY = (y - dy.sign * distance * (abs(dy) > 0.3f).toFloat()).coerceIn(0f, h - 1)
            if (endX == x && endY == y) return
            val path = Path().apply { moveTo(x, y); lineTo(endX, endY) }
            scrolling = true
            val done = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { scrolling = false }
                override fun onCancelled(gestureDescription: GestureDescription?) { scrolling = false }
            }
            val sent = service.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_MS)).build(), done, null,
            )
            if (!sent) scrolling = false
        }

        private fun Boolean.toFloat() = if (this) 1f else 0f
    }

    private const val SPEED_DP = 900f
    private const val DEADZONE = 0.15f
    private const val TAP_MS = 40L
    private const val LONG_PRESS_MS = 700L
    private const val SCROLL_DP = 140f
    private const val SCROLL_MS = 120L
}
