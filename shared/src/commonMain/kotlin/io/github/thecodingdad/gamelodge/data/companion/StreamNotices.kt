package io.github.thecodingdad.gamelodge.data.companion

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Tells the GameLodge on a streaming host's PC (when paired) that this launcher streams from it:
 * the PC then leaves Guide to this launcher, and on a resume brings the game's window to the front.
 * Repeated while the stream runs; the PC forgets it on its own when the repetitions stop.
 */
class StreamNotices(private val servers: ServerRepository, private val scope: CoroutineScope) {
    private val json = Json { encodeDefaults = true }
    private var heartbeat: Job? = null
    private var current: PairedServer? = null

    /** Before the stream starts: the first notice is awaited briefly, so a resume finds the game in front. */
    suspend fun started(hostUuid: String, resume: Boolean) {
        val server = servers.companionFor(hostUuid)
        // Another PC: that one is told the stream ended. The same PC: no "stopped" that could
        // overtake the new notice.
        if (current?.id != server?.id) stopped() else heartbeat?.cancel()
        server ?: return
        current = server
        withTimeoutOrNull(3_000) { send(server, StreamNotice(active = true, resume = resume)) }
        heartbeat = scope.launch {
            while (isActive) {
                delay(30_000)
                send(server, StreamNotice(active = true))
            }
        }
    }

    /** The stream ended (or never started). */
    fun stopped() {
        heartbeat?.cancel()
        heartbeat = null
        val server = current ?: return
        current = null
        scope.launch { send(server, StreamNotice(active = false)) }
    }

    private suspend fun send(server: PairedServer, notice: StreamNotice) = withContext(Dispatchers.IO) {
        runCatching {
            servers.client(server).rawPost("/v1/stream", json.encodeToString(StreamNotice.serializer(), notice), "application/json").close()
        }.onFailure { Log.d("Stream notice to ${server.name} failed: ${it.message}") }
    }
}
