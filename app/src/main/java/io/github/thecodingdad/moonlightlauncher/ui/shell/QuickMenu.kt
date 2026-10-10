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

        Text(stringResource(R.string.quick_menu), style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface)
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(max = 2000.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // What the status bar shows, reachable with the controller too.
            StatusEntries(onHost = onHost, onControllers = onControllers)
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
            } else {
                LauncherButton(stringResource(R.string.qm_brightness_permission), icon = Icons.Filled.BrightnessMedium, onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LauncherButton(stringResource(R.string.qm_wifi), icon = Icons.Filled.Wifi, onClick = {
                    context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
                })
                LauncherButton(stringResource(R.string.qm_bluetooth), icon = Icons.Filled.Bluetooth, onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                })
                LauncherButton(stringResource(R.string.qm_android_settings), icon = Icons.Filled.Settings, onClick = {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                })
            }
            if (tunnels.isNotEmpty() && c.vpn.consentIntent() == null) {
                SwitchRow(
                    stringResource(R.string.settings_vpn), vpnState.status == VpnStatus.UP,
                    { scope.launch { c.vpn.toggle() } }, icon = Icons.Filled.VpnLock,
                    subtitle = vpnState.tunnel,
                )
            }
            if (profiles.isNotEmpty()) {
                ChoiceRow(
                    stringResource(R.string.launch_profile), profiles, profiles.firstOrNull { it.uuid == activeProfile.uuid } ?: profiles.first(),
                    label = { it.name }, onSelect = onProfile,
                )
            }
            running.forEach { session ->
                SectionHeader(stringResource(R.string.qm_running_on, session.appName, session.host.name))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LauncherButton(stringResource(R.string.qm_resume), icon = Icons.Filled.PlayArrow, primary = true, onClick = { onDismiss(); onResume(session) })
                    HoldToConfirmButton(stringResource(R.string.qm_quit_game), {
                        scope.launch { withContext(Dispatchers.IO) { runCatching { c.hosts.http(session.host).quitApp() } } }
                    }, icon = Icons.Filled.StopCircle)
                }
            }
            HostPowerButtons()
            HomeAssistantSection(openHaSettings)
        }
    }
}

/** Controllers and hosts as colored entries (same colors as the status bar icons). */
@Composable
private fun StatusEntries(onHost: (String) -> Unit, onControllers: () -> Unit) {
    val c = LocalContext.current.container
    val colors = LauncherTheme.colors
    val hosts by c.hosts.hosts.collectAsState()
    val controllers = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers()
    QuickEntry(
        title = stringResource(R.string.settings_controllers),
        subtitle = if (controllers.isEmpty()) stringResource(R.string.controllers_none)
        else controllers.joinToString(", ") { it.name },
        icon = Icons.Filled.SportsEsports,
        tint = if (controllers.isEmpty()) colors.danger else colors.success,
        onClick = onControllers,
        initialFocus = true,
    )
    hosts.values.sortedBy { it.name }.forEach { host ->
        QuickEntry(
            title = host.name,
            subtitle = stringResource(
                when {
                    host.isStreaming -> R.string.host_status_streaming
                    host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.ONLINE -> R.string.host_status_online
                    host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.OFFLINE -> R.string.host_status_offline
                    else -> R.string.host_status_unknown
                },
            ),
            icon = Icons.Filled.Computer,
            tint = when {
                host.isStreaming -> colors.accent
                host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.ONLINE -> colors.success
                host.status == io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus.OFFLINE -> colors.danger
                else -> colors.onSurfaceMuted
            },
            onClick = { onHost(host.uuid) },
        )
    }
}

@Composable
private fun QuickEntry(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    initialFocus: Boolean = false,
) {
    io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(
            if (initialFocus) Modifier.restoreFocusIf(true) else Modifier,
        ),
        color = LauncherTheme.colors.surfaceRaised,
        focusScale = 1.02f,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted, maxLines = 1)
            }
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
    SectionHeader(stringResource(R.string.settings_home_assistant))
    if (connection == io.github.thecodingdad.moonlightlauncher.ha.HaConnection.NOT_CONFIGURED || tiles.isEmpty()) {
        LauncherButton(stringResource(R.string.ha_setup), icon = Icons.Filled.Settings, onClick = openSettings)
    } else {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tiles.forEach { tile -> io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaTile(tile, entities[tile.entityId], width = 220.dp) }
        }
    }
}

/** Sleep / shut down buttons for every host that has HA power actions configured. */
@Composable
private fun HostPowerButtons() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val hosts by c.hosts.hosts.collectAsState()
    var configs by remember { mutableStateOf<List<Pair<String, io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity>>>(emptyList()) }
    LaunchedEffect(hosts.keys) { configs = hosts.values.map { it.name to c.hostPower.config(it.uuid) } }
    configs.filter { (_, cfg) -> cfg.haSleepEntity != null || cfg.haShutdownEntity != null }.forEach { (name, cfg) ->
        SectionHeader(name)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cfg.haSleepEntity != null) HoldToConfirmButton(stringResource(R.string.power_sleep), { scope.launch { c.hostPower.power(cfg.hostUuid, PowerAction.SLEEP) } }, icon = Icons.Filled.Bedtime)
            if (cfg.haShutdownEntity != null) HoldToConfirmButton(stringResource(R.string.power_shutdown), { scope.launch { c.hostPower.power(cfg.hostUuid, PowerAction.SHUTDOWN) } }, icon = Icons.Filled.PowerSettingsNew)
        }
    }
}
