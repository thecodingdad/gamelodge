package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.backup.BackupFile
import io.github.thecodingdad.moonlightlauncher.data.backup.BackupWorker
import io.github.thecodingdad.moonlightlauncher.data.backup.WrongPasswordException
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val backup = context.container.backup
    val scope = rememberCoroutineScope()
    val keyboard = LocalKeyboardController.current
    var message by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupFile?>(null) }
    val okMsg = stringResource(R.string.backup_done)
    val restoredMsg = stringResource(R.string.backup_restored)
    val wrongPwMsg = stringResource(R.string.backup_wrong_password)
    val pwTitle = stringResource(R.string.backup_password)

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        update { it.copy(backupFolderUri = uri.toString()) }
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching { backup.writeToUri(uri, password) }.fold({ okMsg }, { it.message })
        }
    }
    fun doRestore(file: BackupFile, pw: String?) {
        scope.launch {
            message = runCatching { backup.restore(file, pw) }.fold(
                { restoredMsg },
                { if (it is WrongPasswordException) wrongPwMsg else it.message },
            )
            BackupWorker.schedule(context, context.container.settings.current().autoBackup)
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val file = runCatching { backup.readFromUri(uri) }.getOrElse { message = it.message; return@launch }
            if (file.secrets != null) {
                pendingRestore = file
                keyboard.open(pwTitle, "", TextKind.PASSWORD) { pw -> pendingRestore?.let { doRestore(it, pw) }; pendingRestore = null }
            } else doRestore(file, null)
        }
    }
    fun backupNow() {
        if (settings.backupFolderUri.isNotEmpty()) {
            scope.launch {
                message = runCatching { backup.writeToFolder(Uri.parse(settings.backupFolderUri), settings.backupKeepCount, password) }.fold({ okMsg }, { it.message })
            }
        } else {
            createFile.launch("moonlight-launcher-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json")
        }
    }

    SettingsPage(stringResource(R.string.settings_backup)) {
        Text(stringResource(R.string.backup_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        NavRow(
            stringResource(R.string.backup_folder), { pickFolder.launch(null) },
            settings.backupFolderUri.takeIf { it.isNotEmpty() }?.let { Uri.decode(it).substringAfterLast(':') } ?: stringResource(R.string.not_set),
            Icons.Filled.Folder, initialFocus = true,
        )
        SwitchRow(
            stringResource(R.string.backup_auto), settings.autoBackup,
            { on -> update { it.copy(autoBackup = on) }; BackupWorker.schedule(context, on) },
            subtitle = stringResource(R.string.backup_auto_sub), icon = Icons.Filled.Schedule,
        )
        ChoiceRow(
            stringResource(R.string.backup_keep), listOf(3, 7, 14, 30), settings.backupKeepCount,
            label = { "$it" }, onSelect = { v -> update { it.copy(backupKeepCount = v) } },
        )
        SwitchRow(
            stringResource(R.string.backup_with_credentials), password != null,
            { on -> if (on) keyboard.open(pwTitle, "", TextKind.PASSWORD) { pw -> password = pw.ifEmpty { null } } else password = null },
            subtitle = stringResource(R.string.backup_with_credentials_sub), icon = Icons.Filled.Key,
        )
        Text(stringResource(R.string.backup_restore_hint), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.accent)
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(R.string.backup_now), icon = Icons.Filled.Save, primary = true, onClick = ::backupNow)
            HoldToConfirmButton(stringResource(R.string.backup_restore), { openFile.launch(arrayOf("application/json", "*/*")) }, icon = Icons.Filled.Restore)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface) }
    }
}
