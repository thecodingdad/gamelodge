package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The system's file and folder dialogs (mouse/keyboard; paths can always be typed in instead):
 * on Windows the Explorer dialog, elsewhere Java's own.
 */
object FilePickers {
    /** Parent of the dialogs (set by main). */
    var window: () -> Frame? = { null }
    private var lookSet = false

    /** What a dialog lists: a name and its patterns; every list also offers all files. */
    enum class Types(val label: StringResource?, val patterns: List<String>) {
        PROGRAMS(Res.string.file_type_programs, listOf("exe")),
        IMAGES(Res.string.file_type_images, listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")),
        BACKUPS(Res.string.file_type_backups, listOf("json")),
        ALL(null, emptyList()),
    }

    /** A file; null: cancelled. */
    fun pickFile(start: String? = null, types: Types = Types.PROGRAMS): String? {
        if (isWindows) return WindowsFileDialog.show(WindowsFileDialog.Mode.OPEN, window(), start?.let(::File), filters = filters(types))
        val dialog = FileDialog(window(), null, FileDialog.LOAD).apply {
            start?.let { File(it) }?.let { f -> directory = (if (f.isDirectory) f else f.parentFile)?.path }
            if (types.patterns.isNotEmpty()) setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in types.patterns }
            isVisible = true
        }
        return dialog.file?.let { File(dialog.directory, it).path }
    }

    fun pickFolder(start: String? = null): String? {
        if (isWindows) return WindowsFileDialog.show(WindowsFileDialog.Mode.FOLDER, window(), start?.let(::File))
        if (!lookSet) {
            lookSet = true
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        }
        val chooser = JFileChooser(start).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        return if (chooser.showOpenDialog(window()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
    }

    /** Where to save [name] (starting in [folder]); null: cancelled. */
    fun saveFile(folder: String?, name: String, types: Types): String? {
        if (isWindows) {
            return WindowsFileDialog.show(
                WindowsFileDialog.Mode.SAVE, window(), folder?.let(::File), fileName = name, filters = filters(types),
                defaultExtension = types.patterns.firstOrNull(),
            )
        }
        val dialog = FileDialog(window(), null, FileDialog.SAVE).apply { directory = folder; file = name; isVisible = true }
        return dialog.file?.let { File(dialog.directory, it).path }
    }

    private fun filters(types: Types): List<WindowsFileDialog.Filter> = kotlinx.coroutines.runBlocking {
        listOfNotNull(
            types.label?.let { WindowsFileDialog.Filter(getString(it), types.patterns.joinToString(";") { p -> "*.$p" }) },
            WindowsFileDialog.Filter(getString(Res.string.file_type_all), "*.*"),
        )
    }
}
