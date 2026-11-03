package io.github.thecodingdad.gamelodge.system

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Home-screen mode. The app is always one of the home apps Android offers (HOME filter on an
 * activity-alias); launcher mode is on exactly while it is the default home app.
 */
class LauncherMode(private val context: Context) {
    private val alias = ComponentName(context, "${context.packageName}.HomeAlias")
    private val roleManager = context.getSystemService(RoleManager::class.java)

    val isDefaultHome: Boolean
        get() = roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /**
     * Earlier versions switched the home alias off while launcher mode was off: back to the
     * manifest's state (always offered as a home app).
     */
    fun ensureOffered() {
        val pm = context.packageManager
        if (pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }

    /** System dialog asking to make us the default home app (Android 10+). */
    fun requestDefaultHomeIntent(): Intent? =
        roleManager?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }?.createRequestRoleIntent(RoleManager.ROLE_HOME)

    /** Android's own "default home app" settings, used to switch back to another launcher. */
    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
