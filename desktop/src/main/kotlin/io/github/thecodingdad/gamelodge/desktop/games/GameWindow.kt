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
    fun focus(pids: Set<Long>): Boolean {
        if (!isWindows || pids.isEmpty()) return false
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
        return best?.let { WindowsForeground.claim(it) } != null
    }
}
