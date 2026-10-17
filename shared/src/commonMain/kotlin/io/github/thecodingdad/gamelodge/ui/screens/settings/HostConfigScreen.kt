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
import io.github.thecodingdad.gamelodge.ha.HaEntity
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

/** HA domains that can act as a host power action. */
private val actionDomains = setOf("script", "button", "input_button", "switch", "scene", "automation", "input_boolean")

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
fun HostConfigScreen(host: HostState) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val scope = rememberCoroutineScope()
    val entities by c.ha.client.entities.collectAsState()
    var config by remember { mutableStateOf<HostConfigEntity?>(null) }
    LaunchedEffect(host.uuid) { config = c.hostPower.config(host.uuid) }
    var picking by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val cfg = config ?: return

    fun save(new: HostConfigEntity) {
        config = new
        scope.launch { c.hostPower.save(new) }
    }
    fun entityLabel(id: String?) = id?.let { entities[it]?.name ?: it }

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
        val haDisabled = !c.ha.auth.isLoggedIn
        NavRow(stringResource(Res.string.host_ha_wake), { if (!haDisabled) picking = { id -> save(cfg.copy(haWakeEntity = id)) } }, entityLabel(cfg.haWakeEntity) ?: stringResource(Res.string.not_set))
        SectionHeader(stringResource(Res.string.host_power_section))
        NavRow(stringResource(Res.string.host_ha_sleep), { if (!haDisabled) picking = { id -> save(cfg.copy(haSleepEntity = id)) } }, entityLabel(cfg.haSleepEntity) ?: stringResource(Res.string.not_set))
        NavRow(stringResource(Res.string.host_ha_shutdown), { if (!haDisabled) picking = { id -> save(cfg.copy(haShutdownEntity = id)) } }, entityLabel(cfg.haShutdownEntity) ?: stringResource(Res.string.not_set))
        NavRow(stringResource(Res.string.host_ha_restart), { if (!haDisabled) picking = { id -> save(cfg.copy(haRestartEntity = id)) } }, entityLabel(cfg.haRestartEntity) ?: stringResource(Res.string.not_set))
        if (haDisabled) Text(stringResource(Res.string.ha_login_first), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        ChoiceRow(
            stringResource(Res.string.host_auto_sleep), listOf<Int?>(null, 5, 10, 15, 30, 60), cfg.autoSleepMinutes,
            label = { it?.let { m -> stringResource(Res.string.minutes_short, m) } ?: stringResource(Res.string.off) },
            onSelect = { save(cfg.copy(autoSleepMinutes = it)) },
            subtitle = stringResource(Res.string.host_auto_sleep_sub),
        )
        CompanionSection(host)
    }

    val onPick = picking
    Modal(visible = onPick != null, onDismiss = { picking = null }, title = stringResource(Res.string.host_pick_action), maxWidth = 760.dp) {
        val candidates = entities.values.filter { it.domain in actionDomains }.sortedWith(compareBy({ it.domain }, { it.name }))
        val pickerList = io.github.thecodingdad.gamelodge.ui.input.rememberStickLazyListState()
        LazyColumn(state = pickerList, modifier = Modifier.heightIn(max = 400.dp).mouseDragScroll(pickerList), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                EntityRow(null) { onPick?.invoke(null); picking = null }
            }
            items(candidates, key = { it.id }) { e ->
                EntityRow(e) { onPick?.invoke(e.id); picking = null }
            }
        }
    }
}

@Composable
private fun EntityRow(entity: HaEntity?, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.surfaceRaised, focusScale = 1.02f) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(entity?.name ?: stringResource(Res.string.not_set), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
            if (entity != null) Text(entity.id, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}

/** The companion on this host (paired or not): opens its settings or pairs one. */
@Composable
private fun CompanionSection(host: HostState) {
    SectionHeader(stringResource(Res.string.host_companion_section))
    io.github.thecodingdad.gamelodge.ui.screens.metadata.HostCompanionRow(host)
}
