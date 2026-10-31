package io.github.thecodingdad.gamelodge.data.backup

import androidx.compose.runtime.Composable

/** A backup file found in the backup folder. */
data class BackupEntry(val address: String, val name: String, val createdAt: Long?)

/**
 * Where backups go: the platform's files and pickers (Android: storage access framework,
 * PC: plain folders). Addresses are platform strings (content URI, file path).
 */
interface BackupStorage {
    /** Folder used while none is chosen (null: none, backups go through the file picker). */
    val defaultFolder: String? get() = null

    /** Short name of [folder] for the settings row. */
    fun folderLabel(folder: String): String

    /** Writes [bytes] as a new backup into [folder] and keeps only the newest [keep]. */
    suspend fun writeToFolder(folder: String, bytes: ByteArray, keep: Int)

    /** Backups in [folder], newest first. */
    suspend fun list(folder: String): List<BackupEntry>

    suspend fun write(address: String, bytes: ByteArray)

    suspend fun read(address: String): ByteArray

    /** Daily automatic backups on/off (the folder and count come from the settings). */
    fun scheduleAuto(enabled: Boolean)

    /** Returns what opens the folder picker; [onPicked] gets the folder. */
    @Composable
    fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit

    /** Returns what opens the "save as" picker with a suggested name; [onPicked] gets the target. */
    @Composable
    fun rememberCreateFile(onPicked: (String) -> Unit): (String) -> Unit

    /** Returns what opens the file picker for a backup to restore. */
    @Composable
    fun rememberOpenFile(onPicked: (String) -> Unit): () -> Unit
}
