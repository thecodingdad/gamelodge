package io.github.thecodingdad.moonlightlauncher.desktop.games

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinUser
import io.github.thecodingdad.moonlightlauncher.desktop.isWindows
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Starts PC games like the companion's runner did: a game EXE is started and waited for; a store
 * URI goes to the store, then the game's processes (by folder or name) are followed until it ends.
 */
object GameLauncher {
    /**
     * Starts [entry] and returns once the game has ended (or can't be followed). [started] learns
     * right after the start attempt whether anything started.
     */
    suspend fun play(entry: GameEntry, processes: ProcessSource = SystemProcesses, started: (Boolean) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val launch = entry.launch?.takeIf { it.isNotBlank() }
        if (launch == null) {
            started(false)
            return@withContext false
        }
        val processDir = entry.processDir ?: entry.installDir
        val matcher = GameMatcher(processDir, entry.processNames)
        val watcher = ProcessWatcher(processes, matcher)
        val isFile = File(launch).isAbsolute && File(launch).exists()
        // The game itself (game EXE, emulator): wait for that process. A store client or URI:
        // watch the game's folder / names instead.
        val direct = isFile && (processDir == null || WinPath.isUnder(launch, processDir) || entry.source in setOf("emulator", "custom", "gog"))
        Log.i("Starting ${entry.name} (${entry.id}): $launch${if (direct) " (direct)" else ""}")
        if (direct) {
            val exit = runAndWait(launch, entry.launchArgs, entry.workingDir ?: File(launch).parent, started) ?: return@withContext false
            Log.d("${entry.name}: process exited ($exit)")
            // Launch EXEs that hand over to the real game: keep waiting while it runs.
            if (matcher.hasCriteria && watcher.running().isNotEmpty()) watcher.waitForExit()
            return@withContext true
        }
        val opened = open(launch, entry.launchArgs)
        started(opened)
        if (!opened) return@withContext false
        if (!matcher.hasCriteria) {
            Log.w("${entry.name}: no folder or process names known – can't follow the game")
            return@withContext true
        }
        // Stores may update the game first; give them a while.
        if (!watcher.waitForStart(5 * 60_000L)) {
            Log.w("${entry.name}: the game did not start within 5 minutes")
            return@withContext true
        }
        watcher.waitForExit()
        Log.i("${entry.name}: ended")
        true
    }

    /** Opens the store's install page for an owned game; [isInstalled] is asked until it says yes (up to 8 h). */
    suspend fun install(entry: GameEntry, isInstalled: suspend () -> Boolean): Boolean {
        val uri = entry.install?.takeIf { it.isNotBlank() } ?: return false
        if (!withContext(Dispatchers.IO) { open(uri, null) }) return false
        Log.i("Installing ${entry.name} through its store")
        val deadline = System.currentTimeMillis() + 8 * 3_600_000L
        while (System.currentTimeMillis() < deadline) {
            delay(20_000)
            if (isInstalled()) {
                Log.i("${entry.name}: installed")
                return true
            }
        }
        return true
    }

    /** Hands a URI, shell path or program (with a raw argument string) to Windows. */
    private fun open(target: String, args: String?): Boolean {
        if (!isWindows) return runCatching { Desktop.getDesktop().browse(URI(target)); true }.getOrDefault(false)
        val info = execute(target, args, null, wait = false) ?: return false
        info.hProcess?.let { Kernel32.INSTANCE.CloseHandle(it) }
        return true
    }

    /** Starts a program and waits for it; its exit code, null when it didn't start. */
    private fun runAndWait(program: String, args: String?, workingDir: String?, started: (Boolean) -> Unit): Int? {
        if (!isWindows) {
            val p = runCatching { ProcessBuilder(listOf(program) + args.orEmpty().split(' ').filter { it.isNotBlank() }).directory(workingDir?.let(::File)).start() }.getOrNull()
            started(p != null)
            return p?.waitFor()
        }
        val info = execute(program, args, workingDir, wait = true)
        started(info != null)
        if (info == null) return null
        val handle = info.hProcess ?: return 0
        try {
            Kernel32.INSTANCE.WaitForSingleObject(handle, WinBase.INFINITE)
            val code = com.sun.jna.ptr.IntByReference()
            Kernel32.INSTANCE.GetExitCodeProcess(handle, code)
            return code.value
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private fun execute(file: String, args: String?, dir: String?, wait: Boolean): ShellAPI.SHELLEXECUTEINFO? {
        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            fMask = if (wait) SEE_MASK_NOCLOSEPROCESS else SEE_MASK_NOCLOSEPROCESS or SEE_MASK_FLAG_NO_UI
            lpVerb = "open"
            lpFile = file
            lpParameters = args
            lpDirectory = dir
            nShow = WinUser.SW_SHOWNORMAL
        }
        return if (Shell32.INSTANCE.ShellExecuteEx(info)) info else {
            Log.w("Windows could not start $file (error ${Kernel32.INSTANCE.GetLastError()})")
            null
        }
    }

    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
    private const val SEE_MASK_FLAG_NO_UI = 0x00000400
}
