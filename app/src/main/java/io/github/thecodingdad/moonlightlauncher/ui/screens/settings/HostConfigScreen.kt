package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity
import io.github.thecodingdad.moonlightlauncher.data.db.WakeMethod
import io.github.thecodingdad.moonlightlauncher.data.remote.HostPermission
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.ha.HaEntity
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

/** HA domains that can act as a host power action. */
private val actionDomains = setOf("script", "button", "input_button", "switch", "scene", "automation", "input_boolean")

@Composable
fun permissionLabel(p: HostPermission) = stringResource(
    when (p) {
        HostPermission.LAUNCH_APPS -> R.string.perm_launch
        HostPermission.CONTROLLER_INPUT -> R.string.perm_controller
        HostPermission.LIST_APPS -> R.string.perm_list
        else -> R.string.perm_other
    },
)

@Composable
fun MissingPermissionsNote(host: HostState) {
    val missing = host.missingPermissions
    if (missing.isEmpty()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, null, tint = LauncherTheme.colors.warning)
        Text(
            stringResource(R.string.perm_missing, missing.map { permissionLabel(it) }.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.warning,
        )
    }
}

@Composable
fun HostConfigScreen(host: HostState) {
    val c = LocalContext.current.container
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
        SectionHeader(stringResource(R.string.host_wake_section))
        ChoiceRow(
            stringResource(R.string.host_wake_method), WakeMethod.entries, cfg.wakeMethod,
            label = {
                stringResource(
                    when (it) {
                        WakeMethod.NONE -> R.string.wake_none
                        WakeMethod.WOL -> R.string.wake_wol
                        WakeMethod.HOME_ASSISTANT -> R.string.wake_ha
                        WakeMethod.BOTH -> R.string.wake_both
                    },
                )
            },
            onSelect = { save(cfg.copy(wakeMethod = it)) },
            subtitle = stringResource(R.string.host_wake_method_sub),
            initialFocus = true,
        )
        val haDisabled = !c.ha.auth.isLoggedIn
        NavRow(stringResource(R.string.host_ha_wake), { if (!haDisabled) picking = { id -> save(cfg.copy(haWakeEntity = id)) } }, entityLabel(cfg.haWakeEntity) ?: stringResource(R.string.not_set))
        SectionHeader(stringResource(R.string.host_power_section))
        NavRow(stringResource(R.string.host_ha_sleep), { if (!haDisabled) picking = { id -> save(cfg.copy(haSleepEntity = id)) } }, entityLabel(cfg.haSleepEntity) ?: stringResource(R.string.not_set))
        NavRow(stringResource(R.string.host_ha_shutdown), { if (!haDisabled) picking = { id -> save(cfg.copy(haShutdownEntity = id)) } }, entityLabel(cfg.haShutdownEntity) ?: stringResource(R.string.not_set))
        NavRow(stringResource(R.string.host_ha_restart), { if (!haDisabled) picking = { id -> save(cfg.copy(haRestartEntity = id)) } }, entityLabel(cfg.haRestartEntity) ?: stringResource(R.string.not_set))
        if (haDisabled) Text(stringResource(R.string.ha_login_first), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        ChoiceRow(
            stringResource(R.string.host_auto_sleep), listOf<Int?>(null, 5, 10, 15, 30, 60), cfg.autoSleepMinutes,
            label = { it?.let { m -> stringResource(R.string.minutes_short, m) } ?: stringResource(R.string.off) },
            onSelect = { save(cfg.copy(autoSleepMinutes = it)) },
            subtitle = stringResource(R.string.host_auto_sleep_sub),
        )
        VibepolloSection(host, cfg, ::save)
    }

    val onPick = picking
    Modal(visible = onPick != null, onDismiss = { picking = null }, title = stringResource(R.string.host_pick_action), maxWidth = 760.dp) {
        val candidates = entities.values.filter { it.domain in actionDomains }.sortedWith(compareBy({ it.domain }, { it.name }))
        LazyColumn(state = io.github.thecodingdad.moonlightlauncher.ui.input.rememberStickLazyListState(), modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text(entity?.name ?: stringResource(R.string.not_set), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
            if (entity != null) Text(entity.id, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun VibepolloSection(host: HostState, cfg: HostConfigEntity, save: (HostConfigEntity) -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val secretKey = io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore.Keys.vibepolloToken(host.uuid)
    val secret = remember(revision) { c.secrets.get(secretKey).orEmpty() }
    var result by remember { mutableStateOf<String?>(null) }
    val okText = stringResource(R.string.host_api_ok)
    val failText = stringResource(R.string.host_api_failed)
    val type = runCatching { io.github.thecodingdad.moonlightlauncher.data.remote.HostApiType.valueOf(cfg.apiType ?: "VIBEPOLLO") }
        .getOrDefault(io.github.thecodingdad.moonlightlauncher.data.remote.HostApiType.VIBEPOLLO)
    val isToken = type == io.github.thecodingdad.moonlightlauncher.data.remote.HostApiType.VIBEPOLLO

    SectionHeader(stringResource(R.string.host_api_section))
    Text(stringResource(R.string.host_api_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    ChoiceRow(
        stringResource(R.string.host_api_type), io.github.thecodingdad.moonlightlauncher.data.remote.HostApiType.entries, type,
        label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        onSelect = { save(cfg.copy(apiType = it.name)); c.hostApi.invalidate(host.uuid) },
    )
    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField(
        stringResource(R.string.vibepollo_url), cfg.vibepolloUrl ?: "",
        { save(cfg.copy(vibepolloUrl = it.trim().ifEmpty { null })); c.hostApi.invalidate(host.uuid) },
        kind = io.github.thecodingdad.moonlightlauncher.ui.components.TextKind.URL,
        placeholder = host.address?.let { "https://$it:47990" },
    )
    if (!isToken) {
        io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField(
            stringResource(R.string.host_api_user), cfg.apiUser ?: "",
            { save(cfg.copy(apiUser = it.trim().ifEmpty { null })); c.hostApi.invalidate(host.uuid) },
        )
    }
    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField(
        stringResource(if (isToken) R.string.vibepollo_token else R.string.host_api_password), secret,
        {
            c.secrets.put(secretKey, if (isToken) it.trim() else it)
            c.hostApi.invalidate(host.uuid)
            revision++
        },
        kind = io.github.thecodingdad.moonlightlauncher.ui.components.TextKind.PASSWORD,
    )
    if (type == io.github.thecodingdad.moonlightlauncher.data.remote.HostApiType.APOLLO) {
        Text(stringResource(R.string.host_api_apollo_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    }
    io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
        stringResource(R.string.vibepollo_test), enabled = !cfg.vibepolloUrl.isNullOrBlank() && secret.isNotEmpty() && (isToken || !cfg.apiUser.isNullOrBlank()),
        onClick = {
            scope.launch {
                val r = c.hostApi.fetch(host.uuid)
                result = if (r != null) okText.format(r.apps, r.companion.size) else failText
                if (r != null) { c.hostApi.invalidate(host.uuid); c.library.resyncHost(host.uuid) }
            }
        },
    )
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface) }
}
