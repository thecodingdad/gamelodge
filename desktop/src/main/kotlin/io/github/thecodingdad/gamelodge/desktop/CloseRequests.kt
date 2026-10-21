package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.github.thecodingdad.gamelodge.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An invisible window for the runner: the streaming host ends an app by sending WM_CLOSE to the
 * windows of its processes, and without a window the runner would never hear of it (the host
 * then kills only the runner, the game keeps running).
 */
object CloseRequests {
    private const val CLASS = "GameLodgeRunner"
    private val handled = AtomicBoolean(false)
    // Referenced here so the callback isn't garbage collected while the window lives.
    private var proc: WinUser.WindowProc? = null

    /** Calls [onClose] (once, on its own thread) when the window is asked to close. */
    fun listen(onClose: () -> Unit) {
        if (!isWindows) return
        Thread({ loop(onClose) }, "close-requests").apply { isDaemon = true }.start()
    }

    private fun loop(onClose: () -> Unit) {
        runCatching {
            val user32 = User32.INSTANCE
            val instance = Kernel32.INSTANCE.GetModuleHandle(null)
            val callback = WinUser.WindowProc { hwnd, msg, wParam, lParam ->
                if (msg == WinUser.WM_CLOSE) {
                    if (handled.compareAndSet(false, true)) Thread(onClose, "close-game").start()
                    WinDef.LRESULT(0)
                } else {
                    user32.DefWindowProc(hwnd, msg, wParam, lParam)
                }
            }
            proc = callback
            user32.RegisterClassEx(WinUser.WNDCLASSEX().apply { hInstance = instance; lpfnWndProc = callback; lpszClassName = CLASS })
            user32.CreateWindowEx(0, CLASS, "GameLodge game runner", 0, 0, 0, 0, 0, null, null, instance, null)
                ?: error("CreateWindowEx failed (${Kernel32.INSTANCE.GetLastError()})")
            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                user32.TranslateMessage(msg)
                user32.DispatchMessage(msg)
            }
        }.onFailure { Log.w("Runner: close requests unavailable", it) }
    }
}
