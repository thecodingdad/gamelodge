package io.github.thecodingdad.moonlightlauncher.desktop

import java.io.File

/**
 * "Delete all data" on the PC. While the launcher runs its database, settings and log files are
 * open (and locked on Windows), so the request is only noted; the next start deletes the data
 * folder and Moonlight-Qt's settings (pairing identity) before anything opens them.
 */
object DataReset {
    private const val MARKER = "reset.pending"

    fun request(dataDir: File) {
        File(dataDir, MARKER).writeText("")
        // The Run key would otherwise keep starting the launcher with Windows.
        Startup(dataDir).update { it.copy(startWithWindows = false) }
    }

    /** At start, before the data folder is used: carries out a requested reset. */
    fun runIfRequested(dataDir: File) {
        if (!File(dataDir, MARKER).exists()) return
        dataDir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { File(io.github.thecodingdad.moonlightlauncher.desktop.stream.MoonlightQt.locate().dir, "Moonlight Game Streaming Project").deleteRecursively() }
    }
}
