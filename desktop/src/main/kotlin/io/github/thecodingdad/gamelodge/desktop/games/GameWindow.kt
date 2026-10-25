package io.github.thecodingdad.gamelodge.desktop.games

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import io.github.thecodingdad.gamelodge.desktop.WindowsForeground
import io.github.thecodingdad.gamelodge.desktop.isWindows

/** The main window of a running game. */
object GameWindow {
    /** Brings the largest visible top-level window of [pids] to the front; false when there is none. */
    fun focus(pids: Set<Long>): Boolean = mainWindow(pids)?.let { WindowsForeground.claim(it) } != null

    /** The largest visible own top-level window of [pids], if any. */
    fun mainWindow(pids: Set<Long>): WinDef.HWND? {
        if (!isWindows || pids.isEmpty()) return null
        val user32 = User32.INSTANCE
        var best: WinDef.HWND? = null
        var bestArea = 0L
        user32.EnumWindows({ hwnd: WinDef.HWND, _: Pointer? ->
            val pid = IntByReference()
            user32.GetWindowThreadProcessId(hwnd, pid)
            // Own windows only, no tool windows or ones owned by another (dialogs come along anyway).
            if (pid.value.toLong() in pids && user32.IsWindowVisible(hwnd) && user32.GetWindow(hwnd, WinDef.DWORD(WinUser.GW_OWNER.toLong())) == null) {
                val r = WinDef.RECT()
                user32.GetWindowRect(hwnd, r)
                val area = (r.right - r.left).toLong() * (r.bottom - r.top)
                if (area > bestArea) {
                    best = hwnd
                    bestArea = area
                }
            }
            true
        }, null)
        return best
    }

    /**
     * Quits a game the way its own close button would (WM_CLOSE to its visible windows), and
     * ends the processes still there after [graceMillis].
     */
    suspend fun quit(pids: () -> Set<Long>, graceMillis: Long = 10_000) {
        val first = pids()
        if (first.isEmpty()) return
        if (isWindows) {
            val user32 = User32.INSTANCE
            user32.EnumWindows({ hwnd: WinDef.HWND, _: Pointer? ->
                val pid = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pid)
                if (pid.value.toLong() in first && user32.IsWindowVisible(hwnd)) {
                    user32.PostMessage(hwnd, WinUser.WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0))
                }
                true
            }, null)
        }
        val until = System.currentTimeMillis() + graceMillis
        while (System.currentTimeMillis() < until && pids().any { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }) {
            kotlinx.coroutines.delay(250)
        }
        pids().forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }
}
