package io.github.thecodingdad.moonlightlauncher.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.thecodingdad.moonlightlauncher.MainActivity

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
            ComboAction.QUICK_MENU -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_QUICK_MENU, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
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
