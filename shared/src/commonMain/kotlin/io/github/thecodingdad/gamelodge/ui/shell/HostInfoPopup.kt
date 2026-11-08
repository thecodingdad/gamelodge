package io.github.thecodingdad.gamelodge.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.HostConfigEntity
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.stream.PowerAction
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import io.github.thecodingdad.gamelodge.ui.components.StatusDot
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import androidx.compose.ui.graphics.Color
import io.github.thecodingdad.gamelodge.ui.screens.settings.InfoRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

/** One host in the quick menu's hosts tab: state, address, auto sleep, wake and power actions. */
@Composable
fun HostCard(
    host: HostState,
    runningGame: String?,
    openSettings: (HostState) -> Unit,
    initialFocus: Boolean,
) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<HostConfigEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pickSleep by remember { mutableStateOf(false) }
    LaunchedEffect(host.uuid) {
        message = null
        config = c.hostPower.config(host.uuid)
    }
    val wakeSent = stringResource(Res.string.host_wake_sent)
    val h = host
    val colors = LauncherTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(LauncherTheme.dimens.cornerRadius))
            .background(colors.surfaceRaised).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            io.github.thecodingdad.gamelodge.ui.components.HostIcon(h.uuid.startsWith("xbox:"), hostTint(listOf(h), HostStreamingColor, colors.success, colors.danger, colors.onSurfaceMuted), size = 24.dp)
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1)
                Text(
                    when {
                        h.isStreaming -> stringResource(Res.string.host_state_streaming, runningGame ?: "?")
                        h.status == HostStatus.ONLINE -> stringResource(Res.string.host_state_online)
                        h.status == HostStatus.OFFLINE -> stringResource(Res.string.host_state_offline)
                        else -> stringResource(Res.string.host_state_unknown)
                    } + (h.address?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // Only worth a line when something is wrong (pairing is only known once the host answered).
        if (h.status == HostStatus.ONLINE && !h.paired) {
            InfoRow(stringResource(Res.string.host_info_paired), stringResource(Res.string.no))
        }
        if (h.missingPermissions.isNotEmpty()) {
            InfoRow(stringResource(Res.string.host_info_permissions), stringResource(Res.string.host_info_permissions_missing))
        }
        Spacer(Modifier.height(12.dp))
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            if (h.status != HostStatus.ONLINE) {
                LauncherButton(
                    stringResource(Res.string.host_wake), icon = Icons.Filled.WbSunny, primary = true,
                    modifier = Modifier.restoreFocusIf(initialFocus),
                    onClick = { scope.launch { c.hostPower.wake(h.uuid); message = wakeSent } },
                )
            } else {
                config?.let { cfg ->
                    if (cfg.haSleepEntity != null) {
                        HoldToConfirmButton(
                            stringResource(Res.string.power_sleep), { scope.launch { c.hostPower.power(h.uuid, PowerAction.SLEEP) } },
                            Modifier.restoreFocusIf(initialFocus), Icons.Filled.Bedtime,
                        )
                    }
                    if (cfg.haShutdownEntity != null) {
                        HoldToConfirmButton(
                            stringResource(Res.string.power_shutdown), { scope.launch { c.hostPower.power(h.uuid, PowerAction.SHUTDOWN) } },
                            icon = Icons.Filled.PowerSettingsNew,
                        )
                    } else if (cfg.haSleepEntity == null && h.uuid.startsWith("xbox:")) {
                        // A console turns off (standby) through Xbox Live.
                        HoldToConfirmButton(
                            stringResource(Res.string.xbox_turn_off), { scope.launch { c.hostPower.power(h.uuid, PowerAction.SHUTDOWN) } },
                            Modifier.restoreFocusIf(initialFocus), Icons.Filled.PowerSettingsNew,
                        )
                    }
                }
            }
            // Auto sleep after a stream: its value on the button, the choice in a popup.
            config?.let { cfg ->
                LauncherButton(
                    cfg.autoSleepMinutes?.let { m -> stringResource(Res.string.minutes_short, m) } ?: stringResource(Res.string.off),
                    icon = Icons.Filled.Bedtime,
                    onClick = { pickSleep = true },
                )
            }
            io.github.thecodingdad.gamelodge.ui.components.TooltipIconButton(
                Icons.Filled.Settings, stringResource(Res.string.host_settings), { openSettings(h) },
                Modifier.restoreFocusIf(initialFocus && h.status == HostStatus.ONLINE && config?.haSleepEntity == null && config?.haShutdownEntity == null && !h.uuid.startsWith("xbox:")),
            )
        }
        // The same choice list as the settings' (compact, scrolls with every input).
        val cfg = config
        if (pickSleep && cfg != null) {
            val options = listOf<Int?>(null, 5, 10, 15, 30, 60)
            val off = stringResource(Res.string.off)
            val labels = options.map { m -> m?.let { stringResource(Res.string.minutes_short, it) } ?: off }
            io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceList(
                stringResource(Res.string.host_auto_sleep), labels, options.indexOf(cfg.autoSleepMinutes).coerceAtLeast(0), null,
                onPick = { i ->
                    val updated = cfg.copy(autoSleepMinutes = options[i])
                    config = updated
                    pickSleep = false
                    scope.launch { c.hostPower.save(updated) }
                },
            ) { pickSleep = false }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted) }
    }
}

/** Host streaming: blue in every theme (red = offline, green = online; not the accent colour). */
val HostStreamingColor = Color(0xFF42A5F5)

/** Colour of the hosts' icon: the "best" state wins (streaming, then online, then offline). */
fun hostTint(hosts: Collection<HostState>, streaming: Color, online: Color, offline: Color, unknown: Color): Color = when {
    hosts.any { it.isStreaming } -> streaming
    hosts.any { it.status == HostStatus.ONLINE } -> online
    hosts.isNotEmpty() && hosts.all { it.status == HostStatus.OFFLINE } -> offline
    else -> unknown
}
