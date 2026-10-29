package io.github.thecodingdad.gamelodge

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** The launcher activity in the foreground (main window or quick menu), for starting other activities. */
object CurrentActivity : Application.ActivityLifecycleCallbacks {
    private var current = WeakReference<Activity>(null)

    private var resumed = WeakReference<Activity>(null)

    fun get(): Activity? = current.get()

    /** The GameLodge window in front right now (main window, quick menu or stream), null behind another app. */
    fun resumed(): Activity? = resumed.get()

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        resumed = WeakReference(activity)
        // The stream has its own mouse: GameLodge's pointer goes away when it comes to the front.
        if (activity is com.limelight.Game) io.github.thecodingdad.gamelodge.input.PointerMode.sync()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
        // ... and comes back when the stream leaves the front (if the emulation is still on).
        if (activity is com.limelight.Game) io.github.thecodingdad.gamelodge.input.PointerMode.sync()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // A new launcher window counts at once (a launch may come before its first resume).
        current = WeakReference(activity)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (current.get() === activity) current.clear()
        if (activity is com.limelight.Game) io.github.thecodingdad.gamelodge.ui.shell.ConnectionWarningOverlay.hide()
        // The stream ended, whatever comes to the front now (the quick menu that quit it, say): not
        // only once the launcher's own window is back. A newer stream (Game.instance) keeps it.
        if (activity is com.limelight.Game && activity.isFinishing && com.limelight.Game.instance.let { it == null || it === activity }) {
            activity.container.launcher.streamEnded()
        }
    }
}
