package io.github.thecodingdad.gamelodge.system

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import io.github.thecodingdad.gamelodge.MainActivity
import io.github.thecodingdad.gamelodge.PhoneScreenActivity
import java.lang.ref.WeakReference

/**
 * Launcher on an attached TV: instead of mirroring the handheld, the launcher's activity runs on
 * the TV's own display (native resolution) and the handheld shows a black screen. Falls back to
 * mirroring when the device doesn't allow apps on the external display.
 */
object TvSession {
    private const val TAG = "TvSession"

    /** The launcher while it is shown on the TV; target of the handheld's input. */
    private var tvActivity: WeakReference<Activity>? = null

    /** The device refused to start us on this display: mirror instead. */
    private var failedDisplay: Int? = null

    fun displayIdOf(activity: Activity): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.displayId

    fun isOnTv(activity: Activity) = displayIdOf(activity) != Display.DEFAULT_DISPLAY

    /** Called by the launcher whenever the TV or the setting changes. */
    fun update(activity: Activity, tv: ExternalDisplayInfo?, enabled: Boolean) {
        if (tv == null) {
            failedDisplay = null
            tvActivity = null
            return
        }
        val onTv = isOnTv(activity)
        when {
            !enabled && onTv -> startOn(activity, MainActivity::class.java, Display.DEFAULT_DISPLAY, null)
            enabled && !onTv && tv.id != failedDisplay ->
                if (!startOn(activity, MainActivity::class.java, tv.id, Rect(0, 0, tv.width, tv.height))) failedDisplay = tv.id
        }
    }

    /** The launcher arrived on the TV (or is shown there again): full screen, mode, handheld side. */
    fun onShownOnTv(activity: Activity, modeId: Int) {
        val arrived = tvActivity?.get() !== activity
        tvActivity = WeakReference(activity)
        requestFullscreen(activity)
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = modeId }
        // The handheld side only when arriving, not on every resume (the user may use other apps there).
        if (arrived) startOn(activity, PhoneScreenActivity::class.java, Display.DEFAULT_DISPLAY, null)
    }

    /** Desktop modes (Samsung DeX, Android desktop windowing) open a window: make it full screen. */
    private fun requestFullscreen(activity: Activity) {
        Log.i(TAG, "Full screen? sdk=${Build.VERSION.SDK_INT} multiWindow=${activity.isInMultiWindowMode}")
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            activity.requestFullscreenMode(
                Activity.FULLSCREEN_MODE_REQUEST_ENTER,
                object : android.os.OutcomeReceiver<Void, Throwable> {
                    override fun onResult(result: Void?) { Log.i(TAG, "Full screen granted") }
                    override fun onError(error: Throwable) { Log.w(TAG, "Full screen refused: ${error.message}") }
                },
            )
        }.onFailure { Log.w(TAG, "Full screen request failed: ${it.message}") }
    }

    fun onLeftTv(activity: Activity) {
        if (tvActivity?.get() === activity) tvActivity = null
    }

    val active: Boolean get() = tvActivity?.get() != null

    /** Gives the TV display the input focus (the launcher only handles keys while focused). */
    fun focusTv(context: Context) {
        val activity = tvActivity?.get() ?: return
        // Bring the existing task forward; starting the activity again would re-apply launch
        // options and the desktop mode would turn the full screen launcher back into a window.
        runCatching { context.getSystemService(android.app.ActivityManager::class.java)?.moveTaskToFront(activity.taskId, 0) }
            .onFailure { Log.w(TAG, "Focus to TV failed: ${it.message}") }
        // Only the focused window may go full screen: ask again once the focus is back there.
        activity.window.decorView.postDelayed({ requestFullscreen(activity) }, 300)
    }

    private fun startOn(context: Context, target: Class<out Activity>, displayId: Int, bounds: Rect?): Boolean {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (bounds != null) options.setLaunchBounds(bounds)
        return runCatching {
            context.startActivity(Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        }.onFailure { Log.w(TAG, "Start on display $displayId refused: ${it.message}") }.isSuccess
    }
}
