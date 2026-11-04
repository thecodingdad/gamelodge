package io.github.thecodingdad.gamelodge.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** Artemis' stream settings: base settings in the default preferences, profiles in its [ProfilesManager]. */
class ArtemisStreamBackup(private val context: Context) : StreamSettingsBackup {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(context)

    override fun baseSettings(): Map<String, JsonElement> = prefs.all.toJsonMap()

    override fun profiles(): List<ProfileBackup> {
        val manager = ProfilesManager.getInstance()
        return manager.profiles.map { ProfileBackup(it.name, it.options.orEmpty().toJsonMap(), manager.active?.uuid == it.uuid) }
    }

    override fun restore(base: Map<String, JsonElement>, profiles: List<ProfileBackup>, replace: Boolean) {
        prefs.edit().apply {
            base.forEach { (key, value) ->
                when (val v = value.toAny()) {
                    is Boolean -> putBoolean(key, v)
                    is Int -> putInt(key, v)
                    is Long -> putLong(key, v)
                    is Double -> putFloat(key, v.toFloat())
                    is String -> putString(key, v)
                }
            }
        }.apply()
        val manager = ProfilesManager.getInstance()
        fun options(p: ProfileBackup) = HashMap(p.options.mapNotNull { (k, v) -> v.toAny()?.let { k to it } }.toMap())
        val wanted = profiles.mapTo(HashSet()) { it.name }
        if (replace) manager.profiles.filter { it.name !in wanted }.forEach { manager.delete(it.uuid) }
        val existing = manager.profiles.associateBy { it.name }
        profiles.forEach { p ->
            val now = System.currentTimeMillis()
            val current = existing[p.name]
            val profile = when {
                current == null -> SettingsProfile(UUID.randomUUID(), p.name, now, now, options(p)).also(manager::add)
                replace -> SettingsProfile(current.uuid, p.name, current.createdUtc, now, options(p)).also(manager::update)
                else -> return@forEach
            }
            if (p.active) manager.setActive(profile.uuid)
        }
    }
}

/** Backups through the storage access framework: a folder or files picked with the system picker. */
class SafBackupStorage(private val context: Context) : BackupStorage {
    override fun folderLabel(folder: String): String = Uri.decode(folder).substringAfterLast(':')

    override suspend fun writeToFolder(folder: String, keep: Int, content: suspend (java.io.OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: error("Backup folder not accessible")
        val file = dir.createFile("application/zip", BackupManager.fileName()) ?: error("Cannot create backup file")
        try {
            (context.contentResolver.openOutputStream(file.uri) ?: error("Cannot write backup file")).use { content(it) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        dir.listFiles()
            .filter { it.name?.let(BackupManager::isBackupName) == true }
            .sortedByDescending { it.name }
            .drop(keep.coerceAtLeast(1))
            .forEach { it.delete() }
    }

    override suspend fun list(folder: String): List<BackupEntry> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: return@withContext emptyList()
        dir.listFiles()
            .mapNotNull { f -> f.name?.takeIf(BackupManager::isBackupName)?.let { BackupEntry(f.uri.toString(), it, BackupManager.createdAt(it)) } }
            .sortedByDescending { it.name }
    }

    override suspend fun write(address: String, content: suspend (java.io.OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        (context.contentResolver.openOutputStream(Uri.parse(address), "wt") ?: error("Cannot write file")).use { content(it) }
    }

    override fun open(address: String): java.io.InputStream =
        context.contentResolver.openInputStream(Uri.parse(address)) ?: error("Cannot read file")

    override fun scheduleAuto(enabled: Boolean) = BackupWorker.schedule(context, enabled)

    @Composable
    override fun rememberFolderPicker(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            onPicked(uri.toString())
        }
        return { launcher.launch(null) }
    }

    @Composable
    override fun rememberCreateFile(onPicked: (String) -> Unit): (String) -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            uri?.let { onPicked(it.toString()) }
        }
        return { name -> launcher.launch(name) }
    }

    @Composable
    override fun rememberOpenFile(onPicked: (String) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { onPicked(it.toString()) }
        }
        return { launcher.launch(arrayOf("application/json", "*/*")) }
    }
}
