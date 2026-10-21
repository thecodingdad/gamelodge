package io.github.thecodingdad.gamelodge.desktop.games

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import io.github.thecodingdad.gamelodge.desktop.isWindows
import io.github.thecodingdad.gamelodge.util.Log

/** Ends a game the way a player would: close its windows (like Alt+F4), and only if that fails, hard. */
object GameCloser {
    /**
     * Asks the processes from [processes] (and their child processes) to close; whatever still runs
     * after [graceMs] is ended hard. [processes] is asked again while waiting (launchers hand over).
     */
    fun close(processes: () -> Set<Long>, graceMs: Long = 15_000) {
        val first = withChildren(processes())
        if (first.isEmpty()) return
        Log.i("Closing the game (${first.size} processes)")
        requestClose(first)
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            if (alive(withChildren(processes())).isEmpty()) {
                Log.i("The game closed")
                return
            }
        }
        val left = alive(withChildren(processes()))
        if (left.isEmpty()) return
        Log.w("The game did not close within ${graceMs / 1000} s: ending ${left.size} processes")
        left.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    private fun withChildren(pids: Set<Long>): Set<Long> = pids.flatMapTo(HashSet()) { pid ->
        listOf(pid) + ProcessHandle.of(pid).map { h -> h.descendants().use { s -> s.map { it.pid() }.toList() } }.orElse(emptyList())
    }

    private fun alive(pids: Set<Long>): List<Long> = pids.filter { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }

    /** WM_CLOSE to the visible top-level windows of [pids] (all of their windows when none is visible). */
    private fun requestClose(pids: Set<Long>) {
        if (!isWindows) {
            pids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }
            return
        }
        val user32 = User32.INSTANCE
        val visible = ArrayList<WinDef.HWND>()
        val hidden = ArrayList<WinDef.HWND>()
        user32.EnumWindows({ hwnd: WinDef.HWND, _: Pointer? ->
            val pid = IntByReference()
            user32.GetWindowThreadProcessId(hwnd, pid)
            if (pid.value.toLong() in pids) (if (user32.IsWindowVisible(hwnd)) visible else hidden) += hwnd
            true
        }, null)
        (visible.ifEmpty { hidden }).forEach { user32.PostMessage(it, WinUser.WM_CLOSE, null, null) }
    }
}
