package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import android.Manifest
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
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
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.settings.AutoVpnTrigger
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.vpn.VpnStatus
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.resources.*

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
    val ssid by vpn.currentSsid.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
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
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) update { it.copy(autoVpnTriggers = it.autoVpnTriggers + AutoVpnTrigger.OUTSIDE_HOME) }
    }

    SettingsPage(stringResource(Res.string.settings_vpn)) {
        InfoRow(stringResource(Res.string.ha_status), vpnStatusLabel(state.status) + (state.tunnel?.let { " · $it" } ?: ""))
        if (state.error != null && state.error != "consent") Text(state.error!!, color = LauncherTheme.colors.danger)

        SectionHeader(stringResource(Res.string.vpn_tunnels))
        tunnels.forEachIndexed { index, name ->
            val isUp = state.status == VpnStatus.UP && state.tunnel == name
            SwitchRow(
                name, isUp,
                onChange = { on -> if (on) connect(name) else scope.launch { vpn.disconnect() } },
                subtitle = if (settings.activeTunnel == name) stringResource(Res.string.vpn_default_tunnel) else null,
                icon = Icons.Filled.VpnKey,
                initialFocus = index == 0,
            )
        }
        if (tunnels.size > 1) {
            ChoiceRow(
                stringResource(Res.string.vpn_default_tunnel), tunnels, settings.activeTunnel.ifEmpty { tunnels.first() },
                label = { it }, onSelect = { v -> update { it.copy(activeTunnel = v) } },
            )
        }
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(Res.string.vpn_import_file), icon = Icons.Filled.FileOpen, primary = tunnels.isEmpty(), onClick = { openFile.launch(arrayOf("*/*")) })
            LauncherButton(stringResource(Res.string.vpn_import_clipboard), icon = Icons.Filled.ContentPaste, onClick = {
                val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                importText("wg${tunnels.size}", text)
            })
            tunnels.firstOrNull { it == settings.activeTunnel }?.let { name ->
                HoldToConfirmButton(stringResource(Res.string.vpn_delete, name), { vpn.delete(name) }, icon = Icons.Filled.Delete)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted) }
        Text(stringResource(Res.string.vpn_import_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)

        SectionHeader(stringResource(Res.string.vpn_auto))
        SwitchRow(
            stringResource(Res.string.vpn_auto_unreachable), AutoVpnTrigger.HOST_UNREACHABLE in settings.autoVpnTriggers,
            { on -> update { it.copy(autoVpnTriggers = if (on) it.autoVpnTriggers + AutoVpnTrigger.HOST_UNREACHABLE else it.autoVpnTriggers - AutoVpnTrigger.HOST_UNREACHABLE) } },
            subtitle = stringResource(Res.string.vpn_auto_unreachable_sub), icon = Icons.Filled.VpnLock,
        )
        SwitchRow(
            stringResource(Res.string.vpn_auto_outside), AutoVpnTrigger.OUTSIDE_HOME in settings.autoVpnTriggers,
            { on ->
                if (on) locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                else update { it.copy(autoVpnTriggers = it.autoVpnTriggers - AutoVpnTrigger.OUTSIDE_HOME) }
            },
            subtitle = stringResource(Res.string.vpn_auto_outside_sub), icon = Icons.Filled.Wifi,
        )
        if (AutoVpnTrigger.OUTSIDE_HOME in settings.autoVpnTriggers) {
            SwitchRow(stringResource(Res.string.vpn_disconnect_home), settings.vpnDisconnectAtHome, { v -> update { it.copy(vpnDisconnectAtHome = v) } })
            settings.homeSsids.sorted().forEach { home ->
                NavRow(home, { update { it.copy(homeSsids = it.homeSsids - home) } }, stringResource(Res.string.vpn_home_remove), Icons.Filled.Wifi)
            }
            ssid?.takeIf { it !in settings.homeSsids }?.let { current ->
                LauncherButton(stringResource(Res.string.vpn_add_home, current), icon = Icons.Filled.Add, onClick = { update { it.copy(homeSsids = it.homeSsids + current) } })
            }
            if (ssid == null) Text(stringResource(Res.string.vpn_ssid_unknown), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}
