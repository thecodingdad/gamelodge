package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask
import io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.shell.Notices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Games and streams being quit. Quitting takes a moment (the host closes the game, the stream
 * ends): meanwhile it's a background task (status bar) and the quick menu shows it on the game's
 * card; if the game is still running after [TIMEOUT_MS] a notice says so.
 */
object Quitting {
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    /**
     * Quits [title] (key [key], e.g. "host:<uuid>"): runs [quit], then waits until [running] is
     * false ([refresh] asks for a fresh state now and then) and calls [onDone] on success.
     */
    fun start(
        scope: CoroutineScope,
        key: String,
        title: String,
        quit: suspend () -> Unit,
        running: () -> Boolean,
        refresh: suspend () -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        if (key in _keys.value) return
        _keys.update { it + key }
        val taskId = "quit:$key"
        BackgroundTasks.set(BackgroundTask(taskId, Res.string.task_quitting, listOf(title)))
        scope.launch {
            try {
                runCatching { quit() }
                val until = System.currentTimeMillis() + TIMEOUT_MS
                var nextRefresh = 0L
                while (running() && System.currentTimeMillis() < until) {
                    if (System.currentTimeMillis() >= nextRefresh) {
                        runCatching { refresh() }
                        nextRefresh = System.currentTimeMillis() + REFRESH_MS
                    }
                    delay(POLL_MS)
                }
                if (running()) Notices.show(Res.string.qm_quit_failed, title)
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
            } finally {
                BackgroundTasks.remove(taskId)
                _keys.update { it - key }
            }
        }
    }

    private const val TIMEOUT_MS = 20_000L
    private const val REFRESH_MS = 2_000L
    private const val POLL_MS = 300L
}
