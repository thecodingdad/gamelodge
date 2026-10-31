package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.Composable
import io.github.thecodingdad.gamelodge.data.backup.BackupEntry
import io.github.thecodingdad.gamelodge.data.backup.BackupManager
import io.github.thecodingdad.gamelodge.data.backup.BackupStorage
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.filechooser.FileSystemView

/**
 * Backups as plain files. Without a chosen folder they go to Documents\GameLodge\Backups,
 * so backing up and restoring work with the controller alone (no file dialog needed). The daily
 * automatic backup runs while the launcher runs (also in the tray).
 */
class DesktopBackupStorage(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val backup: () -> BackupManager,
) : BackupStorage {
    private var autoJob: Job? = null

    override val defaultFolder: String =
        File(runCatching { FileSystemView.getFileSystemView().defaultDirectory }.getOrNull() ?: File(System.getProperty("user.home")), "GameLodge/Backups").path

    override fun folderLabel(folder: String): String = folder

    override suspend fun writeToFolder(folder: String, bytes: ByteArray, keep: Int) = withContext(Dispatchers.IO) {
        val dir = File(folder).apply { mkdirs() }
        val file = File(dir, BackupManager.fileName())
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { tmp.delete(); error("Cannot write ${file.path}") }
        restrictToOwner(file)
        backupFiles(dir).drop(keep.coerceAtLeast(1)).forEach { it.delete() }
    }

    override suspend fun list(folder: String): List<BackupEntry> = withContext(Dispatchers.IO) {
        backupFiles(File(folder)).map { BackupEntry(it.path, it.name, BackupManager.createdAt(it.name)) }
    }

    override suspend fun write(address: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        File(address).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        restrictToOwner(File(address))
    }

    override suspend fun read(address: String): ByteArray = withContext(Dispatchers.IO) { File(address).readBytes() }

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
        writeToFolder(folder, backup().export(null), s.backupKeepCount)
        Log.i("Auto backup written to $folder")
    }

    private fun backupFiles(dir: File) =
        dir.listFiles { f -> f.isFile && BackupManager.isBackupName(f.name) }.orEmpty().sortedByDescending { it.name }

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit = {
        FilePickers.pickFolder(defaultFolder)?.let(onPicked)
    }

    @Composable
    override fun rememberCreateFile(onPicked: (String) -> Unit): (String) -> Unit = { name ->
        FilePickers.saveFile(defaultFolder.also { File(it).mkdirs() }, name, FilePickers.Types.BACKUPS)?.let(onPicked)
    }

    @Composable
    override fun rememberOpenFile(onPicked: (String) -> Unit): () -> Unit = {
        FilePickers.pickFile(defaultFolder, FilePickers.Types.BACKUPS)?.let(onPicked)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val CHECK_MS = 60L * 60 * 1000
    }
}
