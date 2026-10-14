package io.github.thecodingdad.moonlightlauncher.ui.shell

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.stream.PowerAction
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.StatusDot
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import androidx.compose.ui.graphics.Color
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.InfoRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

/** Small popup from the status bar's host icon: state, address, running game, quick actions. */
@Composable
fun HostInfoPopup(
    host: HostState?,
    runningGame: String?,
    onDismiss: () -> Unit,
    openSettings: (HostState) -> Unit,
    profiles: List<io.github.thecodingdad.moonlightlauncher.stream.StreamProfile> = emptyList(),
    activeProfile: io.github.thecodingdad.moonlightlauncher.stream.StreamProfile? = null,
    onProfile: (io.github.thecodingdad.moonlightlauncher.stream.StreamProfile) -> Unit = {},
    onEditProfiles: () -> Unit = {},
) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<HostConfigEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host?.uuid) {
        message = null
        config = host?.let { c.hostPower.config(it.uuid) }
    }
    val wakeSent = stringResource(R.string.host_wake_sent)
    Modal(visible = host != null, onDismiss = onDismiss, title = host?.name, maxWidth = 560.dp) {
        val h = host ?: return@Modal
        val colors = LauncherTheme.colors
        Column(Modifier.heightIn(max = 600.dp).controllerScroll()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(
                when {
                    h.isStreaming -> colors.accent
                    h.status == HostStatus.ONLINE -> colors.success
                    h.status == HostStatus.OFFLINE -> colors.danger
                    else -> colors.onSurfaceMuted
                },
            )
            Text(
                when {
                    h.isStreaming -> stringResource(R.string.host_state_streaming, runningGame ?: "?")
                    h.status == HostStatus.ONLINE -> stringResource(R.string.host_state_online)
                    h.status == HostStatus.OFFLINE -> stringResource(R.string.host_state_offline)
                    else -> stringResource(R.string.host_state_unknown)
                },
                style = MaterialTheme.typography.titleMedium, color = colors.onSurface,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        h.address?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Pairing is only known once the host answered.
            // Only worth a line when something is wrong.
            if (h.status == HostStatus.ONLINE && !h.paired) {
                InfoRow(stringResource(R.string.host_info_paired), stringResource(R.string.no))
            }
            if (h.missingPermissions.isNotEmpty()) {
                InfoRow(stringResource(R.string.host_info_permissions), stringResource(R.string.host_info_permissions_missing))
            }
        }
        // Stream profile used for the next streams (global, "start with…" overrides it per game).
        if (profiles.isNotEmpty() && activeProfile != null) {
            val context = LocalContext.current
            Spacer(Modifier.height(12.dp))
            // One compact row: D-pad left/right or A cycles; editing lives in the settings.
            io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow(
                stringResource(R.string.profile_picker_title), profiles,
                profiles.firstOrNull { it.uuid == activeProfile.uuid } ?: profiles.first(),
                label = { it.name }, onSelect = onProfile,
                subtitle = profileSummary(context, activeProfile.uuid),
                icon = Icons.Filled.Tune,
            )
        }
        // Auto sleep after a stream, editable right here (same options as in the host settings).
        config?.let { cfg ->
            io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ChoiceRow(
                stringResource(R.string.host_auto_sleep), listOf<Int?>(null, 5, 10, 15, 30, 60), cfg.autoSleepMinutes,
                label = { it?.let { m -> stringResource(R.string.minutes_short, m) } ?: stringResource(R.string.off) },
                onSelect = { minutes ->
                    val updated = cfg.copy(autoSleepMinutes = minutes)
                    config = updated
                    scope.launch { c.hostPower.save(updated) }
                },
                icon = Icons.Filled.Bedtime,
            )
        }
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (h.status != HostStatus.ONLINE) {
                LauncherButton(
                    stringResource(R.string.host_wake), icon = Icons.Filled.WbSunny, primary = true, modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { c.hostPower.wake(h.uuid); message = wakeSent } },
                )
            } else {
                config?.let { cfg ->
                    if (cfg.haSleepEntity != null) {
                        HoldToConfirmButton(
                            stringResource(R.string.power_sleep), { scope.launch { c.hostPower.power(h.uuid, PowerAction.SLEEP); onDismiss() } },
                            Modifier.fillMaxWidth(), Icons.Filled.Bedtime,
                        )
                    }
                    if (cfg.haShutdownEntity != null) {
                        HoldToConfirmButton(
                            stringResource(R.string.power_shutdown), { scope.launch { c.hostPower.power(h.uuid, PowerAction.SHUTDOWN); onDismiss() } },
                            Modifier.fillMaxWidth(), Icons.Filled.PowerSettingsNew,
                        )
                    }
                }
            }
            LauncherButton(
                stringResource(R.string.host_settings), icon = Icons.Filled.Settings, modifier = Modifier.fillMaxWidth(),
                onClick = { onDismiss(); openSettings(h) },
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted) }
        }
        }
    }
}

/** [HostInfoPopup] target meaning "all hosts": the list of them. */
const val ALL_HOSTS = "*"

/** From two hosts on, the status bar and the quick menu show one combined icon for all of them. */
fun showHostsCombined(hosts: Collection<HostState>): Boolean = hosts.size >= 2

/** Colour of a host icon; combined, the "best" state wins (streaming, then online, then offline). */
fun hostTint(hosts: Collection<HostState>, streaming: Color, online: Color, offline: Color, unknown: Color): Color = when {
    hosts.any { it.isStreaming } -> streaming
    hosts.any { it.status == HostStatus.ONLINE } -> online
    hosts.isNotEmpty() && hosts.all { it.status == HostStatus.OFFLINE } -> offline
    else -> unknown
}

/** Every host with its state; selecting one opens its [HostInfoPopup]. */
@Composable
fun HostListPopup(
    visible: Boolean,
    hosts: Collection<HostState>,
    runningGame: (String) -> String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(R.string.settings_hosts), maxWidth = 560.dp) {
        val colors = LauncherTheme.colors
        Column(Modifier.heightIn(max = 600.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.sortedBy { it.name.lowercase() }.forEach { h ->
                FocusSurface(onClick = { onSelect(h.uuid) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusDot(hostTint(listOf(h), colors.accent, colors.success, colors.danger, colors.onSurfaceMuted))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1)
                            Text(
                                when {
                                    h.isStreaming -> stringResource(R.string.host_state_streaming, runningGame(h.uuid) ?: "?")
                                    h.status == HostStatus.ONLINE -> stringResource(R.string.host_state_online)
                                    h.status == HostStatus.OFFLINE -> stringResource(R.string.host_state_offline)
                                    else -> stringResource(R.string.host_state_unknown)
                                },
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
