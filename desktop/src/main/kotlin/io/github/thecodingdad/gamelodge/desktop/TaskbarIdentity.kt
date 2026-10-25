package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.PointerByReference
import io.github.thecodingdad.gamelodge.util.Log
import java.awt.Frame
import java.io.File

/**
 * How Windows' taskbar knows the launcher: the window runs in the Java runtime, so pinning it
 * would pin java without its arguments (and start nothing). The process and the window get the
 * app's id (the installer's start menu shortcut has the same one), and the window says how to start
 * it again: the GameLodge.exe starter with its name and icon.
 */
object TaskbarIdentity {
    const val APP_ID = "thecodingdad.GameLodge"

    /** The runner's own id: its splash is a separate taskbar button, neither java nor the launcher. */
    const val RUNNER_ID = "thecodingdad.GameLodge.Runner"

    /** Before the first window: the process belongs to the app's id ([id]: the runner's). */
    fun applyToProcess(id: String = APP_ID) {
        if (!isWindows) return
        runCatching {
            val shell = NativeLibrary.getInstance("shell32")
            shell.getFunction("SetCurrentProcessExplicitAppUserModelID").invokeInt(arrayOf(WString(id)))
        }.onFailure { Log.w("App id not set", it) }
    }

    /** The window's id and relaunch command (pinning works without a shortcut, e.g. the portable package). */
    fun applyToWindow(window: Frame) {
        if (!isWindows) return
        val starter = starter() ?: return
        // Hidden at start (tray): no native window yet, so once it opens.
        if (!window.isDisplayable) {
            window.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowOpened(e: java.awt.event.WindowEvent) {
                    window.removeWindowListener(this)
                    applyToWindow(window)
                }
            })
            return
        }
        runCatching {
            val hwnd = Native.getComponentPointer(window)
            val store = PointerByReference()
            val iid = Guid.GUID.fromString("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}") // IPropertyStore
            val hr = NativeLibrary.getInstance("shell32").getFunction("SHGetPropertyStoreForWindow")
                .invokeInt(arrayOf(hwnd, iid, store))
            if (hr != 0) error("SHGetPropertyStoreForWindow: 0x${hr.toString(16)}")
            val ps = store.value
            try {
                setString(ps, RELAUNCH_COMMAND, "\"${starter.path}\"")
                setString(ps, RELAUNCH_NAME, "GameLodge")
                setString(ps, RELAUNCH_ICON, "${starter.path},0")
                // The id last: the relaunch values only count together with it.
                setString(ps, ID, APP_ID)
                comCall(ps, COMMIT)
            } finally {
                comRelease(ps)
            }
        }.onFailure { Log.w("Taskbar relaunch command not set", it) }
    }

    /** GameLodge.exe next to lib/ (installed or portable package); null when started otherwise (IDE). */
    private fun starter(): File? {
        val jar = File(TaskbarIdentity::class.java.protectionDomain.codeSource.location.toURI())
        return jar.parentFile?.parentFile?.let { File(it, "GameLodge.exe") }?.takeIf { it.isFile }
    }

    // ---- IPropertyStore through its vtable (QueryInterface, AddRef, Release, GetCount, GetAt, GetValue, SetValue, Commit) ----

    private const val SET_VALUE = 6
    private const val COMMIT = 7

    /** System.AppUserModel.* property keys. */
    private const val APP_MODEL = "{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"
    private val RELAUNCH_COMMAND = APP_MODEL to 2
    private val RELAUNCH_ICON = APP_MODEL to 3
    private val RELAUNCH_NAME = APP_MODEL to 4
    private val ID = APP_MODEL to 5

    private fun setString(store: Pointer, key: Pair<String, Int>, value: String) {
        // PROPERTYKEY: GUID + property id.
        val pkey = Memory(20)
        pkey.write(0, Guid.GUID.fromString(key.first).toByteArray(), 0, 16)
        pkey.setInt(16, key.second)
        // PROPVARIANT with VT_LPWSTR: type at 0, the string pointer at 8 (x64 layout).
        val text = Memory((value.length + 1L) * 2).apply { setWideString(0, value) }
        val variant = Memory(24).apply { clear(); setShort(0, VT_LPWSTR); setPointer(8, text) }
        val hr = comCall(store, SET_VALUE, pkey, variant)
        if (hr != WinNT.S_OK.toInt()) error("IPropertyStore.SetValue: 0x${hr.toString(16)}")
    }

    private const val VT_LPWSTR: Short = 31
}
