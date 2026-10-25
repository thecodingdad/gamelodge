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
 * GameLodge's own mouse emulation outside the stream: a pointer above every app, moved with the
 * left stick. A taps, X long-presses, B goes back, the right stick and the D-pad scroll. Taps and
 * swipes are gestures of the accessibility service (Android only lets services inject them); the
 * pointer window takes the controller's input while it's on, so the app below doesn't see it.
 */
object PointerMode {
    private var view: PointerView? = null

    val active: Boolean get() = view != null

    fun toggle(context: Context) {
        if (view != null) stop(context) else start(context)
    }

    private fun start(context: Context) {
        val service = ComboAccessibilityService.instance
        if (service == null) {
            Toast.makeText(context, R.string.pointer_needs_service, Toast.LENGTH_LONG).show()
            return
        }
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
        Toast.makeText(context, R.string.pointer_on, Toast.LENGTH_SHORT).show()
    }

    fun stop(context: Context? = null) {
        val pointer = view ?: return
        view = null
        pointer.release()
        runCatching { pointer.context.getSystemService(WindowManager::class.java).removeView(pointer) }
        context?.let { Toast.makeText(it, R.string.pointer_off, Toast.LENGTH_SHORT).show() }
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
                x = (x + curve(moveX) * SPEED_DP * density * dt).coerceIn(0f, w - 1)
                y = (y + curve(moveY) * SPEED_DP * density * dt).coerceIn(0f, h - 1)
                invalidate()
            }
            if (!scrolling && (abs(scrollX) > 0.3f || abs(scrollY) > 0.3f)) scroll(scrollX, scrollY)
            Choreographer.getInstance().postFrameCallback(this)
        }

        /** Fine control near the centre, full speed at the edge. */
        private fun curve(v: Float): Float = v * v * v.sign

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
