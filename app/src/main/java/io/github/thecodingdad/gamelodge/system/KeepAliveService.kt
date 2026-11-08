package io.github.thecodingdad.gamelodge.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.thecodingdad.gamelodge.R
import io.github.thecodingdad.gamelodge.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Short foreground service that keeps the app from being frozen while a task waits for the user
 * in another app (pairing). Its notification shows the newest task's text (e.g. the PIN).
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // The type is in the manifest; lint misses it once the app has product flavors.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = holds.values.lastOrNull()
        if (text == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_quick_menu)
            .setContentTitle(getString(R.string.launcher_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(ID, notification)
            }
        }.onFailure { Log.w("Keep-alive service not started", it); stopSelf() }
        return START_NOT_STICKY
    }

    /** A short service runs at most about three minutes (the pairing's own limit as well). */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "background_tasks"
        private const val ID = 4711
        private val holds = ConcurrentHashMap<String, String>()

        /** Keeps the app awake with a notification showing [text] until the returned handle closes. */
        fun hold(context: Context, tag: String, text: String): AutoCloseable {
            holds.remove(tag)
            holds[tag] = text
            val app = context.applicationContext
            runCatching { app.startForegroundService(Intent(app, KeepAliveService::class.java)) }
                .onFailure { Log.w("Keep-alive service not started", it) }
            return AutoCloseable {
                holds.remove(tag)
                if (holds.isEmpty()) app.stopService(Intent(app, KeepAliveService::class.java))
                else runCatching { app.startForegroundService(Intent(app, KeepAliveService::class.java)) }
            }
        }
    }
}
