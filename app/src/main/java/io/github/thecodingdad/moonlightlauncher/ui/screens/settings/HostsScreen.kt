package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.remote.AddHostResult
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.data.remote.PairResult
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.StatusDot
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed interface PairUi {
    data class Waiting(val host: HostState, val pin: String) : PairUi
    data class Done(val host: HostState, val result: PairResult) : PairUi
}

@Composable
fun HostsSettings(hosts: List<HostState>, openHostConfig: (HostState) -> Unit) {
    val context = LocalContext.current
    val actions = context.container.hostActions
    val scope = rememberCoroutineScope()
    val keyboard = LocalKeyboardController.current
    var pairUi by remember { mutableStateOf<PairUi?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // New hosts are only searched for while this screen is open.
    DisposableEffect(Unit) {
        context.container.hosts.setDiscovery(true)
        onDispose { context.container.hosts.setDiscovery(false) }
    }

    fun startPairing(host: HostState) {
        val pin = actions.generatePin()
        pairUi = PairUi.Waiting(host, pin)
        scope.launch {
            val result = actions.pair(host, pin)
            pairUi = PairUi.Done(host, result)
        }
    }

    val c = androidx.compose.ui.platform.LocalContext.current.container
    val waking = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val notes = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val hostConfigList by androidx.compose.runtime.remember { c.database.hostConfigDao().observeAll() }.collectAsState(emptyList())
    val hostConfigs = hostConfigList.associateBy { it.hostUuid }
    val wakeSentMsg = stringResource(R.string.host_wake_sending)
    val wakeFailedMsg = stringResource(R.string.host_wake_failed)
    val wakeOnlineMsg = stringResource(R.string.host_wake_online)
    val wakeTimeoutMsg = stringResource(R.string.host_wake_timeout)
    val addTitle = stringResource(R.string.host_add_title)
    val addedMsg = stringResource(R.string.host_added)
    val invalidMsg = stringResource(R.string.host_invalid_address)
    val unreachableMsg = stringResource(R.string.host_unreachable)

    SettingsPage(stringResource(R.string.settings_hosts)) {
        if (hosts.isEmpty()) {
            Text(stringResource(R.string.hosts_empty), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurfaceMuted)
        }
        hosts.sortedBy { it.name }.forEachIndexed { index, host ->
            HostCard(
                host = host,
                initialFocus = index == 0,
                onPair = { startPairing(host) },
                waking = host.uuid in waking && host.status != HostStatus.ONLINE,
                note = notes[host.uuid],
                power = hostConfigs[host.uuid],
                onPower = { action -> scope.launch { c.hostPower.power(host.uuid, action) } },
                onWake = {
                    // Uses the configured wake method (WoL and/or Home Assistant), with feedback
                    // until the host answers.
                    waking.add(host.uuid)
                    notes[host.uuid] = wakeSentMsg
                    scope.launch {
                        val sent = c.hostPower.wake(host.uuid)
                        if (!sent) {
                            waking.remove(host.uuid)
                            notes[host.uuid] = wakeFailedMsg
                            return@launch
                        }
                        c.hosts.refreshAppLists()
                        val online = kotlinx.coroutines.withTimeoutOrNull(180_000) {
                            c.hosts.hosts.first { it[host.uuid]?.status == HostStatus.ONLINE }
                        } != null
                        waking.remove(host.uuid)
                        notes[host.uuid] = if (online) wakeOnlineMsg else wakeTimeoutMsg
                    }
                },
                onUnpair = { scope.launch { actions.unpair(host) } },
                onRemove = { scope.launch { actions.remove(host) } },
                onConfig = { openHostConfig(host) },
            )
        }
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(
                stringResource(R.string.host_add), icon = Icons.Filled.Add, primary = hosts.isEmpty(),
                onClick = {
                    keyboard.open(addTitle, "", TextKind.URL) { address ->
                        adding = true
                        scope.launch {
                            message = when (actions.addHost(address)) {
                                AddHostResult.ADDED -> addedMsg
                                AddHostResult.INVALID_ADDRESS -> invalidMsg
                                AddHostResult.UNREACHABLE -> unreachableMsg
                            }
                            adding = false
                        }
                    }
                },
            )
            LauncherButton(stringResource(R.string.hosts_refresh), icon = Icons.Filled.Refresh, onClick = { context.container.hosts.refreshAppLists() })
        }
        if (adding) CircularProgressIndicator(color = LauncherTheme.colors.accent)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted) }
        Text(stringResource(R.string.hosts_discovery_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    }

    val ui = pairUi
    Modal(visible = ui != null, onDismiss = { pairUi = null }, title = stringResource(R.string.pair_title)) {
        when (ui) {
            is PairUi.Waiting -> {
                Text(stringResource(R.string.pair_instructions, ui.host.name), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                Spacer(Modifier.height(20.dp))
                Text(
                    ui.pin,
                    fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 12.sp,
                    color = LauncherTheme.colors.accent,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.pair_waiting), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                }
                Spacer(Modifier.height(16.dp))
                LauncherButton(stringResource(R.string.action_cancel), onClick = { pairUi = null })
            }
            is PairUi.Done -> {
                Text(
                    stringResource(
                        when (ui.result) {
                            PairResult.PAIRED -> R.string.pair_success
                            PairResult.WRONG_PIN -> R.string.pair_wrong_pin
                            PairResult.IN_GAME -> R.string.pair_in_game
                            PairResult.ALREADY_IN_PROGRESS -> R.string.pair_in_progress
                            PairResult.OFFLINE -> R.string.error_host_offline
                            PairResult.FAILED -> R.string.pair_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface,
                )
                Spacer(Modifier.height(20.dp))
                LauncherButton(stringResource(R.string.action_close), primary = true, onClick = { pairUi = null })
            }
            null -> Unit
        }
    }
}

@Composable
private fun HostCard(
    host: HostState,
    initialFocus: Boolean,
    waking: Boolean,
    note: String?,
    power: io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity?,
    onPower: (io.github.thecodingdad.moonlightlauncher.stream.PowerAction) -> Unit,
    onPair: () -> Unit,
    onWake: () -> Unit,
    onUnpair: () -> Unit,
    onRemove: () -> Unit,
    onConfig: () -> Unit,
) {
    val colors = LauncherTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    host.isStreaming -> colors.accent
                    host.status == HostStatus.ONLINE -> colors.success
                    host.status == HostStatus.OFFLINE -> colors.danger
                    else -> colors.onSurfaceMuted
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Text(
                    listOfNotNull(
                        stringResource(
                            when {
                                host.isStreaming -> R.string.host_status_streaming
                                host.status == HostStatus.ONLINE -> R.string.host_status_online
                                host.status == HostStatus.OFFLINE -> R.string.host_status_offline
                                else -> R.string.host_status_unknown
                            },
                        ),
                        // Pairing is only known while the host answers.
                        if (host.status == HostStatus.ONLINE) stringResource(if (host.paired) R.string.host_paired else R.string.host_not_paired) else null,
                        host.address,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                )
            }
        }
        if (host.paired) MissingPermissionsNote(host)
        val online = host.status == HostStatus.ONLINE
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 10.dp) {
            if (online && !host.paired) {
                LauncherButton(
                    stringResource(R.string.host_pair), icon = Icons.Filled.Link, primary = true, onClick = onPair,
                    modifier = Modifier.restoreFocusIf(initialFocus),
                )
            }
            LauncherButton(
                stringResource(R.string.host_settings), icon = Icons.Filled.Settings, onClick = onConfig,
                modifier = if (online && !host.paired) Modifier else Modifier.restoreFocusIf(initialFocus),
            )
            if (!online) {
                LauncherButton(
                    stringResource(if (waking) R.string.host_waking else R.string.host_wake), icon = Icons.Filled.Power,
                    onClick = onWake, enabled = !waking, primary = waking,
                )
            }
            // Power actions (Home Assistant), only for a paired host that is on, when configured.
            if (online && host.paired && power != null) {
                if (power.haSleepEntity != null) HoldToConfirmButton(stringResource(R.string.power_sleep), { onPower(io.github.thecodingdad.moonlightlauncher.stream.PowerAction.SLEEP) }, icon = Icons.Filled.Bedtime)
                if (power.haRestartEntity != null) HoldToConfirmButton(stringResource(R.string.power_restart), { onPower(io.github.thecodingdad.moonlightlauncher.stream.PowerAction.RESTART) }, icon = Icons.Filled.RestartAlt)
                if (power.haShutdownEntity != null) HoldToConfirmButton(stringResource(R.string.power_shutdown), { onPower(io.github.thecodingdad.moonlightlauncher.stream.PowerAction.SHUTDOWN) }, icon = Icons.Filled.PowerSettingsNew)
            }
            if (online && host.paired) LauncherButton(stringResource(R.string.host_unpair), icon = Icons.Filled.LinkOff, onClick = onUnpair)
            if (host.paired) HoldToConfirmButton(stringResource(R.string.host_remove), onConfirmed = onRemove, icon = Icons.Filled.Delete)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (waking) colors.accent else colors.onSurfaceMuted) }
    }
}
