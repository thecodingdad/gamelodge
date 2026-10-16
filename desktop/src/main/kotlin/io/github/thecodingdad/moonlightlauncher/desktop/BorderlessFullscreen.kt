package io.github.thecodingdad.moonlightlauncher.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Frame

/**
 * Full screen like games do it on Windows: the window loses its frame and title bar and covers
 * the whole monitor (taskbar included). Compose's own full screen keeps the title bar of a
 * decorated window. Leaving restores the frame and maximizes the window again.
 */
object BorderlessFullscreen {
    private const val FRAME_STYLES = WinUser.WS_CAPTION or WinUser.WS_THICKFRAME or WinUser.WS_MINIMIZEBOX or WinUser.WS_MAXIMIZEBOX or WinUser.WS_SYSMENU

    @Volatile var active = false
        private set
    private var savedStyle = 0

    fun enter(window: Frame) {
        if (!isWindows || active) return
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        val user32 = User32.INSTANCE
        // A maximized window keeps its maximized frame: restore it first.
        user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        savedStyle = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, savedStyle and FRAME_STYLES.inv())
        val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        user32.GetMonitorInfo(monitor, info)
        val r = info.rcMonitor
        user32.SetWindowPos(
            hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top,
            WinUser.SWP_FRAMECHANGED or WinUser.SWP_SHOWWINDOW,
        )
        active = true
    }

    fun exit(window: Frame) {
        if (!isWindows || !active) return
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        val user32 = User32.INSTANCE
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, savedStyle)
        user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER)
        user32.ShowWindow(hwnd, WinUser.SW_MAXIMIZE)
        active = false
    }
}
