package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Windows only lets the app with the last user input take the foreground; a controller button
 * read through SDL doesn't count, so toFront() would just flash the taskbar button. Sharing the
 * input state with the window in front for a moment lifts that.
 */
object WindowsForeground {
    private const val WS_MINIMIZE = 0x20000000

    fun claim(window: Window) {
        runCatching {
            val user32 = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            val front = user32.GetForegroundWindow()
            if (front == hwnd) return
            if (user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WS_MINIMIZE != 0) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
            val frontThread = front?.let { user32.GetWindowThreadProcessId(it, null) } ?: 0
            val ownThread = Kernel32.INSTANCE.GetCurrentThreadId()
            val attached = frontThread != 0 && frontThread != ownThread && user32.AttachThreadInput(WinDef.DWORD(ownThread.toLong()), WinDef.DWORD(frontThread.toLong()), true)
            try {
                user32.BringWindowToTop(hwnd)
                user32.SetForegroundWindow(hwnd)
            } finally {
                if (attached) user32.AttachThreadInput(WinDef.DWORD(ownThread.toLong()), WinDef.DWORD(frontThread.toLong()), false)
            }
        }.onFailure { io.github.thecodingdad.gamelodge.util.Log.w("Bringing the window to the front failed", it) }
    }
}
