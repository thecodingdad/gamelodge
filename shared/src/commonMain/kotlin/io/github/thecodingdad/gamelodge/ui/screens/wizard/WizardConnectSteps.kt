package io.github.thecodingdad.gamelodge.ui.screens.wizard

import androidx.compose.material.icons.filled.Monitor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.companion.PairedServer
import io.github.thecodingdad.gamelodge.data.db.HostConfigEntity
import io.github.thecodingdad.gamelodge.data.db.WakeMethod
import io.github.thecodingdad.gamelodge.data.remote.AddHostResult
import io.github.thecodingdad.gamelodge.data.remote.HostState
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.data.remote.PairResult
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.ha.HaConnection
import io.github.thecodingdad.gamelodge.ha.HaRepository
import io.github.thecodingdad.gamelodge.metadata.MetadataSources
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.QrCard
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.screens.ha.EntityPicker
import io.github.thecodingdad.gamelodge.ui.screens.ha.LoginDialog
import io.github.thecodingdad.gamelodge.ui.screens.ha.actionDomains
import io.github.thecodingdad.gamelodge.ui.screens.metadata.KeySourceDialog
import io.github.thecodingdad.gamelodge.ui.screens.metadata.PairDialog
import io.github.thecodingdad.gamelodge.ui.screens.metadata.PairTarget
import io.github.thecodingdad.gamelodge.ui.screens.metadata.companionTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.MissingPermissionsNote
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private val LocalNav @Composable get() = LocalWizardNav.current!!

// ---- Streaming hosts ----

private sealed interface Pairing {
    data class Waiting(val host: HostState, val pin: String) : Pairing
    data class Done(val host: HostState, val result: PairResult) : Pairing
}

@Composable
internal fun ColumnScope.HostsStep() {
    val nav = LocalNav
    val c = LocalContainer.current
    val actions = c.hostActions
    val scope = rememberCoroutineScope()
    val keyboard = LocalKeyboardController.current
    val hosts by c.hosts.hosts.collectAsState()
    val settings by c.settings.settings.collectAsState(LauncherSettings())
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // Hosts are searched for while the step is open.
    DisposableEffect(Unit) {
        io.github.thecodingdad.gamelodge.data.remote.HostDiscovery.hold(c.hosts, "wizard", true)
        onDispose { io.github.thecodingdad.gamelodge.data.remote.HostDiscovery.hold(c.hosts, "wizard", false) }
    }
    val addTitle = stringResource(Res.string.host_add_title)
    val addedMsg = stringResource(Res.string.host_added)
    val invalidMsg = stringResource(Res.string.host_invalid_address)
    val unreachableMsg = stringResource(Res.string.host_unreachable)

    val sorted = hosts.values.sortedWith(compareByDescending<HostState> { it.paired }.thenBy { it.name })
    if (sorted.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.padding(end = 12.dp))
            WizardNote(stringResource(Res.string.wizard_hosts_searching))
        }
    }
    sorted.forEachIndexed { index, host ->
        HostEntry(
            host, settings, initialFocus = index == 0,
            onPair = {
                val pin = actions.generatePin()
                pairing = Pairing.Waiting(host, pin)
                scope.launch { pairing = Pairing.Done(host, actions.pair(host, pin)) }
            },
        )
    }
    // Xbox consoles in the same list, as on the streaming hosts page (their settings later there).
    val xboxHosts by io.github.thecodingdad.gamelodge.LocalContainer.current.xbox.hosts.collectAsState()
    val xboxAdder = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberXboxAdder()
    xboxHosts.values.sortedBy { it.console.name.lowercase() }.forEach { host ->
        io.github.thecodingdad.gamelodge.ui.screens.settings.XboxHostCard(host, onConfig = null)
    }
    ButtonRow(spacing = 12.dp) {
        LauncherButton(
            stringResource(Res.string.host_add), icon = Icons.Filled.Add, primary = sorted.isEmpty() && xboxHosts.isEmpty(),
            modifier = Modifier.restoreFocusIf(sorted.isEmpty()),
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
        LauncherButton(stringResource(Res.string.xbox_add), icon = Icons.Filled.Add, primary = sorted.isEmpty() && xboxHosts.isEmpty(), onClick = xboxAdder::start)
    }
    io.github.thecodingdad.gamelodge.ui.screens.settings.PcVersionHint()
    if (adding) CircularProgressIndicator(color = LauncherTheme.colors.accent)
    message?.let { WizardNote(it) }
    WizardNote(stringResource(Res.string.hosts_discovery_hint))

    val p = pairing
    Modal(visible = p != null, onDismiss = { pairing = null }, title = stringResource(Res.string.pair_title), closeButton = false) {
        when (p) {
            is Pairing.Waiting -> {
                Text(stringResource(Res.string.pair_instructions, p.host.name), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                Spacer(Modifier.height(20.dp))
                Text(
                    p.pin, fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 12.sp,
                    color = LauncherTheme.colors.accent, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))
                WizardNote(stringResource(Res.string.wizard_hosts_pin_where))
                Spacer(Modifier.height(16.dp))
                LauncherButton(stringResource(Res.string.action_cancel), onClick = { pairing = null })
            }
            is Pairing.Done -> {
                Text(
                    stringResource(
                        when (p.result) {
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
                LauncherButton(stringResource(Res.string.action_close), primary = true, onClick = { pairing = null })
            }
            null -> Unit
        }
    }
}

/** One host: state, pairing, missing permissions and (once paired) how it is woken up. */
@Composable
private fun HostEntry(host: HostState, settings: LauncherSettings, initialFocus: Boolean, onPair: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val colors = LauncherTheme.colors
    var config by remember(host.uuid) { mutableStateOf<HostConfigEntity?>(null) }
    LaunchedEffect(host.uuid) { config = c.hostPower.config(host.uuid) }
    var login by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val entities by c.ha.client.entities.collectAsState()
    fun save(new: HostConfigEntity) {
        config = new
        scope.launch { c.hostPower.save(new) }
    }
    fun needsHa(method: WakeMethod) = method == WakeMethod.HOME_ASSISTANT || method == WakeMethod.BOTH

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val online = host.status == HostStatus.ONLINE
        if (host.paired) {
            WizardStatusRow(
                host.name, stringResource(Res.string.wizard_hosts_paired, host.address.orEmpty()), done = true, icon = Icons.Filled.Monitor,
            )
        } else {
            WizardStatusRow(
                host.name,
                listOfNotNull(
                    stringResource(if (online) Res.string.host_status_online else Res.string.host_status_offline),
                    host.address,
                ).joinToString(" · "),
                done = false, icon = Icons.Filled.Monitor,
                action = if (online) stringResource(Res.string.host_pair) else null,
                onClick = if (online) onPair else null,
                initialFocus = initialFocus,
            )
        }
        if (host.paired) {
            MissingPermissionsNote(host)
            config?.let { cfg ->
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
                    onSelect = { method ->
                        save(cfg.copy(wakeMethod = method))
                        // Home Assistant chosen: sign in first when it isn't yet; the action is picked
                        // with "Wake up" afterwards.
                        if (needsHa(method) && !c.ha.auth.isLoggedIn) login = true
                        if (needsHa(method)) c.ha.client.start()
                    },
                    subtitle = stringResource(Res.string.host_wake_method_sub),
                    initialFocus = initialFocus,
                )
                if (needsHa(cfg.wakeMethod) && c.ha.auth.isLoggedIn) {
                    WizardStatusRow(
                        stringResource(Res.string.host_wake),
                        cfg.haWakeEntity?.let { entities[it]?.name ?: it } ?: stringResource(Res.string.not_set),
                        done = cfg.haWakeEntity != null, icon = Icons.Filled.Power,
                        action = stringResource(Res.string.wizard_choose),
                        onClick = { c.ha.client.start(); picking = true },
                    )
                }
            }
        }
    }
    if (login) {
        LoginDialog(settings, LocalNav.update) { login = false }
    }
    EntityPicker(
        visible = picking,
        title = stringResource(Res.string.ha_pick_action_for, stringResource(Res.string.host_wake)),
        entities = entities.values, domains = actionDomains,
        onPick = { id -> config?.let { save(it.copy(haWakeEntity = id)) } },
        onDismiss = { picking = false },
        allowNone = true, switchDefault = true,
    )
}

// ---- GameLodge PC ----

@Composable
internal fun ColumnScope.CompanionStep() {
    val nav = LocalNav
    val c = LocalContainer.current
    val hosts by c.hosts.hosts.collectAsState()
    val servers by c.servers.servers.collectAsState()
    val paired = hosts.values.filter { it.paired }.sortedBy { it.name }
    // Host uuid → GameLodge answers on that PC (null while asking).
    val found = remember { mutableStateMapOf<String, Boolean>() }
    var probing by remember { mutableStateOf(true) }
    var pairFor by remember { mutableStateOf<PairTarget?>(null) }
    LaunchedEffect(paired.map { it.uuid }) {
        probing = true
        paired.forEach { h ->
            if (h.uuid in found) return@forEach
            val address = h.address?.substringBefore(':') ?: return@forEach
            found[h.uuid] = c.servers.probe("https://$address:${PairedServer.COMPANION_PORT}", PairedServer.KIND_COMPANION)
        }
        probing = false
    }
    val companionOf = { h: HostState -> servers.firstOrNull { it.isCompanion && it.hostId.equals(h.uuid, ignoreCase = true) } }
    val offered = paired.filter { companionOf(it) != null || found[it.uuid] == true }
    // Nothing to pair with: the step has nothing to offer.
    LaunchedEffect(probing, offered.isEmpty(), servers.none { it.isCompanion }) {
        if (!probing && offered.isEmpty() && servers.none { it.isCompanion }) nav.autoSkip()
    }

    WizardNote(stringResource(Res.string.wizard_companion_benefits))
    if (probing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.padding(end = 12.dp))
            WizardNote(stringResource(Res.string.wizard_companion_searching))
        }
    }
    offered.forEachIndexed { i, h ->
        val companion = companionOf(h)
        WizardStatusRow(
            h.name,
            if (companion != null) stringResource(Res.string.servers_paired, companion.name) else stringResource(Res.string.wizard_companion_found),
            done = companion != null, icon = Icons.Filled.Computer,
            action = stringResource(Res.string.servers_pair),
            onClick = if (companion == null) ({ pairFor = companionTarget(h) }) else null,
            initialFocus = i == 0,
        )
    }
    // Other companions paired by address (no matching host here).
    servers.filter { s -> s.isCompanion && paired.none { it.uuid.equals(s.hostId, ignoreCase = true) } }.forEach { s ->
        WizardStatusRow(s.name, s.baseUrl.removePrefix("https://"), done = true, icon = Icons.Filled.Computer)
    }
    LauncherButton(
        stringResource(Res.string.wizard_pair_by_address), icon = Icons.Filled.Link,
        onClick = { pairFor = PairTarget(PairedServer.KIND_COMPANION, "") },
    )
    pairFor?.let { PairDialog(it, onDismiss = { pairFor = null }) }
}

// ---- Metadata sources ----

@Composable
internal fun ColumnScope.MetadataStep() {
    val c = LocalContainer.current
    val servers by c.servers.servers.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var keysFor by remember { mutableStateOf<String?>(null) }
    var pairMetadata by remember { mutableStateOf(false) }
    val secrets = c.secrets
    val hasSgdb = remember(revision) { !secrets.get(SecretStore.Keys.SGDB_API_KEY).isNullOrBlank() }
    val hasIgdb = remember(revision) {
        !secrets.get(SecretStore.Keys.IGDB_CLIENT_ID).isNullOrBlank() && !secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET).isNullOrBlank()
    }
    val viaPc = servers.filter { it.isCompanion && (it.steamGridDb || it.igdb) }
    val metadataServers = servers.filter { it.kind == PairedServer.KIND_METADATA }

    SectionHeader(stringResource(Res.string.wizard_metadata_via_pc))
    if (viaPc.isEmpty()) {
        WizardStatusRow(stringResource(Res.string.wizard_metadata_via_pc_none), stringResource(Res.string.wizard_metadata_via_pc_sub), done = false, icon = Icons.Filled.Computer)
    } else {
        viaPc.forEach { s ->
            WizardStatusRow(
                s.name, listOfNotNull("SteamGridDB".takeIf { s.steamGridDb }, "IGDB".takeIf { s.igdb }).joinToString(" · "),
                done = true, icon = Icons.Filled.Computer,
            )
        }
    }

    SectionHeader(stringResource(Res.string.wizard_metadata_server))
    if (metadataServers.isEmpty()) {
        WizardStatusRow(
            stringResource(Res.string.servers_pair_metadata), stringResource(Res.string.wizard_metadata_server_sub), done = false,
            icon = Icons.Filled.Dns, action = stringResource(Res.string.servers_pair), onClick = { pairMetadata = true },
            initialFocus = viaPc.isEmpty(),
        )
    } else {
        metadataServers.forEach { s -> WizardStatusRow(s.name, s.baseUrl.removePrefix("https://"), done = true, icon = Icons.Filled.Dns) }
    }

    SectionHeader(stringResource(Res.string.wizard_metadata_keys))
    WizardStatusRow(
        "SteamGridDB", stringResource(Res.string.wizard_metadata_sgdb_sub), done = hasSgdb, icon = Icons.Filled.Key,
        action = stringResource(Res.string.wizard_enter), onClick = { keysFor = MetadataSources.SGDB },
    )
    WizardStatusRow(
        "IGDB (Twitch)", stringResource(Res.string.wizard_metadata_igdb_sub), done = hasIgdb, icon = Icons.Filled.Key,
        action = stringResource(Res.string.wizard_enter), onClick = { keysFor = MetadataSources.IGDB },
    )
    // How to get the keys: step by step (with the page as QR code) behind the button in each key dialog.
    WizardNote(stringResource(Res.string.wizard_metadata_guide_hint))

    keysFor?.let { id -> KeySourceDialog(id, null, onChanged = { revision++ }, onDismiss = { keysFor = null; revision++ }) }
    if (pairMetadata) PairDialog(PairTarget(PairedServer.KIND_METADATA, ""), onDismiss = { pairMetadata = false })
}


// ---- Home Assistant ----

@Composable
internal fun ColumnScope.HomeAssistantStep(settings: LauncherSettings) {
    val nav = LocalNav
    val c = LocalContainer.current
    val ha = c.ha
    val scope = rememberCoroutineScope()
    val connection by ha.client.connection.collectAsState()
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    var login by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // Not signed in and no address yet: look for Home Assistant under its usual name.
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ha.client.start()
        if (!ha.auth.isLoggedIn && settings.haUrl.isEmpty()) {
            searching = true
            val found = withContext(Dispatchers.IO) { findHomeAssistant(okhttp3.OkHttpClient()) }
            searching = false
            if (found != null) nav.update { it.copy(haUrl = found) }
        }
    }
    val loggedIn = ha.auth.isLoggedIn && connection != HaConnection.NOT_CONFIGURED
    WizardStatusRow(
        stringResource(if (loggedIn) Res.string.wizard_ha_connected else Res.string.ha_login),
        when {
            loggedIn -> settings.haUrl.ifEmpty { ha.auth.baseUrl.orEmpty() }
            searching -> stringResource(Res.string.wizard_ha_searching)
            settings.haUrl.isNotEmpty() -> stringResource(Res.string.wizard_ha_found, settings.haUrl)
            else -> stringResource(Res.string.wizard_ha_not_found)
        },
        done = loggedIn, icon = Icons.Filled.Cottage,
        action = stringResource(Res.string.ha_login),
        onClick = if (loggedIn) null else ({ login = true }),
        initialFocus = !loggedIn,
    )
    if (loggedIn) {
        SectionHeader(stringResource(Res.string.ha_tiles))
        WizardNote(stringResource(Res.string.ha_entities_intro))
        tiles.forEach { t ->
            WizardStatusRow(t.label ?: entities[t.entityId]?.name ?: t.entityId, t.entityId, done = true, icon = Icons.Filled.Star)
        }
        LauncherButton(
            stringResource(Res.string.ha_add_tile), icon = Icons.Filled.Add, primary = tiles.isEmpty(),
            onClick = { picking = true },
            modifier = Modifier.restoreFocusIf(true),
        )
    }
    if (login) LoginDialog(settings, nav.update) { login = false }
    EntityPicker(
        visible = picking, title = stringResource(Res.string.ha_add_tile),
        entities = entities.values.filter { it.id !in tiles.map { t -> t.entityId } },
        domains = HaRepository.supportedDomains,
        onPick = { id -> entities[id]?.let { scope.launch { ha.addTile(it) } } },
        onDismiss = { picking = false },
    )
}

/** Home Assistant under its usual names in the home network, or null. */
private fun findHomeAssistant(http: okhttp3.OkHttpClient): String? {
    val client = http.newBuilder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS).readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
    return listOf("http://homeassistant.local:8123", "http://homeassistant:8123").firstOrNull { base ->
        runCatching {
            client.newCall(okhttp3.Request.Builder().url("$base/manifest.json").build()).execute().use { r ->
                r.isSuccessful && r.body?.string().orEmpty().contains("Home Assistant")
            }
        }.getOrDefault(false)
    }
}
