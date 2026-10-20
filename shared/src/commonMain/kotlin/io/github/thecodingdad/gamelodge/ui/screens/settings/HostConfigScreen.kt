package io.github.thecodingdad.gamelodge.ui.screens.settings

import io.github.thecodingdad.gamelodge.ui.components.mouseDragScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.thecodingdad.gamelodge.data.db.WakeMethod
import io.github.thecodingdad.gamelodge.data.remote.HostPermission
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.ui.screens.ha.EntityPicker
import io.github.thecodingdad.gamelodge.ui.screens.ha.actionDomains
import io.github.thecodingdad.gamelodge.ui.screens.ha.actionLabel
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*


@Composable
fun permissionLabel(p: HostPermission) = stringResource(
    when (p) {
        HostPermission.LAUNCH_APPS -> Res.string.perm_launch
        HostPermission.CONTROLLER_INPUT -> Res.string.perm_controller
        HostPermission.LIST_APPS -> Res.string.perm_list
        else -> Res.string.perm_other
    },
)

@Composable
fun MissingPermissionsNote(host: HostState) {
    val missing = host.missingPermissions
    if (missing.isEmpty()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, null, tint = LauncherTheme.colors.warning)
        Text(
            stringResource(Res.string.perm_missing, missing.map { permissionLabel(it) }.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.warning,
        )
    }
}

@Composable
fun HostConfigScreen(host: HostState, openHaSettings: () -> Unit) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val scope = rememberCoroutineScope()
    val entities by c.ha.client.entities.collectAsState()
    var config by remember { mutableStateOf<HostConfigEntity?>(null) }
    LaunchedEffect(host.uuid) { config = c.hostPower.config(host.uuid) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val cfg = config ?: return

    fun save(new: HostConfigEntity) {
        config = new
        scope.launch { c.hostPower.save(new) }
    }

    SettingsPage(host.name) {
        MissingPermissionsNote(host)
        SectionHeader(stringResource(Res.string.host_wake_section))
        ChoiceRow(
            stringResource(Res.string.host_wake_method), WakeMethod.entries, cfg.wakeMethod,
            label = {
                stringResource(
                    when (it) {
                        WakeMethod.NONE -> Res.string.wake_none
                        WakeMethod.WOL -> Res.string.wake_wol
                        WakeMethod.HOME_ASSISTANT -> Res.string.wake_ha
                        WakeMethod.BOTH -> Res.string.wake_both
                    },
                )
            },
            onSelect = { save(cfg.copy(wakeMethod = it)) },
            subtitle = stringResource(Res.string.host_wake_method_sub),
            initialFocus = true,
        )
        if (cfg.wakeMethod != WakeMethod.NONE) {
            val waits = listOf(60, 120, 180, 300, 600)
            ChoiceRow(
                stringResource(Res.string.host_wake_timeout_setting), (waits + cfg.wakeTimeoutSeconds).distinct().sorted(), cfg.wakeTimeoutSeconds,
                label = { stringResource(Res.string.minutes_short, (it + 59) / 60) },
                onSelect = { save(cfg.copy(wakeTimeoutSeconds = it)) },
                subtitle = stringResource(Res.string.host_wake_timeout_sub),
            )
        }

        // Only where the launcher has VPN tunnels of its own (Android).
        val tunnels by c.vpn.tunnels.collectAsState()
        if (tunnels.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.settings_vpn))
            val auto = c.vpn.autoTunnelFor(host.uuid)
            ChoiceRow(
                stringResource(Res.string.vpn_host_tunnel),
                listOf<String?>(null, io.github.thecodingdad.gamelodge.vpn.NO_TUNNEL) + tunnels,
                cfg.vpnTunnel?.takeIf { it == io.github.thecodingdad.gamelodge.vpn.NO_TUNNEL || it in tunnels },
                label = {
                    when (it) {
                        null -> stringResource(Res.string.vpn_host_auto, auto ?: "–")
                        io.github.thecodingdad.gamelodge.vpn.NO_TUNNEL -> stringResource(Res.string.vpn_host_none)
                        else -> it
                    }
                },
                onSelect = { save(cfg.copy(vpnTunnel = it)) },
                subtitle = stringResource(Res.string.vpn_host_tunnel_sub),
            )
        }

        SectionHeader(stringResource(Res.string.host_ha_actions))
        if (!c.ha.auth.isLoggedIn) {
            Text(stringResource(Res.string.host_ha_actions_needs_ha), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            io.github.thecodingdad.gamelodge.ui.components.LauncherButton(
                stringResource(Res.string.host_ha_setup), onClick = openHaSettings, icon = Icons.Filled.Home, primary = true,
            )
        } else {
            val notSet = stringResource(Res.string.not_set)
            val wake = stringResource(Res.string.host_wake)
            val sleep = stringResource(Res.string.power_sleep)
            val shutdown = stringResource(Res.string.power_shutdown)
            val restart = stringResource(Res.string.power_restart)
            fun pick(title: String, switchOn: Boolean, apply: (String?) -> HostConfigEntity) {
                c.ha.client.start()
                picking = Picking(title, switchOn) { save(apply(it)) }
            }
            NavRow(wake, { pick(wake, true) { cfg.copy(haWakeEntity = it) } }, actionLabel(cfg.haWakeEntity, entities) ?: notSet, Icons.Filled.Power)
            NavRow(sleep, { pick(sleep, false) { cfg.copy(haSleepEntity = it) } }, actionLabel(cfg.haSleepEntity, entities) ?: notSet, Icons.Filled.Bedtime)
            NavRow(shutdown, { pick(shutdown, false) { cfg.copy(haShutdownEntity = it) } }, actionLabel(cfg.haShutdownEntity, entities) ?: notSet, Icons.Filled.PowerSettingsNew)
            NavRow(restart, { pick(restart, false) { cfg.copy(haRestartEntity = it) } }, actionLabel(cfg.haRestartEntity, entities) ?: notSet, Icons.Filled.RestartAlt)
        }

        SectionHeader(stringResource(Res.string.host_after_stream))
        ChoiceRow(
            stringResource(Res.string.host_auto_sleep), listOf<Int?>(null, 5, 10, 15, 30, 60), cfg.autoSleepMinutes,
            label = { it?.let { m -> stringResource(Res.string.minutes_short, m) } ?: stringResource(Res.string.off) },
            onSelect = { save(cfg.copy(autoSleepMinutes = it)) },
            subtitle = stringResource(Res.string.host_auto_sleep_sub),
        )
        CompanionSection(host)
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_profiles), SettingsTarget.Page(SettingsPageId.PROFILES)),
            SeeAlsoLink(stringResource(Res.string.settings_home_assistant), SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT)),
        )
    }

    val p = picking
    EntityPicker(
        visible = p != null,
        title = stringResource(Res.string.ha_pick_action_for, p?.title.orEmpty()),
        entities = entities.values, domains = actionDomains,
        onPick = { p?.onPick?.invoke(it) }, onDismiss = { picking = null },
        allowNone = true, switchDefault = p?.switchOn ?: false,
    )
}

/** A host action being picked; [switchOn]: what a switch does for it unless changed. */
private class Picking(val title: String, val switchOn: Boolean, val onPick: (String?) -> Unit)

/** The companion on this host (paired or not): opens its settings or pairs one. */
@Composable
private fun CompanionSection(host: HostState) {
    SectionHeader(stringResource(Res.string.host_companion_section))
    io.github.thecodingdad.gamelodge.ui.screens.metadata.HostCompanionRow(host)
}
