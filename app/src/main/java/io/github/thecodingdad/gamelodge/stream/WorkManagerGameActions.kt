package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.ha.DelayedActions
import io.github.thecodingdad.gamelodge.util.Log
import java.util.concurrent.TimeUnit

/** Delayed game actions through WorkManager: on time even while the launcher is frozen in the background. */
class WorkManagerGameActions(private val context: Context) : DelayedActions {
    override fun schedule(action: String, delaySeconds: Int, endOfGame: Boolean) {
        val request = OneTimeWorkRequestBuilder<GameActionWorker>()
            .setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_ACTION to action))
            .addTag(if (endOfGame) TAG_END else TAG_START)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    override fun cancelEndOfGame() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_END)
    }

    companion object {
        const val KEY_ACTION = "action"
        private const val TAG_END = "game_action_end"
        private const val TAG_START = "game_action_start"
    }
}

class GameActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val action = inputData.getString(WorkManagerGameActions.KEY_ACTION) ?: return Result.failure()
        val ok = runCatching { applicationContext.container.ha.runAction(action) }.getOrDefault(false)
        if (!ok) Log.w("Game action $action failed")
        return if (ok) Result.success() else Result.failure()
    }
}
