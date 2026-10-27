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
 * card; if the game is still running [TIMEOUT_MS] after the quit returned, a notice says so.
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
                // The host may answer only once the game has closed.
                runCatching { kotlinx.coroutines.withTimeoutOrNull(QUIT_CALL_MS) { quit() } }
                val until = System.currentTimeMillis() + TIMEOUT_MS
                var nextRefresh = 0L
                while (running() && System.currentTimeMillis() < until) {
                    if (System.currentTimeMillis() >= nextRefresh) {
                        runCatching { refresh() }
                        nextRefresh = System.currentTimeMillis() + REFRESH_MS
                    }
                    delay(POLL_MS)
                }
                if (running()) {
                    Notices.show(Res.string.qm_quit_failed, title)
                    // It may still close: the cards show what the host says by then.
                    runCatching { refresh() }
                } else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
                }
            } finally {
                BackgroundTasks.remove(taskId)
                _keys.update { it - key }
            }
        }
    }

    fun hostKey(host: io.github.thecodingdad.gamelodge.data.remote.HostState) = "host:${host.uuid}"

    /**
     * Quits the game running on [host] ([title] for the task): our own stream through its client
     * (no "connection ended" error there), otherwise through the host.
     */
    fun quitHost(
        c: io.github.thecodingdad.gamelodge.LauncherContainer,
        host: io.github.thecodingdad.gamelodge.data.remote.HostState,
        title: String,
        onDone: () -> Unit = {},
    ) {
        val uuid = host.uuid
        start(
            c.scope, hostKey(host), title,
            quit = { if (!c.streamControls.quit(host)) c.hostActions.quitApp(host) },
            running = { c.hosts.hosts.value[uuid]?.isStreaming == true || c.launcher.activeStream.value?.hostUuid == uuid },
            refresh = { c.hosts.invalidate(uuid) },
            onDone = onDone,
        )
    }

    /** Longest wait for the quit itself (the host closing the game), then for the state to follow. */
    private const val QUIT_CALL_MS = 90_000L
    private const val TIMEOUT_MS = 20_000L
    private const val REFRESH_MS = 2_000L
    private const val POLL_MS = 300L
}
