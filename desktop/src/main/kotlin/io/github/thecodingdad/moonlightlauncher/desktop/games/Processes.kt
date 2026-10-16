package io.github.thecodingdad.moonlightlauncher.desktop.games

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import io.github.thecodingdad.moonlightlauncher.desktop.isWindows
import kotlinx.coroutines.delay

data class ProcessInfo(val id: Long, val name: String, val path: String?)

/** Running processes (abstracted for tests). */
fun interface ProcessSource {
    fun snapshot(): List<ProcessInfo>
}

/** All processes with their image path; the path of elevated games comes from Windows itself. */
object SystemProcesses : ProcessSource {
    override fun snapshot(): List<ProcessInfo> = ProcessHandle.allProcesses().use { stream ->
        stream.toList().mapNotNull { p ->
            val path = p.info().command().orElse(null) ?: if (isWindows) imagePath(p.pid()) else null
            val name = path?.let(WinPath::fileName) ?: return@mapNotNull null
            ProcessInfo(p.pid(), name, path)
        }
    }

    private fun imagePath(pid: Long): String? {
        val handle = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return null
        try {
            val buffer = CharArray(1024)
            val size = IntByReference(buffer.size)
            return if (Kernel32.INSTANCE.QueryFullProcessImageName(handle, 0, buffer, size)) String(buffer, 0, size.value) else null
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
}

/**
 * Decides which processes belong to a game: anything running from its folder or with one of its
 * executable names. Store launchers and helpers outside the folder are ignored.
 */
class GameMatcher(private val processDir: String?, private val processNames: List<String>) {
    val hasCriteria get() = !processDir.isNullOrEmpty() || processNames.isNotEmpty()

    fun matches(p: ProcessInfo): Boolean {
        if (ignored.any { it.equals(p.name, ignoreCase = true) }) return false
        if (processNames.any { it.equals(p.name, ignoreCase = true) }) return true
        return !processDir.isNullOrEmpty() && p.path != null && WinPath.isUnder(p.path, processDir)
    }

    private companion object {
        val ignored = setOf(
            "UnityCrashHandler64.exe", "UnityCrashHandler32.exe", "CrashReportClient.exe", "crashpad_handler.exe",
            "EasyAntiCheat.exe", "EasyAntiCheat_EOS.exe", "BEService.exe", "steamwebhelper.exe",
        )
    }
}

/** Waits for a game's processes to appear and to end. */
class ProcessWatcher(
    private val source: ProcessSource,
    private val matcher: GameMatcher,
    private val pollMs: Long = 2_000,
    /** The game must be gone this long (launchers restart processes during start-up). */
    private val graceMs: Long = 8_000,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    fun running(): List<ProcessInfo> = source.snapshot().filter(matcher::matches)

    /** True when a game process showed up within [timeoutMs]. */
    suspend fun waitForStart(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (running().isNotEmpty()) return true
            wait(pollMs)
            waited += pollMs
        }
        return false
    }

    /** Returns once no game process has been seen for the grace period. */
    suspend fun waitForExit() {
        var gone = 0L
        while (gone < graceMs) {
            wait(pollMs)
            gone = if (running().isNotEmpty()) 0 else gone + pollMs
        }
    }
}
