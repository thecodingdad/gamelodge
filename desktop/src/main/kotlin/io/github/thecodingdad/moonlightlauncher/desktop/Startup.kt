package io.github.thecodingdad.moonlightlauncher.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** How the launcher starts and ends on the PC (desktop.json). */
@Serializable
data class StartupSettings(
    /** Starts when the user signs in to Windows (in the tray when [closeToTray] is on). */
    val startWithWindows: Boolean = false,
    /** Closing the window keeps the launcher running in the tray (servers and sync go on). */
    val closeToTray: Boolean = false,
)

/** Start with Windows (the user's Run key) and the tray behaviour. */
class Startup(dataDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(dataDir, "desktop.json")
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<StartupSettings> = _settings.asStateFlow()

    /** Run key entry for this copy, also when the launcher was moved (written on every start). */
    fun start() {
        if (_settings.value.startWithWindows) setAutostart(true)
    }

    fun update(change: (StartupSettings) -> StartupSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        if (next.startWithWindows != _settings.value.startWithWindows) setAutostart(next.startWithWindows)
        _settings.value = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
        }.onFailure { Log.w("Startup settings not saved", it) }
    }

    private fun load(): StartupSettings = runCatching { json.decodeFromString<StartupSettings>(file.readText()) }.getOrDefault(StartupSettings())

    private fun setAutostart(on: Boolean) {
        if (!isWindows) return
        runCatching {
            if (on) {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, Runner.launcherCommand(AUTOSTART_ARG))
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
            }
        }.onFailure { Log.w("Autostart not changed", it) }
    }

    companion object {
        const val AUTOSTART_ARG = "--autostart"
        private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val VALUE = "MoonlightLauncher"
    }
}

/**
 * One launcher per user: a second start (e.g. from the start menu while the first sits in the
 * tray) asks the first one to show its window and ends. The signal goes over a loopback port.
 */
object SingleInstance {
    private const val PORT = 47919
    private const val SHOW = "show"

    /** True: this is the first instance, [onShow] runs when another start asks for the window. False: told the running one. */
    fun claim(onShow: () -> Unit): Boolean {
        val server = runCatching { ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT)) } }.getOrNull()
        if (server == null) {
            val told = runCatching {
                Socket(InetAddress.getLoopbackAddress(), PORT).use { it.getOutputStream().write("$SHOW\n".toByteArray()) }
            }.isSuccess
            // Port taken by something else: start anyway.
            return !told
        }
        thread(isDaemon = true, name = "single-instance") {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    s.soTimeout = 2000
                    val line = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull()
                    if (line == SHOW) onShow()
                }
            }
        }
        return true
    }
}
