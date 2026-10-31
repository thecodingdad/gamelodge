package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.material.icons.filled.Storage
import io.github.thecodingdad.gamelodge.ui.components.StatusCard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.backup.BackupEntry
import io.github.thecodingdad.gamelodge.data.backup.BackupFile
import io.github.thecodingdad.gamelodge.data.backup.BackupManager
import io.github.thecodingdad.gamelodge.data.backup.WrongPasswordException
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.MetadataState
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.storage.Orphans
import io.github.thecodingdad.gamelodge.data.storage.StorageKind
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.data.backup.summary
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

/** Settings › System › Data: what the launcher stores, clean-up, backups and the full reset. */
@Composable
fun DataSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, vm: LauncherViewModel) {
    val container = LocalContainer.current
    val data = container.data
    val scope = rememberCoroutineScope()
    val imageLoader = coil3.SingletonImageLoader.get(coil3.compose.LocalPlatformContext.current)
    var message by remember { mutableStateOf<String?>(null) }
    // Bumped after every action: sizes and orphans are measured again.
    var revision by remember { mutableIntStateOf(0) }
    var resetting by remember { mutableStateOf(false) }

    val usage by produceState<Map<StorageKind, Long>?>(null, revision) {
        val files = data.usage()
        val imageCache = withContext(Dispatchers.IO) { imageLoader.diskCache?.size ?: 0L }
        value = if (imageCache > 0) files + (StorageKind.CACHE to (files[StorageKind.CACHE] ?: 0L) + imageCache) else files
    }
    val orphans by produceState<Orphans?>(null, revision) { value = data.findOrphans() }
    val entries by vm.allEntries.collectAsState()

    val orphansDone = stringResource(Res.string.data_orphans_done)
    val redownloadDone = stringResource(Res.string.data_redownload_done)

    val lastBackup by produceState<Long?>(null, revision, settings.backupFolderUri) {
        val folder = settings.backupFolderUri.ifEmpty { container.backupStorage.defaultFolder.orEmpty() }
        value = if (folder.isEmpty()) null else runCatching { container.backupStorage.list(folder).mapNotNull { it.createdAt }.maxOrNull() }.getOrNull()
    }

    SettingsPage(stringResource(Res.string.settings_data)) {
        val u = usage
        StatusCard(
            if (u == null) stringResource(Res.string.data_measuring) else stringResource(Res.string.data_status_used, formatFileSize(u.values.sum())),
            LauncherTheme.colors.accent, Icons.Filled.Storage, busy = u == null,
            details = listOf(
                stringResource(
                    Res.string.data_status_library,
                    entries.count { it.item.kind == ItemKind.GAME }, entries.count { it.item.kind == ItemKind.APP },
                    entries.count { it.item.metadataState in setOf(MetadataState.OK, MetadataState.LOCKED) },
                ),
                lastBackup?.let { stringResource(Res.string.data_status_last_backup, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
                    ?: stringResource(Res.string.data_status_no_backup),
            ),
        )

        if (u != null) {
            SectionHeader(stringResource(Res.string.data_storage))
            StorageBar(u)
            u.entries.sortedByDescending { it.value }.forEach { (kind, bytes) -> StorageLine(kind, bytes) }
        }

        SectionHeader(stringResource(Res.string.data_cleanup))
        ActionRow(
            stringResource(Res.string.data_clear_cache),
            {
                scope.launch {
                    val before = usage?.get(StorageKind.CACHE) ?: 0L
                    imageLoader.memoryCache?.clear()
                    withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
                    data.clearCache()
                    message = org.jetbrains.compose.resources.getString(Res.string.data_cache_cleared, formatFileSize(before))
                    revision++
                }
            },
            stringResource(Res.string.data_clear_cache_sub), Icons.Filled.CleaningServices,
        )
        val o = orphans
        ActionRow(
            stringResource(Res.string.data_orphans),
            { scope.launch { data.deleteOrphans(); message = orphansDone; revision++ } },
            when {
                o == null -> stringResource(Res.string.data_measuring)
                o.isEmpty -> stringResource(Res.string.data_orphans_none)
                else -> stringResource(Res.string.data_orphans_sub, o.detachedGames, o.files.size, formatFileSize(o.bytes))
            },
            Icons.Filled.DeleteSweep, enabled = o != null && !o.isEmpty, hold = true,
        )
        ActionRow(
            stringResource(Res.string.data_redownload),
            { scope.launch { data.redownloadArtwork(); message = redownloadDone; revision++ } },
            stringResource(Res.string.data_redownload_sub), Icons.Filled.CloudDownload, hold = true,
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.accentText) }

        BackupSection(settings, update)

        SectionHeader(stringResource(Res.string.data_reset_section))
        ActionRow(stringResource(Res.string.data_reset), { resetting = true }, stringResource(Res.string.data_reset_sub), Icons.Filled.DeleteForever)
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.metadata_refetch_all), SettingsTarget.Page(SettingsPageId.METADATA), highlight = stringResource(Res.string.metadata_refetch_all), subtitle = stringResource(Res.string.settings_metadata)),
            SeeAlsoLink(stringResource(Res.string.settings_game_sources), SettingsTarget.Page(SettingsPageId.GAME_SOURCES)),
        )
    }

    Modal(visible = resetting, onDismiss = { resetting = false }, title = stringResource(Res.string.data_reset_title), closeButton = false) {
        Text(stringResource(Res.string.data_reset_text), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
        Spacer(Modifier.height(16.dp))
        HoldToConfirmButton(
            stringResource(Res.string.data_reset_confirm), icon = Icons.Filled.DeleteForever, modifier = Modifier.fillMaxWidth(),
            onConfirmed = { PlatformUi.current.resetAllData() },
        )
        Spacer(Modifier.height(8.dp))
        LauncherButton(stringResource(Res.string.action_cancel), primary = true, onClick = { resetting = false }, modifier = Modifier.fillMaxWidth())
    }
}

/** Title and explanation above an action button. */
private val storageColors = mapOf(
    StorageKind.DATABASE to Color(0xFF42A5F5),
    StorageKind.SETTINGS to Color(0xFF26A69A),
    StorageKind.ARTWORK to Color(0xFFAB47BC),
    StorageKind.OWN_IMAGES to Color(0xFFEC407A),
    StorageKind.HOST_ART to Color(0xFFFFA726),
    StorageKind.CACHE to Color(0xFF8D6E63),
    StorageKind.LOGS to Color(0xFF78909C),
    StorageKind.STREAMING to Color(0xFF66BB6A),
    StorageKind.HOST_ROLE to Color(0xFFFFEE58),
)

@Composable
private fun storageLabel(kind: StorageKind): String = stringResource(
    when (kind) {
        StorageKind.DATABASE -> Res.string.storage_database
        StorageKind.SETTINGS -> Res.string.storage_settings
        StorageKind.ARTWORK -> Res.string.storage_artwork
        StorageKind.OWN_IMAGES -> Res.string.storage_own_images
        StorageKind.HOST_ART -> Res.string.storage_host_art
        StorageKind.CACHE -> Res.string.storage_cache
        StorageKind.LOGS -> Res.string.storage_logs
        StorageKind.STREAMING -> Res.string.storage_streaming
        StorageKind.HOST_ROLE -> Res.string.storage_host_role
    },
)

/** All kinds side by side, each as wide as its share. */
@Composable
private fun StorageBar(usage: Map<StorageKind, Long>) {
    val total = usage.values.sum().coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(LauncherTheme.colors.surface),
    ) {
        usage.entries.sortedByDescending { it.value }.forEach { (kind, bytes) ->
            val share = bytes.toFloat() / total
            if (share > 0.002f) Box(Modifier.weight(share).fillMaxHeight().background(storageColors.getValue(kind)))
        }
    }
}

@Composable
private fun StorageLine(kind: StorageKind, bytes: Long) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LauncherTheme.colors.surface).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(storageColors.getValue(kind)))
        Text(storageLabel(kind), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.weight(1f))
        Text(formatFileSize(bytes), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
    }
}

/** Backup folder, automatic backups, backing up and restoring (from the folder's list or any file). */
@Composable
private fun BackupSection(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val container = LocalContainer.current
    val backup = container.backup
    val storage = container.backupStorage
    val scope = rememberCoroutineScope()
    val keyboard = LocalKeyboardController.current
    var message by remember { mutableStateOf<String?>(null) }
    // The backup dialog ("what goes in") and, for a chosen file, its loaded bytes + preview.
    var showBackup by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<ByteArray, io.github.thecodingdad.gamelodge.data.backup.BackupFile>?>(null) }
    var choosing by remember { mutableStateOf<List<BackupEntry>?>(null) }
    val okMsg = stringResource(Res.string.backup_done)
    val restoredMsg = stringResource(Res.string.backup_restored)
    val wrongPwMsg = stringResource(Res.string.backup_wrong_password)
    val pwTitle = stringResource(Res.string.backup_password)
    val folder = settings.backupFolderUri.ifEmpty { storage.defaultFolder.orEmpty() }

    val pickFolder = storage.rememberFolderPicker { picked -> update { it.copy(backupFolderUri = picked) } }
    val createFile = storage.rememberCreateFile { target ->
        scope.launch { message = runCatching { storage.write(target, backup.export(exportPassword)) }.fold({ okMsg }, { it.message }) }
    }
    // After the dialog: write to the folder, or pick a file (which then uses [exportPassword]).
    fun runBackup(pw: String?) {
        exportPassword = pw
        if (folder.isNotEmpty()) {
            scope.launch {
                message = runCatching { storage.writeToFolder(folder, backup.export(pw), settings.backupKeepCount) }.fold({ okMsg }, { it.message })
            }
        } else {
            createFile(BackupManager.fileName())
        }
    }
    fun doRestore(bytes: ByteArray, pw: String?) {
        pending = null
        scope.launch {
            message = runCatching { backup.import(bytes, pw) }.fold(
                { restoredMsg },
                { if (it is WrongPasswordException) wrongPwMsg else it.message },
            )
            storage.scheduleAuto(container.settings.current().autoBackup)
        }
    }
    // Loads the file and shows what it contains; the restore itself waits for the confirm dialog.
    fun restoreFrom(address: String) {
        choosing = null
        scope.launch {
            val bytes = runCatching { storage.read(address) }.getOrElse { message = it.message; return@launch }
            val manifest = runCatching { backup.readManifest(bytes) }.getOrElse { message = it.message; return@launch }
            pending = bytes to manifest
        }
    }
    val openFile = storage.rememberOpenFile(::restoreFrom)
    fun restore() {
        if (folder.isEmpty()) { openFile(); return }
        scope.launch { choosing = runCatching { storage.list(folder) }.getOrDefault(emptyList()) }
    }

    SectionHeader(stringResource(Res.string.settings_backup))
    Text(stringResource(Res.string.backup_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
    NavRow(
        stringResource(Res.string.backup_folder), pickFolder,
        folder.takeIf { it.isNotEmpty() }?.let(storage::folderLabel) ?: stringResource(Res.string.not_set),
        Icons.Filled.Folder,
    )
    SwitchRow(
        stringResource(Res.string.backup_auto), settings.autoBackup,
        { on -> update { it.copy(autoBackup = on) }; storage.scheduleAuto(on) },
        subtitle = stringResource(Res.string.backup_auto_sub), icon = Icons.Filled.Schedule,
        enabled = folder.isNotEmpty(),
    )
    // How many automatic backups stay: only with them on.
    if (settings.autoBackup) {
        ChoiceRow(
            stringResource(Res.string.backup_keep), listOf(3, 7, 14, 30), settings.backupKeepCount,
            label = { "$it" }, onSelect = { v -> update { it.copy(backupKeepCount = v) } },
        )
    }
    ActionRow(stringResource(Res.string.backup_now), { showBackup = true }, folder.takeIf { it.isNotEmpty() }?.let(storage::folderLabel), Icons.Filled.Save)
    ActionRow(stringResource(Res.string.backup_restore), ::restore, stringResource(Res.string.backup_restore_hint), Icons.Filled.Restore)
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface) }

    if (showBackup) BackupNowDialog(settings, update, onDismiss = { showBackup = false }, onConfirm = { pw -> showBackup = false; runBackup(pw) })
    pending?.let { (bytes, manifest) ->
        RestoreConfirmDialog(manifest, onDismiss = { pending = null }, onConfirm = { pw -> doRestore(bytes, pw) })
    }

    // Backups of the folder, newest first; restoring needs holding A (it overwrites settings).
    val list = choosing
    Modal(visible = list != null, onDismiss = { choosing = null }, title = stringResource(Res.string.backup_choose)) {
        if (list.isNullOrEmpty()) {
            Text(stringResource(Res.string.backup_none), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurfaceMuted)
        } else {
            val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
            list.take(MAX_LISTED).forEach { entry ->
                // Tap only shows what the backup contains; the restore is confirmed afterwards.
                LauncherButton(
                    entry.createdAt?.let { format.format(Date(it)) } ?: entry.name,
                    icon = Icons.Filled.Description, modifier = Modifier.fillMaxWidth(),
                    onClick = { restoreFrom(entry.address) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        LauncherButton(stringResource(Res.string.backup_other_file), icon = Icons.Filled.Folder, modifier = Modifier.fillMaxWidth(), onClick = {
            choosing = null
            openFile()
        })
    }
}

private const val MAX_LISTED = 10

/** "Back up now": choose what goes in; credentials ask for the password twice right here. */
@Composable
private fun BackupNowDialog(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (password: String?) -> Unit,
) {
    var credentials by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val match = pw.isNotEmpty() && pw == pw2
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = true, onDismiss = onDismiss, title = stringResource(Res.string.backup_now), maxWidth = 620.dp, closeButton = false,
    ) {
        Column(Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.backup_includes_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            SwitchRow(
                stringResource(Res.string.backup_cover_cache), settings.backupArtwork,
                { on -> update { it.copy(backupArtwork = on, backupAnimatedArtwork = on && it.backupAnimatedArtwork) } },
                subtitle = stringResource(Res.string.backup_cover_cache_sub), icon = Icons.Filled.Image, initialFocus = true,
            )
            if (settings.backupArtwork) {
                SwitchRow(
                    stringResource(Res.string.backup_animated_art), settings.backupAnimatedArtwork,
                    { on -> update { it.copy(backupAnimatedArtwork = on) } },
                    subtitle = stringResource(Res.string.backup_animated_art_sub), icon = Icons.Filled.Animation,
                )
            }
            SwitchRow(
                stringResource(Res.string.backup_with_credentials), credentials,
                { on -> credentials = on; if (!on) { pw = ""; pw2 = "" } },
                subtitle = stringResource(Res.string.backup_with_credentials_sub), icon = Icons.Filled.Key,
            )
            if (credentials) {
                LauncherTextField(stringResource(Res.string.backup_password), pw, { pw = it }, kind = TextKind.PASSWORD)
                LauncherTextField(stringResource(Res.string.backup_password_repeat), pw2, { pw2 = it }, kind = TextKind.PASSWORD)
                if (pw2.isNotEmpty() && !match) {
                    Text(stringResource(Res.string.backup_password_mismatch), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.danger)
                }
            }
        }
        ButtonRow {
            LauncherButton(
                stringResource(Res.string.backup_now), primary = true, icon = Icons.Filled.Save,
                enabled = !credentials || match, onClick = { onConfirm(if (credentials) pw else null) },
            )
            LauncherButton(stringResource(Res.string.action_cancel), onClick = onDismiss)
        }
    }
}

/** After a backup file is loaded: shows what it holds; restore is confirmed (or cancelled) here. */
@Composable
private fun RestoreConfirmDialog(
    manifest: io.github.thecodingdad.gamelodge.data.backup.BackupFile,
    onDismiss: () -> Unit,
    onConfirm: (password: String?) -> Unit,
) {
    val s = remember(manifest) { manifest.summary() }
    var pw by remember { mutableStateOf("") }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = true, onDismiss = onDismiss, title = stringResource(Res.string.backup_restore), maxWidth = 620.dp, closeButton = false,
    ) {
        Column(Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.backup_restore_contains), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
            Text(format.format(Date(s.createdAt)), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            Text(stringResource(Res.string.backup_summary_library, s.games, s.collections, s.filters), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
            Text(stringResource(Res.string.backup_summary_more, s.hosts, s.profiles, s.haTiles), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
            if (s.hasCredentials) {
                Text(stringResource(Res.string.backup_has_credentials), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                LauncherTextField(stringResource(Res.string.backup_password), pw, { pw = it }, kind = TextKind.PASSWORD)
            }
        }
        ButtonRow {
            LauncherButton(
                stringResource(Res.string.backup_restore), primary = true, icon = Icons.Filled.Restore,
                enabled = !s.hasCredentials || pw.isNotEmpty(), onClick = { onConfirm(if (s.hasCredentials) pw else null) },
            )
            LauncherButton(stringResource(Res.string.action_cancel), onClick = onDismiss)
        }
    }
}
