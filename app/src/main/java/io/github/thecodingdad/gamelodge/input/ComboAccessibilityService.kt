package io.github.thecodingdad.gamelodge.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

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

    override fun onKeyEvent(event: KeyEvent): Boolean =
        // The keyboard's quick menu shortcut, in every app.
        io.github.thecodingdad.gamelodge.ui.input.AndroidKeyboard.handleShortcut(event) { AndroidCombos.run(this, ComboAction.QUICK_MENU, -1) } ||
            ButtonCombos.handle(event) { action, controller -> AndroidCombos.run(this, action, controller) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile var instance: ComboAccessibilityService? = null
            private set

        /**
         * Opens this service's own page in the accessibility settings, where it is switched on,
         * where Android lets apps do that; otherwise the accessibility list. Returns false in that
         * case: the user then has to find "GameLodge combos" there (installed / downloaded apps).
         */
        fun openSettings(context: Context): Boolean {
            val component = android.content.ComponentName(context, ComboAccessibilityService::class.java).flattenToString()
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (details.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(details) }.isSuccess) return true
            // Usually only the system may open that page: the list then, with our entry highlighted
            // where the settings app supports it.
            val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", component) })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(list) }
            return false
        }

        /**
         * Android 13+ blocks accessibility services of apps not installed from an app store (the
         * service shows greyed out) until "Allow restricted settings" is chosen in the app info.
         */
        fun isRestricted(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 33) return false
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
            return runCatching {
                appOps.unsafeCheckOpNoThrow(OP_RESTRICTED_SETTINGS, android.os.Process.myUid(), context.packageName) !=
                    android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }

        /** GameLodge's app info, where its menu (⋮) holds "Allow restricted settings". */
        fun openAppInfo(context: Context) {
            val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(info) }
        }

        private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.endsWith(ComboAccessibilityService::class.java.name) || it.endsWith("/.input.ComboAccessibilityService") }
        }
    }
}
