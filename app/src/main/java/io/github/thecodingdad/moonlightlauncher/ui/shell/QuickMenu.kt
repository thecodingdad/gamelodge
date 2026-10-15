package io.github.thecodingdad.moonlightlauncher.ui.shell

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
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
import androidx.compose.material.icons.filled.SettingsEthernet
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
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.ui.unit.dp
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
import io.github.thecodingdad.moonlightlauncher.resources.*

/** Select (hold) anywhere in the launcher: system and streaming shortcuts. */
@Composable
fun QuickMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    profiles: List<StreamProfile>,
    activeProfile: StreamProfile,
    onProfile: (StreamProfile) -> Unit,
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

    SidePanel(visible = visible, onDismiss = onDismiss, width = 580.dp) {
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
        // LB/RB glyphs only with a controller connected (touch only: just the tabs).
        val padConnected = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers().isNotEmpty()
        // Back arrow on the left (touch / mouse), the tabs centred.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            io.github.thecodingdad.moonlightlauncher.ui.components.CloseArrow(onDismiss)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                if (padConnected) io.github.thecodingdad.moonlightlauncher.ui.components.PadGlyph(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.L1)
                PanelTab(stringResource(Res.string.qm_tab_quick), selected = tab == 0, onClick = { tab = 0 })
                PanelTab(stringResource(Res.string.settings_home_assistant), selected = tab == 1, onClick = { tab = 1 })
                if (padConnected) io.github.thecodingdad.moonlightlauncher.ui.components.PadGlyph(io.github.thecodingdad.moonlightlauncher.ui.input.PadButton.R1)
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 4.dp))
        if (tab == 1) {
            Column(Modifier.weight(1f).fillMaxWidth().controllerScroll().padding(vertical = 22.dp, horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeAssistantSection(openHaSettings)
            }
            return@SidePanel
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().controllerScroll().padding(vertical = 22.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Status and shortcuts as tiles: controllers, hosts, VPN, Wi-Fi, Bluetooth, Android settings.
            // Configurable tiles (Settings -> Quick menu), at most two rows, scrolling sideways.
            val tileIds by c.settings.settings.collectAsState(io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings())
            QuickTileGrid(rememberQuickTiles(tileIds.quickTiles, onHost = onHost, onControllers = onControllers))
            if (!canWriteSettings) {
                LauncherButton(stringResource(Res.string.qm_brightness_permission), icon = Icons.Filled.BrightnessMedium, onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                })
            }

            SectionHeader(stringResource(Res.string.qm_system))
            SliderRow(
                stringResource(Res.string.qm_volume), volume, 0..maxVolume, 1,
                onChange = { v -> volume = v; audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) },
                valueLabel = { "${it * 100 / maxVolume.coerceAtLeast(1)} %" }, icon = Icons.Filled.VolumeUp,
            )
            if (canWriteSettings) {
                SliderRow(
                    stringResource(Res.string.qm_brightness), brightness, 1..255, 8,
                    onChange = { v ->
                        brightness = v
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                    },
                    valueLabel = { "${it * 100 / 255} %" }, icon = Icons.Filled.BrightnessMedium,
                )
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
    if (connection == io.github.thecodingdad.moonlightlauncher.ha.HaConnection.NOT_CONFIGURED || tiles.isEmpty()) {
        Text(stringResource(Res.string.ha_panel_empty), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        LauncherButton(stringResource(Res.string.ha_setup), icon = Icons.Filled.Settings, onClick = openSettings, modifier = Modifier.restoreFocusIf(true))
    } else {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tiles.forEachIndexed { i, tile ->
                io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaTile(
                    tile, entities[tile.entityId], Modifier.restoreFocusIf(i == 0), width = 210.dp,
                )
            }
        }
    }
}


/** Tab header like the launcher's main tabs: touch only (LB/RB switch with the controller). */
@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LauncherTheme.colors
    io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = if (selected) colors.onSurface else androidx.compose.ui.graphics.Color.Transparent,
        focusScale = 1f,
        dpadFocusable = false,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.background else colors.onSurfaceMuted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}
