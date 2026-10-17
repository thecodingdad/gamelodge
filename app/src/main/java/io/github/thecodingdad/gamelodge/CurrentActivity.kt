package io.github.thecodingdad.gamelodge

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** The launcher activity in the foreground (main window or quick menu), for starting other activities. */
object CurrentActivity : Application.ActivityLifecycleCallbacks {
    private var current = WeakReference<Activity>(null)

    fun get(): Activity? = current.get()

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // A new launcher window counts at once (a launch may come before its first resume).
        current = WeakReference(activity)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (current.get() === activity) current.clear()
    }
}
