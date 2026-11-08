package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.data.settings.XboxUnlicensed
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.QrCard
import io.github.thecodingdad.gamelodge.ui.components.StatusDot
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.xbox.XboxAccount
import io.github.thecodingdad.gamelodge.xbox.XboxConsole
import io.github.thecodingdad.gamelodge.xbox.XboxDeviceCode
import io.github.thecodingdad.gamelodge.xbox.XboxHost
import io.github.thecodingdad.gamelodge.xbox.XboxPower
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A console as host for the screens that take one (its settings page, host cards, status bar). */
fun XboxHost.asHostState(runningTitle: Long? = null): HostState = HostState(
    uuid = hostUuid,
    name = console.name,
    status = when (console.power) {
        XboxPower.ON -> HostStatus.ONLINE
        XboxPower.STANDBY, XboxPower.OFF -> HostStatus.OFFLINE
        XboxPower.UNKNOWN -> HostStatus.UNKNOWN
    },
    paired = true,
    runningAppId = runningTitle?.toInt() ?: 0,
    address = null,
    macAddress = null,
    virtualDisplaySupported = false,
    serverCommands = emptyList(),
    permission = -1,
)

/** The added consoles as hosts, a running game as their running app. */
@Composable
fun rememberXboxHostStates(): List<HostState> {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val hosts by c.xbox.hosts.collectAsState()
    val running by c.xbox.running.collectAsState()
    return androidx.compose.runtime.remember(hosts, running) {
        hosts.values.sortedBy { it.console.name.lowercase() }.map { it.asHostState(running[it.console.id]) }
    }
}

private sealed interface XboxAddUi {
    data object Starting : XboxAddUi
    data class Code(val code: XboxDeviceCode) : XboxAddUi
    data object Loading : XboxAddUi
    data class Pick(val consoles: List<XboxConsole>) : XboxAddUi
    data class Failed(val message: String) : XboxAddUi
}

/** Xbox consoles on the hosts page: the Microsoft account, the added consoles and adding more. */
@Composable
fun XboxHostsSection(openHostConfig: ((HostState) -> Unit)?) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val xbox = c.xbox
    val scope = rememberCoroutineScope()
    val account by xbox.account.collectAsState()
    val hosts by xbox.hosts.collectAsState()
    val settings by c.settings.settings.collectAsState(null)
    var addUi by remember { mutableStateOf<XboxAddUi?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val notes = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val failedMsg = stringResource(Res.string.xbox_sign_in_failed)
    val noConsolesMsg = stringResource(Res.string.xbox_no_consoles)
    val wakeSentMsg = stringResource(Res.string.host_wake_sending)
    val wakeOnlineMsg = stringResource(Res.string.host_wake_online)
    val wakeTimeoutMsg = stringResource(Res.string.host_wake_timeout)

    suspend fun loadConsoles() {
        addUi = XboxAddUi.Loading
        val list = runCatching { xbox.accountConsoles() }.getOrNull()
        addUi = when {
            list == null -> XboxAddUi.Failed(failedMsg)
            else -> list.filter { it.id !in hosts.keys }.let { if (it.isEmpty()) XboxAddUi.Failed(noConsolesMsg) else XboxAddUi.Pick(it) }
        }
    }

    fun add() {
        job?.cancel()
        job = scope.launch {
            if (account is XboxAccount.SignedIn) return@launch loadConsoles()
            addUi = XboxAddUi.Starting
            val code = runCatching { xbox.startSignIn() }.getOrElse { addUi = XboxAddUi.Failed(failedMsg); return@launch }
            addUi = XboxAddUi.Code(code)
            if (xbox.awaitSignIn(code)) loadConsoles() else addUi = XboxAddUi.Failed(failedMsg)
        }
    }

    SectionHeader(stringResource(Res.string.xbox_section))
    if (hosts.isEmpty()) {
        Text(stringResource(Res.string.xbox_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
    }
    hosts.values.sortedBy { it.console.name }.forEach { host ->
        XboxCard(
            host = host,
            note = notes[host.console.id],
            onConfig = openHostConfig?.let { open -> { open(host.asHostState()) } },
            onWake = {
                notes[host.console.id] = wakeSentMsg
                scope.launch {
                    c.hostPower.wake(host.hostUuid)
                    val timeout = c.hostPower.config(host.hostUuid).wakeTimeoutSeconds * 1000L
                    notes[host.console.id] = if (xbox.waitOn(host.console.id, timeout)) wakeOnlineMsg else wakeTimeoutMsg
                }
            },
            onTurnOff = { scope.launch { xbox.turnOff(host.console.id); xbox.refresh() } },
            onRemove = { scope.launch { xbox.remove(host.console.id) } },
        )
    }
    io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 12.dp) {
        LauncherButton(stringResource(Res.string.xbox_add), icon = Icons.Filled.Add, primary = hosts.isEmpty(), onClick = ::add)
        val signedIn = account as? XboxAccount.SignedIn
        if (signedIn != null) {
            HoldToConfirmButton(
                stringResource(Res.string.xbox_sign_out), onConfirmed = { scope.launch { xbox.signOut() } }, icon = Icons.Filled.Logout,
            )
        }
    }
    (account as? XboxAccount.SignedIn)?.let {
        Text(
            stringResource(Res.string.xbox_signed_in_as, it.gamertag ?: "–"),
            style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
        )
    }
    if (hosts.isNotEmpty()) {
        settings?.let { s ->
            ChoiceRow(
                stringResource(Res.string.xbox_unlicensed), XboxUnlicensed.entries, s.xboxUnlicensed,
                label = { stringResource(if (it == XboxUnlicensed.HIDE) Res.string.xbox_unlicensed_hide else Res.string.xbox_unlicensed_dim) },
                onSelect = { v -> scope.launch { c.settings.update { it.copy(xboxUnlicensed = v) } } },
                subtitle = stringResource(Res.string.xbox_unlicensed_sub),
            )
        }
    }

    val ui = addUi
    Modal(
        visible = ui != null,
        onDismiss = { job?.cancel(); addUi = null },
        title = stringResource(if (ui is XboxAddUi.Pick) Res.string.xbox_pick_title else Res.string.xbox_sign_in_title),
        closeButton = false,
    ) {
        when (ui) {
            XboxAddUi.Starting, XboxAddUi.Loading -> CircularProgressIndicator(color = LauncherTheme.colors.accent)
            is XboxAddUi.Code -> {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCard(ui.code.directUri, 180.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(Res.string.xbox_sign_in_steps, ui.code.verificationUri.removePrefix("https://")), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                        Text(
                            ui.code.userCode, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp, color = LauncherTheme.colors.accent,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(Res.string.xbox_sign_in_waiting), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                LauncherButton(stringResource(Res.string.action_cancel), onClick = { job?.cancel(); addUi = null })
            }
            is XboxAddUi.Pick -> {
                Text(stringResource(Res.string.xbox_pick_text), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                Spacer(Modifier.height(12.dp))
                ui.consoles.forEachIndexed { i, console ->
                    LauncherButton(
                        "${console.name}  ·  ${consoleType(console.type)}", icon = Icons.Filled.SportsEsports, primary = i == 0,
                        enabled = console.streaming,
                        onClick = { addUi = null; scope.launch { xbox.add(listOf(console)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!console.streaming) Text(stringResource(Res.string.xbox_streaming_off), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.warning)
                    Spacer(Modifier.height(8.dp))
                }
                LauncherButton(stringResource(Res.string.action_cancel), onClick = { addUi = null })
            }
            is XboxAddUi.Failed -> {
                Text(ui.message, style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                Spacer(Modifier.height(16.dp))
                LauncherButton(stringResource(Res.string.action_close), primary = true, onClick = { addUi = null })
            }
            null -> Unit
        }
    }
}

/** "XboxSeriesX" → "Xbox Series X". */
private fun consoleType(type: String): String =
    type.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace("Xbox One S", "Xbox One S").ifEmpty { "Xbox" }

@Composable
private fun XboxCard(
    host: XboxHost,
    note: String?,
    /** Null where the settings page can't open (setup wizard). */
    onConfig: (() -> Unit)?,
    onWake: () -> Unit,
    onTurnOff: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LauncherTheme.colors
    val power = host.console.power
    Column(
        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (power) {
                    XboxPower.ON -> colors.success
                    XboxPower.STANDBY -> colors.onSurfaceMuted
                    XboxPower.OFF -> colors.danger
                    XboxPower.UNKNOWN -> colors.onSurfaceMuted
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(host.console.name, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Text(
                    listOfNotNull(
                        consoleType(host.console.type).takeIf { host.console.type.isNotEmpty() },
                        stringResource(
                            when (power) {
                                XboxPower.ON -> Res.string.host_status_online
                                XboxPower.STANDBY -> Res.string.xbox_status_standby
                                XboxPower.OFF -> Res.string.host_status_offline
                                XboxPower.UNKNOWN -> Res.string.host_status_unknown
                            },
                        ),
                        stringResource(Res.string.xbox_missing).takeIf { host.missing },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                )
            }
        }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            if (onConfig != null) LauncherButton(stringResource(Res.string.host_settings), icon = Icons.Filled.Settings, onClick = onConfig)
            if (power != XboxPower.ON) LauncherButton(stringResource(Res.string.host_wake), icon = Icons.Filled.Power, onClick = onWake)
            if (power == XboxPower.ON) HoldToConfirmButton(stringResource(Res.string.xbox_turn_off), onTurnOff, icon = Icons.Filled.PowerSettingsNew)
            HoldToConfirmButton(stringResource(Res.string.host_remove), onConfirmed = onRemove, icon = Icons.Filled.Delete)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted) }
    }
}

/** The Xbox part of a stream profile (profile editor, Xbox tab); [base]: the base settings, for profiles without own values. */
@Composable
fun XboxStreamSection(values: io.github.thecodingdad.gamelodge.stream.ProfileValues, base: io.github.thecodingdad.gamelodge.stream.ProfileValues?) {
    var s by remember(values) { mutableStateOf(io.github.thecodingdad.gamelodge.xbox.XboxStreamSettings.of(values, base)) }
    fun save(new: io.github.thecodingdad.gamelodge.xbox.XboxStreamSettings) {
        new.writeTo(values, s)
        s = new
    }
    run {
        ChoiceRow(
            stringResource(Res.string.xbox_resolution), io.github.thecodingdad.gamelodge.xbox.XboxResolution.entries, s.resolution,
            label = {
                when (it) {
                    io.github.thecodingdad.gamelodge.xbox.XboxResolution.AUTO -> stringResource(Res.string.xbox_resolution_auto)
                    io.github.thecodingdad.gamelodge.xbox.XboxResolution.P720 -> "720p"
                    io.github.thecodingdad.gamelodge.xbox.XboxResolution.P1080 -> "1080p"
                    io.github.thecodingdad.gamelodge.xbox.XboxResolution.P1080_HQ -> stringResource(Res.string.xbox_resolution_hq)
                }
            },
            onSelect = { save(s.copy(resolution = it)) },
            subtitle = stringResource(Res.string.xbox_resolution_sub),
        )
    }
    ChoiceRow(
        stringResource(Res.string.xbox_bitrate), listOf(0, 5, 10, 15, 20, 30), s.maxBitrateMbps,
        label = { if (it == 0) stringResource(Res.string.xbox_bitrate_auto) else "$it Mbit/s" },
        onSelect = { save(s.copy(maxBitrateMbps = it)) },
        subtitle = stringResource(Res.string.xbox_bitrate_sub),
    )
    ChoiceRow(
        stringResource(Res.string.xbox_sharpness), listOf(0, 2, 5, 8), s.sharpness,
        label = {
            stringResource(
                when (it) {
                    0 -> Res.string.off
                    2 -> Res.string.xbox_sharpness_low
                    5 -> Res.string.xbox_sharpness_medium
                    else -> Res.string.xbox_sharpness_high
                },
            )
        },
        onSelect = { save(s.copy(sharpness = it)) },
        subtitle = stringResource(Res.string.xbox_sharpness_sub),
    )
    ChoiceRow(
        stringResource(Res.string.xbox_fit), io.github.thecodingdad.gamelodge.xbox.XboxFit.entries, s.fit,
        label = {
            stringResource(
                when (it) {
                    io.github.thecodingdad.gamelodge.xbox.XboxFit.FIT -> Res.string.xbox_fit_fit
                    io.github.thecodingdad.gamelodge.xbox.XboxFit.STRETCH -> Res.string.xbox_fit_stretch
                    io.github.thecodingdad.gamelodge.xbox.XboxFit.ZOOM -> Res.string.xbox_fit_zoom
                },
            )
        },
        onSelect = { save(s.copy(fit = it)) },
    )
    SwitchRow(stringResource(Res.string.xbox_vibration), s.vibration, { save(s.copy(vibration = it)) }, subtitle = stringResource(Res.string.xbox_vibration_sub))
}
