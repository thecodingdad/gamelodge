package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.desktop.host.HostRole
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostCheck
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostConnection
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostSync
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostType
import io.github.thecodingdad.gamelodge.desktop.host.sync.SyncResult
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.StatusCard
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun hostTypeName(type: HostType) = when (type) {
    HostType.VIBEPOLLO -> "Vibepollo"
    HostType.APOLLO -> "Apollo"
    HostType.SUNSHINE -> "Sunshine"
}

/** Settings → Streaming host: this PC's games as app entries in Vibepollo, Apollo or Sunshine. */
@Composable
fun StreamingHostPage(role: HostRole, sync: HostSync, secrets: SecretStore?) {
    val settings by role.settings.collectAsState()
    val running by sync.running.collectAsState()
    val last by sync.last.collectAsState()
    val check by sync.check.collectAsState()
    val progress by sync.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sync.refreshCheck() }

    SettingsPage(stringResource(Res.string.settings_streaming_host)) {
        Text(stringResource(Res.string.host_sync_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        val look = checkLook(check, settings.hostType, settings.hostUrl)
        val connected = check?.state == HostCheck.State.CONNECTED
        StatusCard(
            title = if (running) stringResource(Res.string.host_sync_running) else look.title,
            color = look.color, icon = look.icon,
            details = listOfNotNull(look.detail, last?.let { syncSummary(it) }),
            busy = running || check == null || check?.state == HostCheck.State.CHECKING,
        ) {
            LauncherButton(
                stringResource(Res.string.host_sync_now), onClick = { scope.launch { sync.run() } },
                icon = Icons.Filled.Sync, enabled = connected && !running, primary = connected,
            )
            LauncherButton(stringResource(Res.string.host_connection), onClick = { editing = true }, icon = Icons.Filled.Settings, primary = !connected)
        }
        progress?.let { (done, total) ->
            if (total > 0) {
                Text(stringResource(Res.string.task_count, done, total), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent)
            }
        }
        SwitchRow(stringResource(Res.string.host_sync_enable), settings.syncEnabled, { on -> role.update { it.copy(syncEnabled = on) } },
            subtitle = stringResource(Res.string.host_sync_enable_sub), initialFocus = true,
        )
        SwitchRow(
            stringResource(Res.string.host_sync_not_installed), settings.syncNotInstalled, { on -> role.update { it.copy(syncNotInstalled = on) } },
            subtitle = stringResource(Res.string.host_sync_not_installed_sub),
        )
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_game_sources), SettingsTarget.Page(SettingsPageId.GAME_SOURCES)),
            SeeAlsoLink(stringResource(Res.string.startup_tray), SettingsTarget.Page(SettingsPageId.STARTUP), highlight = stringResource(Res.string.startup_tray), subtitle = stringResource(Res.string.settings_startup)),
        )
    }

    if (editing) ConnectionDialog(role, sync, secrets, onDismiss = { editing = false }, onSaved = { scope.launch { sync.refreshCheck() } })
}

/** How a connection state looks: title, details, colour and icon. */
private class CheckLook(val title: String, val detail: String?, val color: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun checkLook(check: HostCheck?, type: HostType, url: String): CheckLook {
    val colors = LauncherTheme.colors
    return when (check?.state) {
        null, HostCheck.State.CHECKING -> CheckLook(stringResource(Res.string.host_check_checking), null, colors.onSurfaceMuted, Icons.Filled.Sync)
        HostCheck.State.CONNECTED -> CheckLook(stringResource(Res.string.host_check_connected, hostTypeName(type), check.apps), url, colors.success, Icons.Filled.CheckCircle)
        HostCheck.State.NOT_SET_UP -> CheckLook(stringResource(Res.string.host_check_not_set_up), stringResource(Res.string.host_check_not_set_up_sub), colors.warning, Icons.Filled.Settings)
        HostCheck.State.AUTH_FAILED -> CheckLook(stringResource(Res.string.host_check_auth), stringResource(Res.string.host_check_auth_sub), colors.danger, Icons.Filled.Lock)
        HostCheck.State.UNREACHABLE -> CheckLook(
            stringResource(Res.string.host_check_unreachable), stringResource(Res.string.host_check_unreachable_sub, hostTypeName(type), url), colors.danger, Icons.Filled.CloudOff,
        )
        HostCheck.State.ERROR -> CheckLook(stringResource(Res.string.host_check_error, check.status), url, colors.danger, Icons.Filled.ErrorOutline)
    }
}

/** The last sync's outcome with its time (errors show in the connection state). */
@Composable
private fun syncSummary(r: SyncResult): String? {
    if (r.error != null) return null
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()))
    val text = if (r.failed > 0) stringResource(Res.string.host_sync_result_failed, r.failed) else stringResource(Res.string.host_sync_result, r.added, r.updated, r.removed)
    return "$text ($time)"
}

/** Host program, address and access; tested before saving if wanted. The secret is never shown again. */
@Composable
internal fun ConnectionDialog(role: HostRole, sync: HostSync, secrets: SecretStore?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val saved = role.settings.value
    val stored = remember { secrets?.get(DesktopContainer.HOST_SECRET) }
    var type by remember { mutableStateOf(saved.hostType) }
    var url by remember { mutableStateOf(saved.hostUrl) }
    var user by remember { mutableStateOf(saved.hostUser.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<HostCheck?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun connection() = HostConnection(url.trim(), type, user.trim().ifEmpty { null }, secret.ifBlank { stored })

    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.host_connection_title), maxWidth = 640.dp) {
        ChoiceRow(stringResource(Res.string.host_sync_type), HostType.entries, type, label = { hostTypeName(it) }, onSelect = { type = it; result = null })
        LauncherTextField(stringResource(Res.string.host_sync_url), url, { url = it; result = null }, kind = TextKind.URL)
        val placeholder = if (stored != null) stringResource(Res.string.host_sync_stored) else null
        if (type == HostType.VIBEPOLLO) {
            LauncherTextField(stringResource(Res.string.host_sync_token), secret, { secret = it; result = null }, kind = TextKind.PASSWORD, placeholder = placeholder)
            Text(stringResource(Res.string.host_sync_token_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        } else {
            LauncherTextField(stringResource(Res.string.host_sync_user), user, { user = it; result = null })
            LauncherTextField(stringResource(Res.string.host_sync_password), secret, { secret = it; result = null }, kind = TextKind.PASSWORD, placeholder = placeholder)
        }
        result?.let { r ->
            val look = checkLook(r, type, url.trim())
            Text(listOfNotNull(look.title, look.detail.takeIf { r.state != HostCheck.State.CONNECTED }).joinToString(" – "), color = look.color, style = MaterialTheme.typography.bodyMedium)
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
                role.update { it.copy(hostType = type, hostUrl = url.trim(), hostUser = user.trim().ifEmpty { null }) }
                if (secret.isNotBlank()) secrets?.put(DesktopContainer.HOST_SECRET, secret)
                onSaved()
                onDismiss()
            })
            LauncherButton(stringResource(Res.string.host_connection_test), icon = Icons.Filled.Sync, enabled = !testing, onClick = {
                testing = true
                scope.launch { result = sync.test(connection()); testing = false }
            })
            LauncherButton(stringResource(Res.string.action_cancel), onClick = onDismiss)
        }
    }
}
