package io.github.thecodingdad.gamelodge.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.thecodingdad.gamelodge.MainActivity
import io.github.thecodingdad.gamelodge.QuickMenuActivity

/**
 * Optional system-wide button combinations: filters controller key events in every app (also
 * in the stream) and runs the configured action. Only sees key events, nothing on screen.
 */
class ComboAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = ButtonCombos.handle(event) { run(this, it) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile var instance: ComboAccessibilityService? = null
            private set

        /**
         * Runs a combination's action from outside the launcher activity (this service, the stream):
         * GameLodge comes to the front (in it: to the start tab), the quick menu opens in the
         * launcher or as the overlay above another app.
         */
        fun run(context: Context, action: ComboAction) {
            val launcher = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            when (action) {
                ComboAction.HOME -> context.startActivity(launcher.putExtra(MainActivity.EXTRA_HOME, true))
                ComboAction.QUICK_MENU ->
                    if (MainActivity.resumed) context.startActivity(launcher.putExtra(MainActivity.EXTRA_QUICK_MENU, true))
                    else QuickMenuActivity.toggle(context)
            }
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.endsWith(ComboAccessibilityService::class.java.name) || it.endsWith("/.input.ComboAccessibilityService") }
        }
    }
}
