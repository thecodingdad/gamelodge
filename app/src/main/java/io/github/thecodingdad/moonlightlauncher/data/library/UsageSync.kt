package io.github.thecodingdad.moonlightlauncher.data.library

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.PlaySessionEntity
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Play time of local apps and games from Android's usage statistics (needs "usage access").
 * With access the launcher no longer times local starts itself: Android also sees use that
 * didn't start from the launcher. Without it [PlaytimeTracker] stays in charge.
 */
class UsageSync(private val context: Context, private val db: LauncherDatabase) {
    private val prefs = context.getSharedPreferences("usage_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    val granted: Boolean
        get() {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }

    /** Imports what Android recorded since the last run. Call when the launcher comes back. */
    suspend fun sync() = mutex.withLock {
        if (!granted) return@withLock
        withContext(Dispatchers.IO) { runCatching { syncNow() } }
    }

    private suspend fun syncNow() {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        // Package → its local launch target (and through it the library item).
        val targets = db.libraryDao().getAllTargets()
            .filter { it.type == TargetType.LOCAL && it.packageName != null && it.packageName != context.packageName }
            .associateBy { it.packageName!! }
        if (targets.isEmpty()) return
        val since = prefs.getLong(KEY_SYNCED, 0L)
        if (since == 0L) {
            // First run: Android's totals so far (up to about two years) instead of replaying
            // old events, without double counting what the launcher measured itself.
            usm.queryAndAggregateUsageStats(0, now).forEach { (pkg, stats) ->
                val target = targets[pkg] ?: return@forEach
                val item = db.libraryDao().getItem(target.itemId) ?: return@forEach
                val seconds = stats.totalTimeInForeground / 1000
                if (seconds > item.playtimeSeconds) db.libraryDao().addPlaytime(item.id, seconds - item.playtimeSeconds)
                if (stats.lastTimeUsed > 0) db.libraryDao().setLastPlayedIfNewer(item.id, stats.lastTimeUsed)
            }
            prefs.edit().putLong(KEY_SYNCED, now).apply()
            return
        }

        // Foreground periods per package from resume/pause events.
        val periods = HashMap<String, MutableList<LongArray>>()
        val open = HashMap<String, Long>()
        val events = usm.queryEvents(since, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pkg !in targets) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open.putIfAbsent(pkg, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> open.remove(pkg)?.let { start ->
                    val list = periods.getOrPut(pkg) { mutableListOf() }
                    val last = list.lastOrNull()
                    // Switching between activities of the same app is one session.
                    if (last != null && start - last[1] < MERGE_GAP_MS) last[1] = event.timeStamp
                    else list += longArrayOf(start, event.timeStamp)
                }
            }
        }
        periods.forEach { (pkg, list) ->
            val target = targets.getValue(pkg)
            list.filter { it[1] - it[0] >= MIN_SESSION_MS }.forEach { (start, end) ->
                db.sessionDao().insert(PlaySessionEntity(itemId = target.itemId, targetId = target.id, startedAt = start, endedAt = end))
                db.libraryDao().addPlaytime(target.itemId, (end - start) / 1000)
                db.libraryDao().setLastPlayedIfNewer(target.itemId, start)
            }
        }
        // A still open period is picked up complete by the next run.
        prefs.edit().putLong(KEY_SYNCED, open.values.minOrNull() ?: now).apply()
    }

    private companion object {
        const val KEY_SYNCED = "synced_until"
        const val MERGE_GAP_MS = 60_000L
        const val MIN_SESSION_MS = 60_000L
    }
}
