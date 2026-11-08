package io.github.thecodingdad.gamelodge.ui.screens.settings

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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.gamelodge.data.remote.AddHostResult
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.data.remote.PairResult
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.StatusDot
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

private sealed interface PairUi {
    data class Waiting(val host: HostState, val pin: String) : PairUi
    data class Done(val host: HostState, val result: PairResult) : PairUi
}

@Composable
fun HostsSettings(
    hosts: List<HostState>,
    openHostConfig: (HostState) -> Unit,
    /** The stream profiles' group below the hosts (platform part; nothing in previews). */
    profiles: @Composable () -> Unit = {},
) {
    val launcherContainer = io.github.thecodingdad.gamelodge.LocalContainer.current
    val actions = launcherContainer.hostActions
    val scope = rememberCoroutineScope()
    val keyboard = LocalKeyboardController.current
    var pairUi by remember { mutableStateOf<PairUi?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HostState?>(null) }
    // New hosts are searched for while this screen is open (and once after start).
    DisposableEffect(Unit) {
        io.github.thecodingdad.gamelodge.data.remote.HostDiscovery.hold(launcherContainer.hosts, "hosts-screen", true)
        onDispose { io.github.thecodingdad.gamelodge.data.remote.HostDiscovery.hold(launcherContainer.hosts, "hosts-screen", false) }
    }

    fun startPairing(host: HostState) {
        val pin = actions.generatePin()
        pairUi = PairUi.Waiting(host, pin)
        scope.launch {
            val result = actions.pair(host, pin)
            pairUi = PairUi.Done(host, result)
        }
    }

    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val waking = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val notes = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val hostConfigList by androidx.compose.runtime.remember { c.database.hostConfigDao().observeAll() }.collectAsState(emptyList())
    val hostConfigs = hostConfigList.associateBy { it.hostUuid }
    val wakeSentMsg = stringResource(Res.string.host_wake_sending)
    val wakeFailedMsg = stringResource(Res.string.host_wake_failed)
    val wakeOnlineMsg = stringResource(Res.string.host_wake_online)
    val wakeTimeoutMsg = stringResource(Res.string.host_wake_timeout)
    val addTitle = stringResource(Res.string.host_add_title)
    val addedMsg = stringResource(Res.string.host_added)
    val invalidMsg = stringResource(Res.string.host_invalid_address)
    val unreachableMsg = stringResource(Res.string.host_unreachable)

    val appLists by launcherContainer.hosts.appLists.collectAsState()
    val boxArt by launcherContainer.library.boxArtProgress.collectAsState()
    val metadataProgress by launcherContainer.metadata.progress.collectAsState()

    SettingsPage(stringResource(Res.string.settings_hosts)) {
        SectionHeader(stringResource(Res.string.hosts_section_pcs))
        val xboxHosts by launcherContainer.xbox.hosts.collectAsState()
        val xboxAdder = rememberXboxAdder()
        if (hosts.isEmpty() && xboxHosts.isEmpty()) {
            Text(stringResource(Res.string.hosts_empty), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurfaceMuted)
        }
        // PCs and consoles in one list, by name.
        val all: List<Pair<String, Any>> = (hosts.map { it.name to it } + xboxHosts.values.map { it.console.name to it }).sortedBy { it.first.lowercase() }
        all.forEachIndexed { index, (_, item) ->
            if (item is io.github.thecodingdad.gamelodge.xbox.XboxHost) {
                XboxHostCard(item, onConfig = { openHostConfig(item.asHostState()) })
                return@forEachIndexed
            }
            val host = item as HostState
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
                onRemove = { removing = host },
                onConfig = { openHostConfig(host) },
                progress = hostProgress(host, appLists, boxArt, metadataProgress),
            )
        }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(
                stringResource(Res.string.host_add), icon = Icons.Filled.Add, primary = hosts.isEmpty() && xboxHosts.isEmpty(),
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
            LauncherButton(stringResource(Res.string.xbox_add), icon = Icons.Filled.Add, primary = hosts.isEmpty() && xboxHosts.isEmpty(), onClick = xboxAdder::start)
            LauncherButton(stringResource(Res.string.hosts_refresh), icon = Icons.Filled.Refresh, onClick = {
                launcherContainer.hosts.refreshAppLists()
                scope.launch { launcherContainer.xbox.refresh() }
            })
        }
        if (adding) CircularProgressIndicator(color = LauncherTheme.colors.accent)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted) }
        Text(stringResource(Res.string.hosts_discovery_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        PcVersionHint()
        profiles()
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.ha_host_actions), SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT), highlight = stringResource(Res.string.ha_host_actions), subtitle = stringResource(Res.string.settings_home_assistant)),
        )
    }

    val ui = pairUi
    Modal(visible = ui != null, onDismiss = { pairUi = null }, title = stringResource(Res.string.pair_title), closeButton = false) {
        when (ui) {
            is PairUi.Waiting -> {
                Text(stringResource(Res.string.pair_instructions, ui.host.name), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
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
                    Text(stringResource(Res.string.pair_waiting), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                }
                Spacer(Modifier.height(16.dp))
                LauncherButton(stringResource(Res.string.action_cancel), onClick = { pairUi = null })
            }
            is PairUi.Done -> {
                Text(
                    stringResource(
                        when (ui.result) {
                            PairResult.PAIRED -> Res.string.pair_success
                            PairResult.WRONG_PIN -> Res.string.pair_wrong_pin
                            PairResult.IN_GAME -> Res.string.pair_in_game
                            PairResult.ALREADY_IN_PROGRESS -> Res.string.pair_in_progress
                            PairResult.OFFLINE -> Res.string.error_host_offline
                            PairResult.FAILED -> Res.string.pair_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface,
                )
                Spacer(Modifier.height(20.dp))
                LauncherButton(stringResource(Res.string.action_close), primary = true, onClick = { pairUi = null })
            }
            null -> Unit
        }
    }

    // Removing a host: keep its games (hidden, back with the next pairing) or delete them.
    val toRemove = removing
    Modal(visible = toRemove != null, onDismiss = { removing = null }, title = toRemove?.let { stringResource(Res.string.host_remove_title, it.name) }, closeButton = false) {
        val host = toRemove ?: return@Modal
        Text(stringResource(Res.string.host_remove_text), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
        Spacer(Modifier.height(16.dp))
        fun remove(keep: Boolean) {
            removing = null
            scope.launch {
                actions.remove(host)
                launcherContainer.library.removeHost(host.uuid, keepGames = keep)
            }
        }
        LauncherButton(stringResource(Res.string.host_remove_keep), primary = true, onClick = { remove(true) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        HoldToConfirmButton(stringResource(Res.string.host_remove_delete), onConfirmed = { remove(false) }, icon = Icons.Filled.Delete, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        LauncherButton(stringResource(Res.string.action_cancel), onClick = { removing = null }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HostCard(
    host: HostState,
    initialFocus: Boolean,
    waking: Boolean,
    note: String?,
    power: io.github.thecodingdad.gamelodge.data.db.HostConfigEntity?,
    onPower: (io.github.thecodingdad.gamelodge.stream.PowerAction) -> Unit,
    onPair: () -> Unit,
    onWake: () -> Unit,
    onRemove: () -> Unit,
    onConfig: () -> Unit,
    /** What is going on for this host right now (app list, box art, metadata), or null. */
    progress: HostProgress? = null,
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
                                host.isStreaming -> Res.string.host_status_streaming
                                host.status == HostStatus.ONLINE -> Res.string.host_status_online
                                host.status == HostStatus.OFFLINE -> Res.string.host_status_offline
                                else -> Res.string.host_status_unknown
                            },
                        ),
                        // Pairing is only known while the host answers.
                        if (host.status == HostStatus.ONLINE) stringResource(if (host.paired) Res.string.host_paired else Res.string.host_not_paired) else null,
                        host.address,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                )
            }
        }
        if (host.paired) MissingPermissionsNote(host)
        val online = host.status == HostStatus.ONLINE
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            if (online && !host.paired) {
                LauncherButton(
                    stringResource(Res.string.host_pair), icon = Icons.Filled.Link, primary = true, onClick = onPair,
                    modifier = Modifier.restoreFocusIf(initialFocus),
                )
            }
            LauncherButton(
                stringResource(Res.string.host_settings), icon = Icons.Filled.Settings, onClick = onConfig,
                modifier = if (online && !host.paired) Modifier else Modifier.restoreFocusIf(initialFocus),
            )
            if (!online) {
                LauncherButton(
                    stringResource(if (waking) Res.string.host_waking else Res.string.host_wake), icon = Icons.Filled.Power,
                    onClick = onWake, enabled = !waking, primary = waking,
                )
            }
            // Power actions (Home Assistant), only for a paired host that is on, when configured.
            if (online && host.paired && power != null) {
                if (power.haSleepEntity != null) HoldToConfirmButton(stringResource(Res.string.power_sleep), { onPower(io.github.thecodingdad.gamelodge.stream.PowerAction.SLEEP) }, icon = Icons.Filled.Bedtime)
                if (power.haRestartEntity != null) HoldToConfirmButton(stringResource(Res.string.power_restart), { onPower(io.github.thecodingdad.gamelodge.stream.PowerAction.RESTART) }, icon = Icons.Filled.RestartAlt)
                if (power.haShutdownEntity != null) HoldToConfirmButton(stringResource(Res.string.power_shutdown), { onPower(io.github.thecodingdad.gamelodge.stream.PowerAction.SHUTDOWN) }, icon = Icons.Filled.PowerSettingsNew)
            }
            if (host.paired) HoldToConfirmButton(stringResource(Res.string.host_remove), onConfirmed = onRemove, icon = Icons.Filled.Delete)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (waking) colors.accent else colors.onSurfaceMuted) }
        progress?.let { p ->
            Text(
                when (p.step) {
                    HostProgress.Step.APP_LIST -> stringResource(Res.string.host_progress_apps)
                    HostProgress.Step.BOX_ART -> stringResource(Res.string.host_progress_box_art, p.done, p.total)
                    HostProgress.Step.METADATA -> stringResource(Res.string.host_progress_metadata, p.done, p.total)
                },
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted,
            )
            if (p.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { p.done.toFloat() / p.total }, color = colors.accent, trackColor = colors.surfaceRaised,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(color = colors.accent, trackColor = colors.surfaceRaised, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

/** One step of what a host's games are going through (shown on its card). */
data class HostProgress(val step: Step, val done: Int = 0, val total: Int = 0) {
    enum class Step { APP_LIST, BOX_ART, METADATA }
}

/**
 * Right after pairing the app list loads, then box art comes in; metadata lookups (for all new
 * games, whichever host) run afterwards.
 */
private fun hostProgress(
    host: HostState,
    appLists: Map<String, List<io.github.thecodingdad.gamelodge.data.remote.RemoteApp>>,
    boxArt: Map<String, Pair<Int, Int>>,
    metadata: io.github.thecodingdad.gamelodge.metadata.MetadataProgress?,
): HostProgress? = when {
    !host.paired || host.status != HostStatus.ONLINE -> null
    appLists[host.uuid] == null -> HostProgress(HostProgress.Step.APP_LIST)
    boxArt[host.uuid] != null -> boxArt.getValue(host.uuid).let { (d, t) -> HostProgress(HostProgress.Step.BOX_ART, d, t) }
    metadata != null && metadata.total > 0 -> HostProgress(HostProgress.Step.METADATA, metadata.done, metadata.total)
    else -> null
}
