package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.ui.input.key.nativeKeyCode
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import io.github.thecodingdad.gamelodge.input.KeyShortcut
import io.github.thecodingdad.gamelodge.util.Log
import io.github.thecodingdad.gamelodge.input.KeyboardKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The system-wide quick menu shortcut on Windows: a hotkey (RegisterHotKey), so it works with a
 * game or stream in front and doesn't reach them. Always with Ctrl / Alt / Shift (Windows hotkeys
 * without one would take the key from every app).
 */
object WindowsHotkey {
    private const val ID = 1
    private const val MOD_ALT = 0x1
    private const val MOD_CONTROL = 0x2
    private const val MOD_SHIFT = 0x4
    private const val MOD_NOREPEAT = 0x4000

    private var current: KeyShortcut? = null
    private var threadId = 0

    /** Registers [shortcut] (null = none); [onPress] runs on the hotkey thread. */
    @Synchronized
    fun set(shortcut: KeyShortcut?, onPress: () -> Unit) {
        if (shortcut == current) return
        stop()
        current = shortcut
        if (shortcut == null || !shortcut.hasModifier) return
        val vk = KeyboardKeys.shortcutKeys.firstOrNull { it.first == shortcut.key }?.second?.nativeKeyCode ?: return
        val mods = (if (shortcut.ctrl) MOD_CONTROL else 0) or (if (shortcut.alt) MOD_ALT else 0) or
            (if (shortcut.shift) MOD_SHIFT else 0) or MOD_NOREPEAT
        val started = CountDownLatch(1)
        Thread({
            // The hotkey belongs to this thread: its messages arrive in this thread's queue.
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val registered = User32.INSTANCE.RegisterHotKey(null, ID, mods, vk)
            started.countDown()
            if (!registered) {
                Log.w("Quick menu shortcut $shortcut not registered (another app has it)")
                return@Thread
            }
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY) runCatching(onPress).onFailure { Log.w("Quick menu shortcut: $it") }
            }
            User32.INSTANCE.UnregisterHotKey(null, ID)
        }, "quick-menu-hotkey").apply { isDaemon = true; start() }
        started.await(2, TimeUnit.SECONDS)
    }

    @Synchronized
    private fun stop() {
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
        threadId = 0
        current = null
    }
}
