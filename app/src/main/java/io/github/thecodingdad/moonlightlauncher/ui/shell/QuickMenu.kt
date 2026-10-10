package io.github.thecodingdad.moonlightlauncher.ui.shell

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.stream.PowerAction
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import io.github.thecodingdad.moonlightlauncher.ui.RunningSession
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.SidePanel
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SliderRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import io.github.thecodingdad.moonlightlauncher.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Select (hold) anywhere in the launcher: system and streaming shortcuts. */
@Composable
fun QuickMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    profiles: List<StreamProfile>,
    activeProfile: StreamProfile,
    onProfile: (StreamProfile) -> Unit,
    running: List<RunningSession>,
    onResume: (RunningSession) -> Unit,
    onHost: (String) -> Unit = {},
    onControllers: () -> Unit = {},
    openHaSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val vpnState by c.vpn.state.collectAsState()
    val tunnels by c.vpn.tunnels.collectAsState()

    SidePanel(visible = visible, onDismiss = onDismiss, width = 520.dp) {
        val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        var volume by remember(visible) { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        val canWriteSettings = remember(visible) { Settings.System.canWrite(context) }
        var brightness by remember(visible) {
            mutableIntStateOf(runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128))
        }

        // Two tabs: quick settings and Home Assistant; LB/RB switch while the menu is open.
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
        io.github.thecodingdad.moonlightlauncher.ui.input.PadAction(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.L1) { tab = 0 }
        io.github.thecodingdad.moonlightlauncher.ui.input.PadAction(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.R1) { tab = 1 }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            io.github.thecodingdad.moonlightlauncher.ui.components.PadGlyph(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.L1)
            io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(stringResource(R.string.qm_tab_quick), selected = tab == 0, onClick = { tab = 0 })
            io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(stringResource(R.string.settings_home_assistant), selected = tab == 1, onClick = { tab = 1 })
            io.github.thecodingdad.moonlightlauncher.ui.components.PadGlyph(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.R1)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 12.dp))
        if (tab == 1) {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 2000.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeAssistantSection(openHaSettings)
            }
            return@SidePanel
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(max = 2000.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Status: what the status bar shows, as compact tiles (reachable with the controller).
            StatusTiles(onHost = onHost, onControllers = onControllers, vpnAvailable = tunnels.isNotEmpty() && c.vpn.consentIntent() == null)

            // 2. Running game: resume or quit (power and profile live in the host popup).
            running.forEach { session ->
                SectionHeader(stringResource(R.string.qm_running_on, session.appName, session.host.name))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LauncherButton(
                        stringResource(R.string.qm_resume), icon = Icons.Filled.PlayArrow, primary = true,
                        onClick = { onDismiss(); onResume(session) }, modifier = Modifier.weight(1f),
                    )
                    HoldToConfirmButton(stringResource(R.string.qm_quit_game), {
                        scope.launch { withContext(Dispatchers.IO) { runCatching { c.hosts.http(session.host).quitApp() } } }
                    }, icon = Icons.Filled.StopCircle, modifier = Modifier.weight(1f))
                }
            }

            // 3. System.
            SectionHeader(stringResource(R.string.qm_system))
            SliderRow(
                stringResource(R.string.qm_volume), volume, 0..maxVolume, 1,
                onChange = { v -> volume = v; audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) },
                valueLabel = { "${it * 100 / maxVolume.coerceAtLeast(1)} %" }, icon = Icons.Filled.VolumeUp,
            )
            if (canWriteSettings) {
                SliderRow(
                    stringResource(R.string.qm_brightness), brightness, 1..255, 8,
                    onChange = { v ->
                        brightness = v
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                    },
                    valueLabel = { "${it * 100 / 255} %" }, icon = Icons.Filled.BrightnessMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconAction(Icons.Filled.Wifi, stringResource(R.string.qm_wifi)) { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
                IconAction(Icons.Filled.Bluetooth, stringResource(R.string.qm_bluetooth)) { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                if (!canWriteSettings) {
                    IconAction(Icons.Filled.BrightnessMedium, stringResource(R.string.qm_brightness_permission)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
                IconAction(Icons.Filled.Settings, stringResource(R.string.qm_android_settings)) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }

        }
    }
}

/** Icon-only action button (label as content description, shown by accessibility services). */
@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = LauncherTheme.colors.surfaceRaised,
        focusScale = 1.1f,
    ) {
        androidx.compose.material3.Icon(icon, label, tint = LauncherTheme.colors.onSurface, modifier = Modifier.align(Alignment.Center).size(26.dp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Controllers, hosts and VPN as small tiles in one row; colors as in the status bar. */
@Composable
private fun StatusTiles(onHost: (String) -> Unit, onControllers: () -> Unit, vpnAvailable: Boolean) {
    val c = LocalContext.current.container
    val colors = LauncherTheme.colors
    val scope = rememberCoroutineScope()
    val hosts by c.hosts.hosts.collectAsState()
    val vpnState by c.vpn.state.collectAsState()
    val controllers = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers()
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusTile(
            icon = Icons.Filled.SportsEsports,
            label = if (controllers.isEmpty()) stringResource(R.string.qm_no_controller) else stringResource(R.string.qm_controllers, controllers.size),
            tint = if (controllers.isEmpty()) colors.danger else colors.success,
            onClick = onControllers,
            initialFocus = true,
        )
        hosts.values.sortedBy { it.name }.forEach { host ->
            StatusTile(
                icon = Icons.Filled.Computer,
                label = host.name,
                tint = when {
                    host.isStreaming -> colors.accent
                    host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.ONLINE -> colors.success
                    host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.OFFLINE -> colors.danger
                    else -> colors.onSurfaceMuted
                },
                onClick = { onHost(host.uuid) },
            )
        }
        if (vpnAvailable) {
            val up = vpnState.status == io.github.thecodingdad.moonlightlauncher.vpn.VpnStatus.UP
            StatusTile(
                icon = Icons.Filled.VpnLock,
                label = stringResource(if (up) R.string.qm_vpn_on else R.string.qm_vpn_off),
                tint = if (up) colors.success else colors.onSurfaceMuted,
                onClick = { scope.launch { c.vpn.toggle() } },
            )
        }
    }
}

@Composable
private fun StatusTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    initialFocus: Boolean = false,
) {
    io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(140.dp).then(if (initialFocus) Modifier.restoreFocusIf(true) else Modifier),
        color = LauncherTheme.colors.surfaceRaised,
        focusScale = 1.05f,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = LauncherTheme.colors.onSurface,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Home Assistant tiles (formerly a panel of its own). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HomeAssistantSection(openSettings: () -> Unit) {
    val ha = LocalContext.current.container.ha
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    val connection by ha.client.connection.collectAsState()
    LaunchedEffect(Unit) { ha.client.start() }
    if (connection == io.github.thecodingdad.moonlightlauncher.ha.HaConnection.NOT_CONFIGURED || tiles.isEmpty()) {
        Text(stringResource(R.string.ha_panel_empty), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        LauncherButton(stringResource(R.string.ha_setup), icon = Icons.Filled.Settings, onClick = openSettings, modifier = Modifier.restoreFocusIf(true))
    } else {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tiles.forEachIndexed { i, tile ->
                io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaTile(
                    tile, entities[tile.entityId], Modifier.restoreFocusIf(i == 0), width = 220.dp,
                )
            }
        }
    }
}

