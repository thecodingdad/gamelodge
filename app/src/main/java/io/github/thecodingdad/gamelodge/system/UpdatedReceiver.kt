package io.github.thecodingdad.gamelodge.system

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After an update of GameLodge as the home app, Android leaves the recent apps on screen (the old
 * home process is gone): the home screen comes back by itself.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !isHome(context)) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun isHome(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(RoleManager::class.java)?.let { return it.isRoleHeld(RoleManager.ROLE_HOME) }
        }
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return home?.activityInfo?.packageName == context.packageName
    }
}
