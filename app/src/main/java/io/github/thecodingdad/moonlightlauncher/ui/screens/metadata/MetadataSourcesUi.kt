package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.companion.Fingerprint
import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer
import io.github.thecodingdad.moonlightlauncher.data.companion.PairingFailed
import io.github.thecodingdad.moonlightlauncher.data.companion.ServerException
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.metadata.MetadataSources
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.InfoRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.resources.*

/** What the pairing dialog is for: a metadata server or the companion of a host. */
internal data class PairTarget(val kind: String, val address: String, val hostName: String? = null)

/** The dialog open on the sources section (one at a time). */
private sealed interface SourceDialog {
    data object Add : SourceDialog
    data object PickHost : SourceDialog
    data class Keys(val id: String) : SourceDialog
    data class Pair(val target: PairTarget) : SourceDialog
    data class Server(val id: String) : SourceDialog
}

/**
 * Settings → Metadata & artwork → Sources: the configured sources in their order (drag to sort),
 * "Add source", and the settings of each source (credentials or pairing, on/off, remove).
 */
@Composable
internal fun SourcesSection(stored: List<String>) {
    val container = LocalContext.current.container
    val servers by container.servers.servers.collectAsState()
    val online by container.servers.online.collectAsState()
    val hosts by container.hosts.hosts.collectAsState()
    // Keys live in the secret store, which isn't observable: re-read after every edit.
    var revision by remember { mutableIntStateOf(0) }
    val entries = remember(stored, servers, revision) { resolve(container, stored) }
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }

    SectionHeader(stringResource(Res.string.metadata_sources))
    Text(stringResource(Res.string.metadata_sources_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    if (entries.isEmpty()) {
        Text(stringResource(Res.string.metadata_sources_empty), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
    }
    entries.forEach { entry ->
        val server = entry.server
        NavRow(
            sourceTitle(entry, hosts),
            { dialog = if (server != null) SourceDialog.Server(entry.id) else SourceDialog.Keys(entry.id) },
            subtitle = if (!entry.enabled) stringResource(Res.string.metadata_source_off) else if (server != null) serverStatus(server, online[server.id]) else null,
            icon = sourceIcon(entry.id, server),
        )
    }
    LauncherButton(stringResource(Res.string.metadata_source_add), icon = Icons.Filled.Add, onClick = { dialog = SourceDialog.Add })

    val changed: () -> Unit = { revision++ }
    when (val d = dialog) {
        SourceDialog.Add -> AddSourceDialog(
            entries,
            onPick = { id ->
                dialog = when (id) {
                    MetadataSources.SGDB, MetadataSources.IGDB -> SourceDialog.Keys(id)
                    PairedServer.KIND_COMPANION -> SourceDialog.PickHost
                    else -> SourceDialog.Pair(PairTarget(PairedServer.KIND_METADATA, ""))
                }
            },
            onDismiss = { dialog = null },
        )
        SourceDialog.PickHost -> PickHostDialog(
            hosts.values.filter { h -> servers.none { it.isCompanion && it.hostId.equals(h.uuid, ignoreCase = true) } },
            onPick = { dialog = SourceDialog.Pair(companionTarget(it)) },
            onDismiss = { dialog = null },
        )
        is SourceDialog.Keys -> KeySourceDialog(d.id, entries.firstOrNull { it.id == d.id }, onChanged = changed, onDismiss = { dialog = null })
        is SourceDialog.Pair -> PairDialog(d.target, onDismiss = { dialog = null })
        is SourceDialog.Server -> {
            val entry = entries.firstOrNull { it.id == d.id }
            val server = entry?.server
            if (server != null) ServerSourceDialog(entry, online[server.id], onDismiss = { dialog = null })
            else LaunchedEffect(d) { dialog = null } // removed meanwhile
        }
        null -> Unit
    }
}

/**
 * The companion of [host] in the host settings: its state, opens its settings, or pairs one.
 */
@Composable
internal fun HostCompanionRow(host: HostState) {
    val container = LocalContext.current.container
    val servers by container.servers.servers.collectAsState()
    val online by container.servers.online.collectAsState()
    val settings by container.settings.settings.collectAsState<io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings, io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings?>(null)
    val companion = servers.firstOrNull { it.isCompanion && it.hostId.equals(host.uuid, ignoreCase = true) }
    val entry = remember(settings, servers) { companion?.let { c -> resolve(container, settings?.metadataSources.orEmpty()).firstOrNull { it.server?.id == c.id } } }
    var open by remember { mutableStateOf(false) }
    NavRow(
        companion?.name ?: stringResource(Res.string.servers_not_paired), { open = true },
        subtitle = when {
            companion == null -> stringResource(Res.string.host_companion_hint)
            entry?.enabled == false -> stringResource(Res.string.metadata_source_off)
            else -> serverStatus(companion, online[companion.id])
        },
        icon = Icons.Filled.Computer,
        trailing = if (companion == null) stringResource(Res.string.servers_pair) else null,
    )
    if (!open) return
    if (entry != null) ServerSourceDialog(entry, online[entry.server!!.id], onDismiss = { open = false })
    else PairDialog(companionTarget(host), onDismiss = { open = false })
}

private fun resolve(container: io.github.thecodingdad.moonlightlauncher.AppContainer, stored: List<String>): List<MetadataSources.Entry> {
    val secrets = container.secrets
    return MetadataSources.resolve(
        stored, container.servers.servers.value,
        hasSgdb = !secrets.get(SecretStore.Keys.SGDB_API_KEY).isNullOrBlank(),
        hasIgdb = !secrets.get(SecretStore.Keys.IGDB_CLIENT_ID).isNullOrBlank() && !secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET).isNullOrBlank(),
    )
}

private fun companionTarget(host: HostState?) = PairTarget(
    PairedServer.KIND_COMPANION,
    host?.address?.substringBefore(':')?.let { "$it:${PairedServer.COMPANION_PORT}" }.orEmpty(),
    host?.name,
)

/** Switches [id] on or off, or forgets it (null: removed). */
private fun storeSource(container: io.github.thecodingdad.moonlightlauncher.AppContainer, id: String, enabled: Boolean?) {
    container.scope.launch {
        container.settings.update { s -> s.copy(metadataSources = MetadataSources.set(s.metadataSources, id, enabled)) }
        container.metadata.refreshPending()
    }
}

@Composable
private fun sourceTitle(entry: MetadataSources.Entry, hosts: Map<String, HostState>): String {
    val server = entry.server
    return when {
        entry.id == MetadataSources.SGDB -> "SteamGridDB"
        entry.id == MetadataSources.IGDB -> "IGDB (Twitch)"
        server != null && server.isCompanion -> stringResource(
            Res.string.metadata_source_companion_of,
            hosts.values.firstOrNull { it.uuid.equals(server.hostId, ignoreCase = true) }?.name ?: server.name,
        )
        else -> server?.name.orEmpty()
    }
}

private fun sourceIcon(id: String, server: PairedServer?): ImageVector = when {
    server?.isCompanion == true -> Icons.Filled.Computer
    server != null -> Icons.Filled.Cloud
    id == MetadataSources.SGDB -> Icons.Filled.Image
    else -> Icons.Filled.Info
}

@Composable
private fun serverStatus(server: PairedServer, online: Boolean?): String {
    val state = when (online) {
        true -> stringResource(Res.string.servers_online)
        false -> stringResource(Res.string.servers_offline)
        null -> stringResource(Res.string.servers_checking)
    }
    val kind = if (server.isCompanion) null else stringResource(Res.string.metadata_source_server)
    return listOfNotNull(kind, state, server.baseUrl.removePrefix("https://"), offers(server)).joinToString(" · ")
}

/** What a server answers for this device: "SteamGridDB · IGDB", "host games only" or null. */
@Composable
private fun offers(server: PairedServer): String? =
    listOfNotNull("SteamGridDB".takeIf { server.steamGridDb }, "IGDB".takeIf { server.igdb }).takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: if (server.isCompanion) stringResource(Res.string.metadata_source_host_games_only) else null

/** The kinds of source to add; own keys only once. */
@Composable
private fun AddSourceDialog(entries: List<MetadataSources.Entry>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.metadata_source_add), maxWidth = 600.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entries.none { it.id == MetadataSources.SGDB }) {
                NavRow("SteamGridDB", { onPick(MetadataSources.SGDB) }, stringResource(Res.string.metadata_source_sgdb_sub), Icons.Filled.Image, initialFocus = true)
            }
            if (entries.none { it.id == MetadataSources.IGDB }) {
                NavRow("IGDB (Twitch)", { onPick(MetadataSources.IGDB) }, stringResource(Res.string.metadata_source_igdb_sub), Icons.Filled.Info)
            }
            NavRow(stringResource(Res.string.metadata_source_companion), { onPick(PairedServer.KIND_COMPANION) }, stringResource(Res.string.metadata_source_companion_sub), Icons.Filled.Computer)
            NavRow(stringResource(Res.string.metadata_source_server), { onPick(PairedServer.KIND_METADATA) }, stringResource(Res.string.metadata_source_server_sub), Icons.Filled.Cloud)
        }
    }
}

/** Companion: for which host (prefills its address), or an address typed in. */
@Composable
private fun PickHostDialog(hosts: Collection<HostState>, onPick: (HostState?) -> Unit, onDismiss: () -> Unit) {
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.metadata_source_pick_host), maxWidth = 600.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.sortedBy { it.name.lowercase() }.forEachIndexed { i, host ->
                NavRow(host.name, { onPick(host) }, host.address, Icons.Filled.Computer, initialFocus = i == 0)
            }
            NavRow(stringResource(Res.string.servers_pair_companion_manual), { onPick(null) }, icon = Icons.Filled.Keyboard, initialFocus = hosts.isEmpty())
        }
    }
}

/** SteamGridDB key or IGDB credentials: enter/change, connection state, on/off, remove. */
@Composable
private fun KeySourceDialog(id: String, entry: MetadataSources.Entry?, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val secrets = container.secrets
    val metadata = container.metadata
    val sgdb = id == MetadataSources.SGDB
    val name = if (sgdb) "SteamGridDB" else "IGDB (Twitch)"
    var first by remember { mutableStateOf(secrets.get(if (sgdb) SecretStore.Keys.SGDB_API_KEY else SecretStore.Keys.IGDB_CLIENT_ID).orEmpty()) }
    var second by remember { mutableStateOf(if (sgdb) "" else secrets.get(SecretStore.Keys.IGDB_CLIENT_SECRET).orEmpty()) }
    // Bumped on save: the connection is checked again.
    var saved by remember { mutableIntStateOf(if (entry != null) 1 else 0) }
    val complete = first.isNotBlank() && (sgdb || second.isNotBlank())

    Modal(visible = true, onDismiss = onDismiss, title = name, maxWidth = 640.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (sgdb) Res.string.metadata_sgdb_help else Res.string.metadata_igdb_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            if (sgdb) {
                LauncherTextField(stringResource(Res.string.metadata_api_key), first, { first = it }, kind = TextKind.PASSWORD)
            } else {
                LauncherTextField(stringResource(Res.string.metadata_client_id), first, { first = it })
                LauncherTextField(stringResource(Res.string.metadata_client_secret), second, { second = it }, kind = TextKind.PASSWORD)
            }
            LauncherButton(stringResource(Res.string.action_save), modifier = Modifier.fillMaxWidth(), primary = true, onClick = {
                if (!complete) return@LauncherButton
                if (sgdb) {
                    secrets.put(SecretStore.Keys.SGDB_API_KEY, first.trim())
                } else {
                    secrets.put(SecretStore.Keys.IGDB_CLIENT_ID, first.trim())
                    secrets.put(SecretStore.Keys.IGDB_CLIENT_SECRET, second.trim())
                    secrets.put(SecretStore.Keys.IGDB_ACCESS_TOKEN, null)
                }
                saved++
                onChanged()
                metadata.refreshPending()
            })
            if (saved > 0) ConnectionStatus(name, saved) { if (sgdb) metadata.testSgdb() else metadata.testIgdb() }
            if (entry != null) {
                SwitchRow(stringResource(Res.string.metadata_source_active), entry.enabled, { storeSource(container, id, it) })
                HoldToConfirmButton(stringResource(Res.string.servers_remove), {
                    if (sgdb) {
                        secrets.put(SecretStore.Keys.SGDB_API_KEY, null)
                    } else {
                        listOf(SecretStore.Keys.IGDB_CLIENT_ID, SecretStore.Keys.IGDB_CLIENT_SECRET, SecretStore.Keys.IGDB_ACCESS_TOKEN).forEach { secrets.put(it, null) }
                    }
                    storeSource(container, id, null)
                    onChanged()
                    onDismiss()
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

private sealed interface PairState {
    data object Address : PairState
    data object Connecting : PairState
    data class ShowPin(val pin: String) : PairState
    data class Done(val server: PairedServer) : PairState
    data class Failed(val message: org.jetbrains.compose.resources.StringResource) : PairState
}

@Composable
internal fun PairDialog(target: PairTarget, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val repo = container.servers
    val scope = rememberCoroutineScope()
    var state by remember(target) { mutableStateOf<PairState>(PairState.Address) }
    var address by remember(target) { mutableStateOf(target.address) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun close() {
        job?.cancel()
        onDismiss()
    }
    val companion = target.kind == PairedServer.KIND_COMPANION
    val title = when {
        target.hostName != null -> stringResource(Res.string.servers_pair_title_host, target.hostName)
        companion -> stringResource(Res.string.servers_pair_companion_manual)
        else -> stringResource(Res.string.servers_pair_metadata)
    }
    Modal(visible = true, onDismiss = ::close, title = title, maxWidth = 560.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = state) {
                PairState.Address -> {
                    Text(stringResource(Res.string.servers_pair_step1), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    LauncherTextField(stringResource(Res.string.servers_address), address, { address = it }, placeholder = if (companion) "192.168.1.10:47910" else "192.168.1.20:47900")
                    val port = if (companion) PairedServer.COMPANION_PORT else PairedServer.METADATA_PORT
                    LauncherButton(stringResource(Res.string.servers_pair), modifier = Modifier.fillMaxWidth(), primary = true, onClick = {
                        val url = PairedServer.normalizeAddress(address, port)
                        if (url == null) {
                            state = PairState.Failed(Res.string.servers_error_address)
                            return@LauncherButton
                        }
                        state = PairState.Connecting
                        job = scope.launch {
                            state = try {
                                val server = repo.pair(url) { pin -> state = PairState.ShowPin(pin) }
                                if (server.kind != target.kind) PairState.Failed(Res.string.servers_error_kind).also { repo.remove(server.id) }
                                else {
                                    // The host's games get the companion's data right away.
                                    server.hostId?.let { hostId -> launch { container.library.resyncHost(hostId.uppercase()) } }
                                    PairState.Done(server)
                                }
                            } catch (e: ServerException) {
                                PairState.Failed(if (e.code == "pairing_closed") Res.string.servers_error_closed else Res.string.servers_error_failed)
                            } catch (e: PairingFailed) {
                                PairState.Failed(if (e.message == "timeout") Res.string.servers_error_timeout else Res.string.servers_error_failed)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                PairState.Failed(Res.string.servers_error_unreachable)
                            }
                        }
                    })
                }
                PairState.Connecting -> Text(stringResource(Res.string.servers_connecting), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                is PairState.ShowPin -> {
                    Text(stringResource(Res.string.servers_pin_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    Text(
                        s.pin.chunked(3).joinToString(" "), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        fontSize = 52.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = LauncherTheme.colors.onSurface,
                    )
                    Text(stringResource(Res.string.servers_pin_wait), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    LauncherButton(stringResource(Res.string.action_cancel), modifier = Modifier.fillMaxWidth(), onClick = ::close)
                }
                is PairState.Done -> {
                    Text(stringResource(Res.string.servers_paired, s.server.name), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.success)
                    LauncherButton(stringResource(Res.string.action_done), modifier = Modifier.fillMaxWidth(), primary = true, onClick = ::close)
                }
                is PairState.Failed -> {
                    Text(stringResource(s.message), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.danger)
                    LauncherButton(stringResource(Res.string.action_retry), modifier = Modifier.fillMaxWidth(), primary = true, onClick = { state = PairState.Address })
                }
            }
        }
    }
}

/** A paired companion or metadata server: details, check, on/off, remove. */
@Composable
private fun ServerSourceDialog(entry: MetadataSources.Entry, online: Boolean?, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val repo = container.servers
    val scope = rememberCoroutineScope()
    val s = entry.server ?: return
    Modal(visible = true, onDismiss = onDismiss, title = s.name, maxWidth = 600.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.Start) {
            InfoRow(stringResource(Res.string.servers_address), s.baseUrl.removePrefix("https://"))
            InfoRow(stringResource(Res.string.servers_state), when (online) {
                true -> stringResource(Res.string.servers_online)
                false -> stringResource(Res.string.servers_offline)
                null -> stringResource(Res.string.servers_checking)
            })
            InfoRow(stringResource(Res.string.metadata_source_offers), offers(s) ?: "–")
            Text(stringResource(Res.string.servers_fingerprint) + "\n" + Fingerprint.pretty(s.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = LauncherTheme.colors.onSurfaceMuted)
            SwitchRow(
                stringResource(Res.string.metadata_source_active), entry.enabled, { on ->
                    storeSource(container, entry.id, on)
                    // A companion switched on or off: its host games change source.
                    s.hostId?.let { scope.launch { container.library.resyncHost(it.uppercase()) } }
                },
                subtitle = if (s.isCompanion) stringResource(Res.string.metadata_source_companion_off_hint) else null,
                initialFocus = true,
            )
            LauncherButton(stringResource(Res.string.servers_check), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    repo.refresh(s)
                    // A companion: its host games are read again.
                    s.hostId?.let { container.library.resyncHost(it.uppercase()) }
                }
            })
            HoldToConfirmButton(stringResource(Res.string.servers_remove), {
                repo.remove(s.id)
                container.companionFeed.forget(s.id)
                storeSource(container, entry.id, null)
                onDismiss()
            }, Modifier.fillMaxWidth())
            Text(stringResource(Res.string.servers_remove_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}

/** Whether a provider accepts the key(s), checked again after every save ([revision]). */
@Composable
private fun ConnectionStatus(provider: String, revision: Int, test: suspend () -> Boolean?) {
    var status by remember { mutableStateOf<Boolean?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(revision) { checked = false; status = test(); checked = true }
    Text(
        when {
            !checked -> stringResource(Res.string.metadata_status_checking, provider)
            status == true -> stringResource(Res.string.metadata_status_ok, provider)
            status == false -> stringResource(Res.string.metadata_status_rejected, provider)
            else -> stringResource(Res.string.metadata_status_unknown, provider)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            !checked -> LauncherTheme.colors.onSurfaceMuted
            status == true -> LauncherTheme.colors.success
            status == false -> LauncherTheme.colors.danger
            else -> LauncherTheme.colors.onSurfaceMuted
        },
    )
}
