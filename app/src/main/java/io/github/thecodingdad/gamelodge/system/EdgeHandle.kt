package io.github.thecodingdad.gamelodge.system

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.thecodingdad.gamelodge.MainActivity
import io.github.thecodingdad.gamelodge.QuickMenuActivity

/**
 * Optional narrow handle at the right screen edge, above every app (also the stream): a tap
 * opens the quick menu. Needs "display over other apps". Hidden while the launcher or the
 * quick menu itself is in front (the launcher has the clock and Y for that).
 */
object EdgeHandle {
    private var view: View? = null
    private var enabled = false

    /** Applies the setting; call again after the overlay permission may have changed. */
    fun update(context: Context, enabled: Boolean) {
        this.enabled = enabled
        refresh(context)
    }

    /** Re-evaluates permission and visibility (launcher / quick menu in front or not). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val wanted = enabled && Settings.canDrawOverlays(app)
        if (!wanted) {
            remove(app)
            return
        }
        val handle = view ?: add(app) ?: return
        handle.visibility = if (MainActivity.resumed || QuickMenuActivity.resumed) View.GONE else View.VISIBLE
    }

    private fun add(context: Context): View? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val density = context.resources.displayMetrics.density
        val handle = HandleView(context).apply {
            contentDescription = "Quick menu"
            setOnClickListener { QuickMenuActivity.toggle(context, touch = true) }
        }
        val params = WindowManager.LayoutParams(
            (22 * density).toInt(), (110 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        return runCatching { wm.addView(handle, params) }.map { handle }.getOrNull()?.also { view = it }
    }

    private fun remove(context: Context) {
        val handle = view ?: return
        view = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(handle) }
    }

    /** Touch area wider than the visible bar: a thin, rounded pill hugging the edge. */
    private class HandleView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = 5 * density
            rect.set(width - w - 3 * density, 8 * density, width - 3 * density, height - 8 * density)
            canvas.drawRoundRect(rect, w / 2, w / 2, fill)
            canvas.drawRoundRect(rect, w / 2, w / 2, outline)
        }
    }
}
