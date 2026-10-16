package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.runtime.Composable
import io.github.thecodingdad.moonlightlauncher.data.backup.BackupEntry
import io.github.thecodingdad.moonlightlauncher.data.backup.BackupManager
import io.github.thecodingdad.moonlightlauncher.data.backup.BackupStorage
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

/**
 * Backups as plain files. Without a chosen folder they go to Documents\Moonlight Launcher\Backups,
 * so backing up and restoring work with the controller alone (no file dialog needed). The daily
 * automatic backup runs while the launcher runs (also in the tray).
 */
class DesktopBackupStorage(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val backup: () -> BackupManager,
) : BackupStorage {
    /** Parent of the file dialogs (set by main). */
    var window: () -> Frame? = { null }
    private var autoJob: Job? = null

    override val defaultFolder: String =
        File(runCatching { FileSystemView.getFileSystemView().defaultDirectory }.getOrNull() ?: File(System.getProperty("user.home")), "Moonlight Launcher/Backups").path

    override fun folderLabel(folder: String): String = folder

    override suspend fun writeToFolder(folder: String, text: String, keep: Int) = withContext(Dispatchers.IO) {
        val dir = File(folder).apply { mkdirs() }
        val file = File(dir, BackupManager.fileName())
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { tmp.delete(); error("Cannot write ${file.path}") }
        restrictToOwner(file)
        backupFiles(dir).drop(keep.coerceAtLeast(1)).forEach { it.delete() }
    }

    override suspend fun list(folder: String): List<BackupEntry> = withContext(Dispatchers.IO) {
        backupFiles(File(folder)).map { BackupEntry(it.path, it.name, BackupManager.createdAt(it.name)) }
    }

    override suspend fun write(address: String, text: String) = withContext(Dispatchers.IO) {
        File(address).apply { parentFile?.mkdirs() }.writeText(text)
        restrictToOwner(File(address))
    }

    override suspend fun read(address: String): String = withContext(Dispatchers.IO) { File(address).readText() }

    override fun scheduleAuto(enabled: Boolean) {
        autoJob?.cancel()
        if (!enabled) return
        autoJob = scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { backupIfDue() }.onFailure { Log.w("Auto backup failed", it) }
                delay(CHECK_MS)
            }
        }
    }

    private suspend fun backupIfDue() {
        val s = settings.current()
        if (!s.autoBackup) return
        val folder = s.backupFolderUri.ifEmpty { defaultFolder }
        val newest = backupFiles(File(folder)).firstOrNull()?.let { BackupManager.createdAt(it.name) } ?: 0L
        if (System.currentTimeMillis() - newest < DAY_MS) return
        val b = backup()
        writeToFolder(folder, b.encode(b.create(null)), s.backupKeepCount)
        Log.i("Auto backup written to $folder")
    }

    private fun backupFiles(dir: File) =
        dir.listFiles { f -> f.isFile && BackupManager.isBackupName(f.name) }.orEmpty().sortedByDescending { it.name }

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit = {
        useSystemLook()
        val chooser = JFileChooser(defaultFolder).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(window()) == JFileChooser.APPROVE_OPTION) onPicked(chooser.selectedFile.path)
    }

    @Composable
    override fun rememberCreateFile(onPicked: (String) -> Unit): (String) -> Unit = { name ->
        val dialog = FileDialog(window(), null, FileDialog.SAVE).apply { directory = defaultFolder.also { File(it).mkdirs() }; file = name; isVisible = true }
        dialog.file?.let { onPicked(File(dialog.directory, it).path) }
    }

    @Composable
    override fun rememberOpenFile(onPicked: (String) -> Unit): () -> Unit = {
        val dialog = FileDialog(window(), null, FileDialog.LOAD).apply { directory = defaultFolder; file = "*.json"; isVisible = true }
        dialog.file?.let { onPicked(File(dialog.directory, it).path) }
    }

    private fun useSystemLook() {
        if (lookSet) return
        lookSet = true
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val CHECK_MS = 60L * 60 * 1000
        var lookSet = false
    }
}
