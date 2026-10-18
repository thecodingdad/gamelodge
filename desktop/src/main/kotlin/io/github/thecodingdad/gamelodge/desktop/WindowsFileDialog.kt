package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.github.thecodingdad.gamelodge.util.Log
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import kotlin.concurrent.thread

/**
 * The Windows file and folder dialog of the Explorer (IFileOpenDialog / IFileSaveDialog), instead
 * of Java's old-style ones. It runs on its own COM thread; the UI thread keeps handling events
 * meanwhile, so the window behind keeps drawing.
 */
internal object WindowsFileDialog {
    enum class Mode { OPEN, FOLDER, SAVE }

    /** A file type of the type list: its name and patterns ("*.exe" or "*.png;*.jpg"). */
    class Filter(val name: String, val patterns: String)

    /** The chosen path, null when cancelled or the dialog failed. */
    fun show(mode: Mode, owner: Frame?, start: File?, fileName: String? = null, filters: List<Filter> = emptyList(), defaultExtension: String? = null): String? {
        val hwnd = owner?.takeIf { it.isDisplayable }?.let { runCatching { Native.getComponentPointer(it) }.getOrNull() }
        var result: String? = null
        val run = {
            result = runCatching { run(mode, hwnd, start, fileName, filters, defaultExtension) }
                .onFailure { Log.w("File dialog failed", it) }.getOrNull()
        }
        if (!EventQueue.isDispatchThread()) {
            thread(name = "file-dialog") { run() }.join()
            return result
        }
        // Started from inside the loop, so exit() never comes before enter().
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        EventQueue.invokeLater { thread(name = "file-dialog") { try { run() } finally { loop.exit() } } }
        loop.enter()
        return result
    }

    private fun run(mode: Mode, owner: Pointer?, start: File?, fileName: String?, filters: List<Filter>, defaultExtension: String?): String? {
        Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
        try {
            val save = mode == Mode.SAVE
            val ref = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                Guid.GUID.fromString(if (save) CLSID_SAVE else CLSID_OPEN), null, CLSCTX_INPROC_SERVER,
                Guid.GUID.fromString(if (save) IID_SAVE else IID_OPEN), ref,
            )
            check(hr.toInt() == 0) { "CoCreateInstance: 0x${hr.toInt().toString(16)}" }
            val dialog = ref.value
            try {
                val options = IntByReference()
                comCall(dialog, GET_OPTIONS, options)
                var flags = options.value or FOS_FORCEFILESYSTEM
                if (mode == Mode.FOLDER) flags = flags or FOS_PICKFOLDERS
                if (save) flags = flags or FOS_OVERWRITEPROMPT
                comCall(dialog, SET_OPTIONS, flags)
                if (filters.isNotEmpty() && mode != Mode.FOLDER) {
                    // COMDLG_FILTERSPEC[]: name and pattern pointers per entry (kept alive until Show returns).
                    val texts = filters.flatMap { listOf(wide(it.name), wide(it.patterns)) }
                    val specs = Memory(texts.size.toLong() * Native.POINTER_SIZE)
                    texts.forEachIndexed { i, m -> specs.setPointer(i.toLong() * Native.POINTER_SIZE, m) }
                    comCall(dialog, SET_FILE_TYPES, filters.size, specs)
                }
                defaultExtension?.let { comCall(dialog, SET_DEFAULT_EXTENSION, WString(it)) }
                fileName?.let { comCall(dialog, SET_FILE_NAME, WString(it)) }
                folderItem(start)?.let { item ->
                    comCall(dialog, SET_FOLDER, item)
                    comRelease(item)
                }
                val shown = comCall(dialog, SHOW, owner)
                if (shown == ERROR_CANCELLED) return null
                check(shown == 0) { "Show: 0x${shown.toString(16)}" }
                val itemRef = PointerByReference()
                check(comCall(dialog, GET_RESULT, itemRef) == 0) { "GetResult" }
                val item = itemRef.value
                try {
                    val name = PointerByReference()
                    check(comCall(item, GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name) == 0) { "GetDisplayName" }
                    return name.value.getWideString(0).also { Ole32.INSTANCE.CoTaskMemFree(name.value) }
                } finally {
                    comRelease(item)
                }
            } finally {
                comRelease(dialog)
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    /** IShellItem of the folder to start in (the file's folder for a file), null if there is none. */
    private fun folderItem(start: File?): Pointer? {
        val folder = start?.let { if (it.isDirectory) it else it.parentFile }?.takeIf { it.isDirectory } ?: return null
        val ref = PointerByReference()
        val hr = NativeLibrary.getInstance("shell32").getFunction("SHCreateItemFromParsingName")
            .invokeInt(arrayOf(WString(folder.absolutePath), null, Guid.GUID.fromString(IID_SHELL_ITEM), ref))
        return if (hr == 0) ref.value else null
    }

    private fun wide(text: String) = Memory((text.length + 1L) * 2).apply { setWideString(0, text) }

    private const val CLSID_OPEN = "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
    private const val IID_OPEN = "{D57C7288-D4AD-4768-BE02-9D969532D960}"
    private const val CLSID_SAVE = "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
    private const val IID_SAVE = "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
    private const val IID_SHELL_ITEM = "{43826D1E-E718-42EE-BC55-A1E261C37BFE}"
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val FOS_OVERWRITEPROMPT = 0x2
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val ERROR_CANCELLED = 0x800704C7.toInt()

    // IFileDialog vtable (after IUnknown 0–2 and IModalWindow::Show 3).
    private const val SHOW = 3
    private const val SET_FILE_TYPES = 4
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_FOLDER = 12
    private const val SET_FILE_NAME = 15
    private const val GET_RESULT = 20
    private const val SET_DEFAULT_EXTENSION = 22

    // IShellItem vtable.
    private const val GET_DISPLAY_NAME = 5
}
