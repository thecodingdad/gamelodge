package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink
import io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.companion.Fingerprint
import io.github.thecodingdad.gamelodge.data.companion.PairedServer
import io.github.thecodingdad.gamelodge.host.Device
import io.github.thecodingdad.gamelodge.desktop.host.HostRole
import io.github.thecodingdad.gamelodge.host.PairingState
import io.github.thecodingdad.gamelodge.host.Permissions
import io.github.thecodingdad.gamelodge.host.WebAdmin
import io.github.thecodingdad.gamelodge.desktop.host.sync.HostSync
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.StatusCard
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
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
    val devices by role.devices.devices.collectAsState()
    var pairingShown by remember { mutableStateOf(false) }
    var webShown by remember { mutableStateOf(false) }
    var deviceFor by remember { mutableStateOf<Device?>(null) }

    SettingsPage(stringResource(Res.string.settings_host_role)) {
        Text(stringResource(Res.string.host_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        val colors = LauncherTheme.colors
        when {
            status.serverFailed -> StatusCard(stringResource(Res.string.host_port_failed, PairedServer.COMPANION_PORT), colors.danger, Icons.Filled.ErrorOutline)
            !status.serverRunning -> StatusCard(stringResource(Res.string.host_server_off), colors.onSurfaceMuted, Icons.Filled.PowerSettingsNew)
            else -> StatusCard(
                stringResource(Res.string.host_server_on), colors.success, Icons.Filled.CheckCircle,
                details = listOf(
                    stringResource(Res.string.host_server_port, PairedServer.COMPANION_PORT, Fingerprint.pretty(role.identity.fingerprint).take(19) + " …"),
                    stringResource(Res.string.host_devices_count, devices.size),
                ),
            ) {
                LauncherButton(stringResource(Res.string.host_pair_start), onClick = { role.pairing.open(); pairingShown = true }, icon = Icons.Filled.Link, primary = true)
            }
        }
        SwitchRow(
            stringResource(Res.string.host_server), settings.serverEnabled, { on -> role.update { it.copy(serverEnabled = on) } },
            subtitle = stringResource(Res.string.host_server_sub, PairedServer.COMPANION_PORT), initialFocus = true,
        )

        SectionHeader(stringResource(Res.string.host_devices))
        if (devices.isEmpty()) Text(stringResource(Res.string.host_devices_none), color = LauncherTheme.colors.onSurfaceMuted)
        devices.forEach { d ->
            NavRow(
                d.name, { deviceFor = d },
                subtitle = d.lastSeen?.let { stringResource(Res.string.host_device_seen, formatDate(it)) } ?: stringResource(Res.string.host_device_paired, formatDate(d.paired)),
                icon = Icons.Filled.Devices,
            )
        }

        SectionHeader(stringResource(Res.string.host_advanced))
        NavRow(stringResource(Res.string.host_web), { webShown = true }, subtitle = webState(role, status.webRunning), icon = Icons.Filled.Language)
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.startup_tray), SettingsTarget.Page(SettingsPageId.STARTUP), highlight = stringResource(Res.string.startup_tray), subtitle = stringResource(Res.string.settings_startup)),
            SeeAlsoLink(stringResource(Res.string.metadata_sources), SettingsTarget.Page(SettingsPageId.METADATA), highlight = stringResource(Res.string.metadata_sources), subtitle = stringResource(Res.string.settings_metadata)),
        )
    }

    if (pairingShown) PairingDialog(role, devices.size) { role.pairing.close(); pairingShown = false }
    if (webShown) WebDialog(role) { webShown = false }
    // The dialog follows the stored device (renames, permissions); closes when it is removed.
    val current = deviceFor?.let { d -> devices.firstOrNull { it.id == d.id } }
    if (deviceFor != null && current == null) deviceFor = null
    current?.let { DeviceDialog(it, role, onDismiss = { deviceFor = null }) }
}

@Composable
private fun webState(role: HostRole, running: Boolean): String = when {
    !running -> stringResource(Res.string.host_web_state_off)
    role.auth.needsSetup -> stringResource(Res.string.host_web_state_no_password)
    else -> stringResource(Res.string.host_web_state_on)
}

/**
 * Pairing window: what to do on the device, the countdown, devices asking with their PIN field.
 * Closing it ends pairing; it closes by itself once a device is paired or the window runs out.
 */
@Composable
private fun PairingDialog(role: HostRole, pairedBefore: Int, onClose: () -> Unit) {
    val pairing by role.pairing.status.collectAsState()
    val devices by role.devices.devices.collectAsState()
    val startCount = remember { pairedBefore }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            role.pairing.refresh()
            delay(1000)
        }
    }
    if (devices.size > startCount || (pairing.openUntil == null && pairing.lockedUntil == null)) {
        LaunchedEffect(Unit) { onClose() }
    }
    var pinFor by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Modal(visible = true, onDismiss = onClose, title = stringResource(Res.string.host_pair_start), maxWidth = 620.dp) {
        val locked = pairing.lockedUntil
        val open = pairing.openUntil
        Text(stringResource(Res.string.host_pair_hint), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        when {
            locked != null -> Text(stringResource(Res.string.host_pair_locked, countdown(locked - now)), color = LauncherTheme.colors.danger)
            open != null -> Text(stringResource(Res.string.host_pair_open, countdown(open - now)), color = LauncherTheme.colors.success, style = MaterialTheme.typography.bodyLarge)
        }
        if (open != null && pairing.requests.isEmpty()) Text(stringResource(Res.string.host_pair_waiting), color = LauncherTheme.colors.onSurfaceMuted)
        pairing.requests.forEach { r ->
            val waiting = r.state == PairingState.WAITING_FOR_PIN
            NavRow(
                r.deviceName, { if (waiting) { pinFor = r.id; pin = ""; invalid = false } },
                subtitle = listOfNotNull(r.address, if (waiting) null else stringResource(Res.string.host_pair_running)).joinToString(" · "),
                icon = Icons.Filled.PhonelinkLock,
                trailing = if (waiting) stringResource(Res.string.host_pair_enter_pin) else null,
            )
            if (pinFor == r.id && waiting) {
                LauncherTextField(
                    stringResource(Res.string.host_pair_pin_title, r.deviceName), pin, { v -> pin = v.filter(Char::isDigit).take(6); invalid = false },
                    kind = TextKind.NUMBER, placeholder = "000000",
                )
                if (invalid) Text(stringResource(Res.string.host_pin_invalid), color = LauncherTheme.colors.danger)
                LauncherButton(stringResource(Res.string.host_pair_confirm), primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    if (pin.length == 6 && role.pairing.enterPin(r.id, pin)) pinFor = null else invalid = true
                })
            }
        }
        LauncherButton(stringResource(Res.string.host_pair_stop), onClick = onClose, icon = Icons.Filled.Stop, modifier = Modifier.fillMaxWidth())
    }
}

/** Web interface: on/off, address, sign-in on this PC and its password. */
@Composable
private fun WebDialog(role: HostRole, onDismiss: () -> Unit) {
    val settings by role.settings.collectAsState()
    val status by role.status.collectAsState()
    var setupCode by remember { mutableStateOf<String?>(null) }
    // Webpage changes (setup done, password reset) show up when the dialog is opened again.
    var revision by remember { mutableStateOf(0) }
    val needsSetup = remember(revision, status.webRunning) { role.auth.needsSetup }
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.host_web), maxWidth = 640.dp) {
        val code = setupCode
        if (code != null) {
            Text(stringResource(Res.string.host_web_setup_hint), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            Text(code, style = MaterialTheme.typography.displaySmall, color = LauncherTheme.colors.onSurface)
            LauncherButton(stringResource(Res.string.action_done), onClick = { setupCode = null; revision++ }, primary = true, modifier = Modifier.fillMaxWidth())
            return@Modal
        }
        SwitchRow(
            stringResource(Res.string.host_web_enable), settings.webEnabled, { on -> role.update { it.copy(webEnabled = on) } },
            subtitle = if (status.webFailed) stringResource(Res.string.host_port_failed, WebAdmin.DEFAULT_PORT) else stringResource(Res.string.host_web_sub),
            initialFocus = true,
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
                HoldToConfirmButton(stringResource(Res.string.host_web_reset), onConfirmed = { setupCode = role.auth.reset(); revision++ }, icon = Icons.Filled.Key)
            }
        }
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
