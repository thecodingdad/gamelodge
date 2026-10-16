package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.data.companion.Fingerprint
import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer
import io.github.thecodingdad.moonlightlauncher.desktop.host.Device
import io.github.thecodingdad.moonlightlauncher.desktop.host.HostRole
import io.github.thecodingdad.moonlightlauncher.desktop.host.PairingRequestInfo
import io.github.thecodingdad.moonlightlauncher.desktop.host.PairingState
import io.github.thecodingdad.moonlightlauncher.desktop.host.Permissions
import io.github.thecodingdad.moonlightlauncher.desktop.host.WebAdmin
import io.github.thecodingdad.moonlightlauncher.desktop.host.sync.HostSync
import io.github.thecodingdad.moonlightlauncher.desktop.host.sync.HostType
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.resources.*
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.InfoRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val permissionTexts: List<Pair<String, StringResource>> = listOf(
    Permissions.METADATA to Res.string.host_perm_metadata,
    Permissions.HOST_GAMES to Res.string.host_perm_hostgames,
    Permissions.HOST_GAMES_WRITE to Res.string.host_perm_hostgames_write,
)

/** What the Devices & pairing page works with. */
class HostServices(val role: HostRole, val sync: HostSync?, val secrets: SecretStore?)

/** Settings → Devices & pairing: the PC as host for other launchers (the former companion's job). */
@Composable
fun HostRolePage(services: HostServices) {
    val role = services.role
    val settings by role.settings.collectAsState()
    val status by role.status.collectAsState()
    val pairing by role.pairing.status.collectAsState()
    val devices by role.devices.devices.collectAsState()
    var pinFor by remember { mutableStateOf<PairingRequestInfo?>(null) }
    var deviceFor by remember { mutableStateOf<Device?>(null) }
    var setupCode by remember { mutableStateOf<String?>(null) }
    // Webpage changes (setup done, password reset) show up when the page is visited again.
    var webRevision by remember { mutableStateOf(0) }
    val needsSetup = remember(webRevision, status.webRunning) { role.auth.needsSetup }

    // Countdown while the window is open or locked; expiries are re-evaluated with it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing.openUntil, pairing.lockedUntil) {
        while (pairing.openUntil != null || pairing.lockedUntil != null) {
            now = System.currentTimeMillis()
            role.pairing.refresh()
            delay(1000)
        }
    }

    SettingsPage(stringResource(Res.string.settings_host_role)) {
        Text(stringResource(Res.string.host_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SwitchRow(
            stringResource(Res.string.host_server), settings.serverEnabled, { on -> role.update { it.copy(serverEnabled = on) } },
            subtitle = if (status.serverFailed) stringResource(Res.string.host_port_failed, PairedServer.COMPANION_PORT)
            else stringResource(Res.string.host_server_sub, PairedServer.COMPANION_PORT),
            initialFocus = true,
        )
        if (role.proxy != null) {
            SwitchRow(
                stringResource(Res.string.host_proxy), settings.proxyEnabled, { on -> role.update { it.copy(proxyEnabled = on) } },
                subtitle = stringResource(Res.string.host_proxy_sub),
            )
        }

        SectionHeader(stringResource(Res.string.host_pairing))
        val locked = pairing.lockedUntil
        val open = pairing.openUntil
        when {
            !status.serverRunning -> Text(stringResource(Res.string.host_pair_needs_server), color = LauncherTheme.colors.onSurfaceMuted)
            locked != null -> Text(stringResource(Res.string.host_pair_locked, countdown(locked - now)), color = LauncherTheme.colors.danger)
            open != null -> {
                Text(stringResource(Res.string.host_pair_open, countdown(open - now)), color = LauncherTheme.colors.success, style = MaterialTheme.typography.bodyLarge)
                if (pairing.requests.isEmpty()) Text(stringResource(Res.string.host_pair_waiting), color = LauncherTheme.colors.onSurfaceMuted)
                pairing.requests.forEach { r ->
                    NavRow(
                        r.deviceName, { if (r.state == PairingState.WAITING_FOR_PIN) pinFor = r },
                        subtitle = listOfNotNull(r.address, if (r.state == PairingState.WAITING_FOR_PIN) null else stringResource(Res.string.host_pair_running)).joinToString(" · "),
                        icon = Icons.Filled.PhonelinkLock,
                        trailing = if (r.state == PairingState.WAITING_FOR_PIN) stringResource(Res.string.host_pair_enter_pin) else null,
                    )
                }
                LauncherButton(stringResource(Res.string.host_pair_stop), onClick = { role.pairing.close() }, icon = Icons.Filled.Stop)
            }
            else -> {
                Text(stringResource(Res.string.host_pair_hint), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                LauncherButton(stringResource(Res.string.host_pair_start), onClick = { role.pairing.open() }, icon = Icons.Filled.Link, primary = true)
            }
        }
        if (status.serverRunning) InfoRow(stringResource(Res.string.host_fingerprint), Fingerprint.pretty(role.identity.fingerprint).take(39) + " …")

        SectionHeader(stringResource(Res.string.host_devices))
        if (devices.isEmpty()) Text(stringResource(Res.string.host_devices_none), color = LauncherTheme.colors.onSurfaceMuted)
        devices.forEach { d ->
            NavRow(
                d.name, { deviceFor = d },
                subtitle = d.lastSeen?.let { stringResource(Res.string.host_device_seen, formatDate(it)) } ?: stringResource(Res.string.host_device_paired, formatDate(d.paired)),
                icon = Icons.Filled.Devices,
            )
        }

        services.sync?.let { HostSyncSection(role, it, services.secrets) }

        SectionHeader(stringResource(Res.string.host_web))
        SwitchRow(
            stringResource(Res.string.host_web_enable), settings.webEnabled, { on -> role.update { it.copy(webEnabled = on) } },
            subtitle = if (status.webFailed) stringResource(Res.string.host_port_failed, WebAdmin.DEFAULT_PORT) else stringResource(Res.string.host_web_sub),
        )
        if (status.webRunning) {
            val address = remember { role.localAddresses().firstOrNull() ?: "localhost" }
            Text(stringResource(Res.string.host_web_address, "https://$address:${WebAdmin.DEFAULT_PORT}"), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
            LauncherButton(
                stringResource(Res.string.host_web_open), onClick = { role.localLoginUrl()?.let(BrowserOpener::open) },
                icon = Icons.AutoMirrored.Filled.OpenInNew, primary = true,
            )
            Text(stringResource(Res.string.host_web_open_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            if (needsSetup) {
                Text(stringResource(Res.string.host_web_no_password), color = LauncherTheme.colors.onSurfaceMuted)
                LauncherButton(stringResource(Res.string.host_web_setup), onClick = { setupCode = role.auth.issueSetupCode() }, icon = Icons.Filled.Key)
            } else {
                Text(stringResource(Res.string.host_web_password_set), color = LauncherTheme.colors.onSurfaceMuted)
                HoldToConfirmButton(stringResource(Res.string.host_web_reset), onConfirmed = { setupCode = role.auth.reset(); webRevision++ }, icon = Icons.Filled.Key)
            }
        }
    }

    pinFor?.let { r -> PinDialog(r, onDismiss = { pinFor = null }) { pin -> role.pairing.enterPin(r.id, pin) } }
    // The dialog follows the stored device (renames, permissions); closes when it is removed.
    val current = deviceFor?.let { d -> devices.firstOrNull { it.id == d.id } }
    if (deviceFor != null && current == null) deviceFor = null
    current?.let { DeviceDialog(it, role, onDismiss = { deviceFor = null }) }
    setupCode?.let { code ->
        Modal(visible = true, onDismiss = { setupCode = null; webRevision++ }, title = stringResource(Res.string.host_web_code_title), maxWidth = 560.dp) {
            Text(stringResource(Res.string.host_web_setup_hint), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            Text(code, style = MaterialTheme.typography.displaySmall, color = LauncherTheme.colors.onSurface)
            LauncherButton(stringResource(Res.string.action_done), onClick = { setupCode = null; webRevision++ }, primary = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Streaming host on this PC: connection and the games sync. */
@Composable
private fun HostSyncSection(role: HostRole, sync: HostSync, secrets: SecretStore?) {
    val settings by role.settings.collectAsState()
    val running by sync.running.collectAsState()
    val last by sync.last.collectAsState()
    val scope = rememberCoroutineScope()
    SectionHeader(stringResource(Res.string.host_sync))
    Text(stringResource(Res.string.host_sync_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
    val status = when {
        running -> stringResource(Res.string.host_sync_running)
        last == null -> null
        last!!.error == "unreachable" -> stringResource(Res.string.host_sync_error_unreachable)
        last!!.error == "no_host" -> stringResource(Res.string.host_sync_error_no_host)
        last!!.error != null -> stringResource(Res.string.host_sync_error_status, last!!.error!!.substringAfter(':'))
        last!!.failed > 0 -> stringResource(Res.string.host_sync_result_failed, last!!.failed)
        else -> stringResource(Res.string.host_sync_result, last!!.added, last!!.updated, last!!.removed)
    }
    SwitchRow(stringResource(Res.string.host_sync_enable), settings.syncEnabled, { on -> role.update { it.copy(syncEnabled = on) } }, subtitle = status)
    val progress by sync.progress.collectAsState()
    progress?.let { (done, total) ->
        if (total > 0) {
            Text(stringResource(Res.string.task_count, done, total), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            androidx.compose.material3.LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = androidx.compose.ui.Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent)
        } else {
            androidx.compose.material3.LinearProgressIndicator(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent)
        }
    }
    ChoiceRow(
        stringResource(Res.string.host_sync_type), HostType.entries, settings.hostType,
        label = { when (it) { HostType.VIBEPOLLO -> "Vibepollo"; HostType.APOLLO -> "Apollo"; HostType.SUNSHINE -> "Sunshine" } },
        onSelect = { v -> role.update { it.copy(hostType = v) } },
    )
    LauncherTextField(stringResource(Res.string.host_sync_url), settings.hostUrl, { v -> role.update { it.copy(hostUrl = v.trim()) } }, kind = TextKind.URL)
    // The secret is never shown again: a new value replaces it, an empty field keeps it.
    var secret by remember { mutableStateOf("") }
    val stored = remember(secret) { secrets?.get(DesktopContainer.HOST_SECRET) != null }
    fun saveSecret(v: String) {
        secret = v
        if (v.isNotBlank()) secrets?.put(DesktopContainer.HOST_SECRET, v)
    }
    if (settings.hostType == HostType.VIBEPOLLO) {
        LauncherTextField(stringResource(Res.string.host_sync_token), secret, ::saveSecret, kind = TextKind.PASSWORD,
            placeholder = if (stored) stringResource(Res.string.host_sync_stored) else null)
        Text(stringResource(Res.string.host_sync_token_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    } else {
        LauncherTextField(stringResource(Res.string.host_sync_user), settings.hostUser.orEmpty(), { v -> role.update { it.copy(hostUser = v.trim().ifEmpty { null }) } })
        LauncherTextField(stringResource(Res.string.host_sync_password), secret, ::saveSecret, kind = TextKind.PASSWORD,
            placeholder = if (stored) stringResource(Res.string.host_sync_stored) else null)
    }
    SwitchRow(
        stringResource(Res.string.host_sync_not_installed), settings.syncNotInstalled, { on -> role.update { it.copy(syncNotInstalled = on) } },
        subtitle = stringResource(Res.string.host_sync_not_installed_sub),
    )
    LauncherButton(stringResource(Res.string.host_sync_now), onClick = { scope.launch { sync.run() } }, icon = Icons.Filled.Sync, enabled = !running)
}

/** PIN the device shows; the J-PAKE exchange checks it (a wrong PIN fails on the device). */
@Composable
private fun PinDialog(request: PairingRequestInfo, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.host_pair_pin_title, request.deviceName), maxWidth = 520.dp) {
        LauncherTextField(stringResource(Res.string.host_pair_enter_pin), pin, { v -> pin = v.filter(Char::isDigit).take(6); invalid = false }, kind = TextKind.NUMBER, placeholder = "000000")
        if (invalid) Text(stringResource(Res.string.host_pin_invalid), color = LauncherTheme.colors.danger)
        LauncherButton(
            stringResource(Res.string.host_pair_confirm),
            onClick = { if (pin.length == 6 && onPin(pin)) onDismiss() else invalid = true },
            primary = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeviceDialog(device: Device, role: HostRole, onDismiss: () -> Unit) {
    Modal(visible = true, onDismiss = onDismiss, title = device.name, maxWidth = 620.dp) {
        // Saved when the dialog closes, not per keystroke.
        var name by remember(device.id) { mutableStateOf(device.name) }
        DisposableEffect(device.id) {
            onDispose { if (name.isNotBlank() && name.trim() != device.name) role.devices.rename(device.id, name.trim().take(64)) }
        }
        LauncherTextField(stringResource(Res.string.host_device_name), name, { name = it })
        Text(
            listOfNotNull(
                stringResource(Res.string.host_device_paired, formatDate(device.paired)),
                device.lastSeen?.let { stringResource(Res.string.host_device_seen, formatDate(it)) + (device.lastAddress?.let { a -> " ($a)" } ?: "") },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted,
        )
        SectionHeader(stringResource(Res.string.host_device_permissions))
        permissionTexts.forEach { (permission, text) ->
            SwitchRow(stringResource(text), device.may(permission), { on ->
                role.devices.setPermissions(device.id, if (on) device.permissions + permission else device.permissions - permission)
            })
        }
        Text(Fingerprint.pretty(device.fingerprint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        HoldToConfirmButton(stringResource(Res.string.host_device_remove), onConfirmed = { role.devices.remove(device.id); onDismiss() }, modifier = Modifier.fillMaxWidth())
    }
}

private fun countdown(ms: Long): String {
    val s = ((ms.coerceAtLeast(0) + 999) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatDate(unixSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()))
