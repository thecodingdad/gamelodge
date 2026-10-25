package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.data.remote.HostActions
import io.github.thecodingdad.gamelodge.data.remote.HostRepository
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.stream.StreamControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The stream runs in Moonlight-Qt: steered through its launcher control channel (stdin), with
 * ending the process as the fallback.
 */
class DesktopStreamControls(private val hostActions: HostActions, private val hosts: HostRepository) : StreamControls {
    private val client get() = MoonlightQt.locate()
    private val running get() = client.running?.takeIf { it.process.isAlive }

    override fun stats(): Flow<String> = flow {
        running?.send("stats on")
        try {
            emitAll(client.stats)
        } finally {
            running?.send("stats off")
        }
    }

    override fun serverCommands(): List<String> = running?.let { hosts.hosts.value[it.hostUuid]?.serverCommands }.orEmpty()

    override fun runServerCommand(index: Int) {
        running?.send("servercmd $index")
    }

    override fun disconnect() {
        val r = running ?: return
        Thread({
            if (!r.send("disconnect") || !r.process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) end(r.process)
        }, "disconnect").start()
    }

    /** Moonlight-Qt's window is the one in front (it reads the pads then). */
    override fun inFront(): Boolean {
        val r = running ?: return false
        val pids = (listOf(r.process.toHandle()) + r.process.descendants().toList()).map { it.pid() }.toSet()
        return runCatching {
            val window = com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow() ?: return false
            val pid = com.sun.jna.ptr.IntByReference()
            com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(window, pid)
            pid.value.toLong() in pids
        }.getOrDefault(false)
    }

    override fun toggleMouse(controller: Int): Boolean = inFront() && running?.send("mouse") == true

    override fun toggleOverlay(): Boolean = running?.send("overlay") == true

    /** Moonlight-Qt quits the app itself (as its own "quit" does): no "connection ended" error. */
    override suspend fun quit(host: HostState): Boolean = withContext(Dispatchers.IO) {
        val r = running?.takeIf { it.hostUuid == host.uuid } ?: return@withContext false
        if (!r.send("quit") || !r.process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
            // An older Moonlight-Qt without the channel: close it first, then quit the app here.
            end(r.process)
            r.process.waitFor(3, TimeUnit.SECONDS)
            hostActions.quitApp(host)
        }
        true
    }

    private fun end(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }

    private companion object {
        const val GRACE_SECONDS = 10L
    }
}
