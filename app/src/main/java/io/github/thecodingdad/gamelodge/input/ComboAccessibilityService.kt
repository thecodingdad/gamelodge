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

    override fun onKeyEvent(event: KeyEvent): Boolean = ButtonCombos.handle(event) { action ->
        when (action) {
            ComboAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            // In the launcher: its own quick menu; anywhere else: the overlay above the app.
            ComboAction.QUICK_MENU ->
                if (MainActivity.resumed) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_QUICK_MENU, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                } else {
                    QuickMenuActivity.toggle(this)
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile var instance: ComboAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.endsWith(ComboAccessibilityService::class.java.name) || it.endsWith("/.input.ComboAccessibilityService") }
        }
    }
}
