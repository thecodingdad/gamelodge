package io.github.thecodingdad.moonlightlauncher.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** The system's file and folder dialogs (mouse/keyboard; paths can always be typed in instead). */
object FilePickers {
    /** Parent of the dialogs (set by main). */
    var window: () -> Frame? = { null }
    private var lookSet = false

    /** A program (.exe) or any file; null: cancelled. */
    fun pickFile(start: String? = null, filter: String? = "*.exe"): String? {
        val dialog = FileDialog(window(), null, FileDialog.LOAD).apply {
            start?.let { File(it) }?.let { f -> directory = (if (f.isDirectory) f else f.parentFile)?.path }
            filter?.let { file = it }
            isVisible = true
        }
        return dialog.file?.let { File(dialog.directory, it).path }
    }

    fun pickFolder(start: String? = null): String? {
        if (!lookSet) {
            lookSet = true
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        }
        val chooser = JFileChooser(start).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        return if (chooser.showOpenDialog(window()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
    }
}
