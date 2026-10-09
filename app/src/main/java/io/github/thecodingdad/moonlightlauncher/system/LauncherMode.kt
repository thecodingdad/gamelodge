package io.github.thecodingdad.moonlightlauncher.system

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Optional home-screen mode. The HOME intent filter lives on a disabled activity-alias, so
 * the app only appears as a launcher choice after the user switched launcher mode on.
 */
class LauncherMode(private val context: Context) {
    private val alias = ComponentName(context, "${context.packageName}.HomeAlias")
    private val roleManager = context.getSystemService(RoleManager::class.java)

    val isAvailable: Boolean
        get() = context.packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    val isDefaultHome: Boolean
        get() = roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true

    fun setAvailable(enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** System dialog asking to make us the default home app (Android 10+). */
    fun requestDefaultHomeIntent(): Intent? =
        roleManager?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }?.createRequestRoleIntent(RoleManager.ROLE_HOME)

    /** Android's own "default home app" settings, used to switch back to another launcher. */
    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
