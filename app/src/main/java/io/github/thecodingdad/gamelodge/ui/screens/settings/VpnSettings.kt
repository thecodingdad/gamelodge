package io.github.thecodingdad.gamelodge.ui.screens.settings

import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.container
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.vpn.VpnStatus
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

@Composable
fun vpnStatusLabel(status: VpnStatus) = stringResource(
    when (status) {
        VpnStatus.DOWN -> Res.string.vpn_down
        VpnStatus.CONNECTING -> Res.string.vpn_connecting
        VpnStatus.UP -> Res.string.vpn_up
        VpnStatus.ERROR -> Res.string.vpn_error
    },
)

@Composable
fun VpnSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val vpn = context.container.vpn
    val scope = rememberCoroutineScope()
    val state by vpn.state.collectAsState()
    val tunnels by vpn.tunnels.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var homeDialog by remember { mutableStateOf(false) }
    val importedMsg = stringResource(Res.string.vpn_imported)

    var pendingConnect by remember { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingConnect?.let { name -> scope.launch { vpn.connect(name) } }
        pendingConnect = null
    }
    fun connect(name: String) {
        val intent = vpn.consentIntent()
        if (intent != null) {
            pendingConnect = name
            consent.launch(intent)
        } else scope.launch { vpn.connect(name) }
    }

    fun importText(name: String, text: String) {
        message = vpn.import(name, text) ?: importedMsg
        if (settings.activeTunnel.isEmpty()) update { it.copy(activeTunnel = vpn.tunnels.value.firstOrNull().orEmpty()) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.') ?: "wg0"
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        importText(name, text)
    }

    var tunnelDialog by remember { mutableStateOf<String?>(null) }
    var importDialog by remember { mutableStateOf(false) }
    val defaultTunnel = settings.activeTunnel.takeIf { it in tunnels } ?: tunnels.firstOrNull()

    SettingsPage(stringResource(Res.string.settings_vpn)) {
        // Connection overview like the Home Assistant page.
        val colors = LauncherTheme.colors
        io.github.thecodingdad.gamelodge.ui.components.StatusCard(
            if (tunnels.isEmpty()) stringResource(Res.string.vpn_no_tunnel) else vpnStatusLabel(state.status),
            when (state.status) {
                VpnStatus.UP -> colors.success
                VpnStatus.ERROR -> colors.danger
                VpnStatus.CONNECTING -> colors.accent
                VpnStatus.DOWN -> colors.onSurfaceMuted
            },
            if (state.status == VpnStatus.UP) Icons.Filled.VpnLock else Icons.Filled.VpnKey,
            details = listOfNotNull(
                if (tunnels.isEmpty()) stringResource(Res.string.vpn_no_tunnel_sub) else state.tunnel ?: defaultTunnel,
                state.error?.takeIf { it != "consent" && state.status == VpnStatus.ERROR },
            ),
            busy = state.status == VpnStatus.CONNECTING,
        ) {
            when {
                tunnels.isEmpty() -> LauncherButton(stringResource(Res.string.vpn_add_tunnel), icon = Icons.Filled.Add, primary = true, onClick = { importDialog = true })
                state.status == VpnStatus.UP -> LauncherButton(stringResource(Res.string.vpn_disconnect), icon = Icons.Filled.LinkOff, onClick = { scope.launch { vpn.disconnect() } })
                else -> defaultTunnel?.let { name -> LauncherButton(stringResource(Res.string.vpn_connect), icon = Icons.Filled.Link, primary = true, onClick = { connect(name) }) }
            }
        }

        SectionHeader(stringResource(Res.string.vpn_tunnels))
        tunnels.forEachIndexed { index, name ->
            val isUp = state.status == VpnStatus.UP && state.tunnel == name
            NavRow(
                name, { tunnelDialog = name },
                listOfNotNull(
                    stringResource(Res.string.vpn_default_tunnel).takeIf { name == defaultTunnel },
                    vpnStatusLabel(if (isUp) VpnStatus.UP else VpnStatus.DOWN),
                ).joinToString(" · "),
                Icons.Filled.VpnKey,
                initialFocus = index == 0,
            )
        }
        LauncherButton(stringResource(Res.string.vpn_add_tunnel), icon = Icons.Filled.Add, primary = tunnels.isEmpty(), onClick = { importDialog = true })

        SectionHeader(stringResource(Res.string.vpn_auto))
        SwitchRow(
            stringResource(Res.string.vpn_on_stream), settings.vpnOnStreamStart, { v -> update { it.copy(vpnOnStreamStart = v) } },
            subtitle = stringResource(Res.string.vpn_on_stream_sub), icon = Icons.Filled.VpnLock,
        )
        if (settings.vpnOnStreamStart) {
            SwitchRow(
                stringResource(Res.string.vpn_only_outside), settings.vpnOnlyOutsideHome, { v -> update { it.copy(vpnOnlyOutsideHome = v) } },
                subtitle = when {
                    !settings.vpnOnlyOutsideHome -> null
                    settings.homeNetworks.isEmpty() -> stringResource(Res.string.vpn_only_outside_none)
                    vpn.atHome(settings.homeNetworks) -> stringResource(Res.string.vpn_at_home)
                    else -> stringResource(Res.string.vpn_not_at_home)
                },
                icon = Icons.Filled.Home,
            )
            if (settings.vpnOnlyOutsideHome) {
                NavRow(
                    stringResource(Res.string.vpn_home_networks), { homeDialog = true },
                    settings.homeNetworks.sorted().joinToString(", ").ifEmpty { stringResource(Res.string.vpn_home_networks_none) },
                    Icons.Filled.Lan,
                )
            }
            SwitchRow(
                stringResource(Res.string.vpn_disconnect_after), settings.vpnDisconnectAfterStream, { v -> update { it.copy(vpnDisconnectAfterStream = v) } },
                subtitle = stringResource(Res.string.vpn_disconnect_after_sub),
            )
        }
        SeeAlso(SeeAlsoLink(stringResource(Res.string.settings_hosts), SettingsTarget.Page(SettingsPageId.HOSTS), subtitle = stringResource(Res.string.vpn_see_hosts)))
    }
    HomeNetworksDialog(homeDialog, settings.homeNetworks, update) { homeDialog = false }

    // One tunnel: state, networks, connect / disconnect, default, delete.
    val shown = tunnelDialog
    io.github.thecodingdad.gamelodge.ui.components.Modal(visible = shown != null, onDismiss = { tunnelDialog = null }, title = shown, size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        val name = shown ?: return@Modal
        val isUp = state.status == VpnStatus.UP && state.tunnel == name
        val routes = remember(name) { vpn.allowedIps(name) }
        InfoRow(stringResource(Res.string.ha_status), vpnStatusLabel(if (isUp) VpnStatus.UP else if (state.tunnel == name) state.status else VpnStatus.DOWN))
        InfoRow(
            stringResource(Res.string.vpn_networks),
            if (routes.any { it.endsWith("/0") }) stringResource(Res.string.vpn_all_traffic) else routes.joinToString(", ").ifEmpty { "–" },
        )
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            if (isUp) LauncherButton(stringResource(Res.string.vpn_disconnect), icon = Icons.Filled.LinkOff, onClick = { scope.launch { vpn.disconnect() } })
            else LauncherButton(stringResource(Res.string.vpn_connect), icon = Icons.Filled.Link, primary = true, onClick = { connect(name) })
            if (name != defaultTunnel) LauncherButton(stringResource(Res.string.vpn_set_default), icon = Icons.Filled.Star, onClick = { update { it.copy(activeTunnel = name) } })
        }
        HoldToConfirmButton(
            stringResource(Res.string.vpn_delete, name),
            {
                vpn.delete(name)
                if (settings.activeTunnel == name) update { it.copy(activeTunnel = vpn.tunnels.value.firstOrNull().orEmpty()) }
                tunnelDialog = null
            },
            icon = Icons.Filled.Delete, modifier = Modifier.fillMaxWidth(),
        )
    }

    // Import: from a file or the clipboard.
    io.github.thecodingdad.gamelodge.ui.components.Modal(visible = importDialog, onDismiss = { importDialog = false; message = null }, title = stringResource(Res.string.vpn_add_tunnel), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Text(stringResource(Res.string.vpn_import_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            LauncherButton(stringResource(Res.string.vpn_import_file), icon = Icons.Filled.FileOpen, primary = true, onClick = { openFile.launch(arrayOf("*/*")) })
            LauncherButton(stringResource(Res.string.vpn_import_clipboard), icon = Icons.Filled.ContentPaste, onClick = {
                val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                importText("wg${tunnels.size}", text)
            })
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted) }
    }
}

/** The home networks: a list with ✕ per entry; the current network / IP or one typed in are added. */
@Composable
private fun HomeNetworksDialog(
    visible: Boolean,
    networks: Set<String>,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val vpn = LocalContext.current.container.vpn
    val keyboard = io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController.current
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val title = stringResource(Res.string.vpn_home_networks)
    val manualTitle = stringResource(Res.string.vpn_add_manual)
    val invalid = stringResource(Res.string.vpn_home_invalid, "%s")
    fun add(entries: List<String>) = update { it.copy(homeNetworks = it.homeNetworks + entries) }
    io.github.thecodingdad.gamelodge.ui.components.Modal(visible = visible, onDismiss = onDismiss, title = title, size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        // Read when the popup shows (the network may have changed since the page opened).
        val addresses = remember(visible) { vpn.localAddresses() }
        Text(stringResource(Res.string.vpn_home_networks_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        Text(
            if (addresses.isEmpty()) stringResource(Res.string.vpn_device_no_address)
            else stringResource(Res.string.vpn_device_addresses, addresses.joinToString(", ") { "${it.first}/${it.second}" }),
            style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
        )
        androidx.compose.foundation.layout.Column(
            Modifier.weight(1f, fill = false).popupHeightIn(320.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (networks.isEmpty()) Text(stringResource(Res.string.vpn_home_empty), color = LauncherTheme.colors.onSurfaceMuted)
            networks.sorted().forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().background(LauncherTheme.colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lan, null, tint = LauncherTheme.colors.accentText, modifier = Modifier.size(22.dp))
                    Text(entry, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                        onClick = { update { it.copy(homeNetworks = it.homeNetworks - entry) } },
                        shape = CircleShape, color = LauncherTheme.colors.surface, modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Filled.Close, stringResource(Res.string.vpn_remove), tint = LauncherTheme.colors.onSurface, modifier = Modifier.align(Alignment.Center).size(22.dp))
                    }
                }
            }
        }
        error?.let { Text(it, color = LauncherTheme.colors.danger, style = MaterialTheme.typography.bodyMedium) }
        // Enabled whenever there is an address (already listed ones are simply not added twice).
        val subnets = addresses.mapNotNull { (ip, bits) -> io.github.thecodingdad.gamelodge.vpn.HomeNetworks.subnetOf(ip, bits) }
        val ips = addresses.map { it.first }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            LauncherButton(stringResource(Res.string.vpn_add_current_network), icon = Icons.Filled.Add, enabled = subnets.isNotEmpty(), onClick = { add(subnets) })
            LauncherButton(stringResource(Res.string.vpn_add_current_ip), icon = Icons.Filled.Add, enabled = ips.isNotEmpty(), onClick = { add(ips) })
            LauncherButton(manualTitle, icon = Icons.Filled.Edit, onClick = {
                keyboard.open(manualTitle, "", io.github.thecodingdad.gamelodge.ui.components.TextKind.URL) { typed ->
                    if (typed.isBlank()) return@open
                    val entry = io.github.thecodingdad.gamelodge.vpn.HomeNetworks.normalize(typed)
                    if (entry == null) error = invalid.replace("%s", typed.trim()) else { error = null; add(listOf(entry)) }
                }
            })
        }
    }
}
