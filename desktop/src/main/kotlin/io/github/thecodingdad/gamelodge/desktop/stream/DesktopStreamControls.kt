package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.data.remote.HostActions
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.stream.StreamControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The stream runs in Moonlight-Qt, a process of its own: steered from outside. */
class DesktopStreamControls(private val hostActions: HostActions) : StreamControls {
    private val running get() = MoonlightQt.locate().running?.takeIf { it.process.isAlive }

    override fun disconnect() {
        running?.process?.let(::end)
    }

    /** Moonlight-Qt goes first, so it can't report the ended app as a broken connection. */
    override suspend fun quit(host: HostState): Boolean = withContext(Dispatchers.IO) {
        val r = running?.takeIf { it.hostUuid == host.uuid } ?: return@withContext false
        end(r.process)
        r.process.waitFor(3, TimeUnit.SECONDS)
        hostActions.quitApp(host)
        true
    }

    private fun end(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }
}
