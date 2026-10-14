package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.companion.Fingerprint
import io.github.thecodingdad.moonlightlauncher.data.companion.PairedServer
import io.github.thecodingdad.moonlightlauncher.data.companion.PairingFailed
import io.github.thecodingdad.moonlightlauncher.data.companion.ServerException
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the pairing dialog is for: a metadata server or the companion of a host. */
private data class PairTarget(val kind: String, val address: String, val hostName: String? = null)

private sealed interface PairState {
    data object Address : PairState
    data object Connecting : PairState
    data class ShowPin(val pin: String) : PairState
    data class Done(val server: PairedServer) : PairState
    data class Failed(val message: Int) : PairState
}

/** Settings → Streaming & connections → Companion & metadata server. */
@Composable
fun ServersSettings(vm: LauncherViewModel) {
    val repo = LocalContext.current.container.servers
    val servers by repo.servers.collectAsState()
    val online by repo.online.collectAsState()
    val hosts by vm.hosts.collectAsState()
    var target by remember { mutableStateOf<PairTarget?>(null) }
    var details by remember { mutableStateOf<PairedServer?>(null) }

    SettingsPage(stringResource(R.string.settings_servers)) {
        Text(stringResource(R.string.servers_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)

        SectionHeader(stringResource(R.string.servers_companion))
        Text(stringResource(R.string.servers_companion_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        hosts.values.sortedBy { it.name.lowercase() }.forEach { host ->
            val companion = servers.firstOrNull { it.isCompanion && it.hostId.equals(host.uuid, ignoreCase = true) }
            if (companion != null) {
                NavRow(host.name, { details = companion }, subtitle = statusText(companion, online[companion.id]), icon = Icons.Filled.Computer)
            } else {
                NavRow(
                    host.name, {
                        target = PairTarget(PairedServer.KIND_COMPANION, host.address?.substringBefore(':')?.let { "$it:${PairedServer.COMPANION_PORT}" }.orEmpty(), host.name)
                    },
                    subtitle = stringResource(R.string.servers_not_paired), icon = Icons.Filled.Computer,
                    trailing = stringResource(R.string.servers_pair),
                )
            }
        }
        // Companions whose host this launcher doesn't know (yet).
        servers.filter { s -> s.isCompanion && hosts.values.none { it.uuid.equals(s.hostId, ignoreCase = true) } }.forEach { s ->
            NavRow(s.name, { details = s }, subtitle = statusText(s, online[s.id]), icon = Icons.Filled.Computer)
        }
        LauncherButton(
            stringResource(R.string.servers_pair_companion_manual), icon = Icons.Filled.Add,
            onClick = { target = PairTarget(PairedServer.KIND_COMPANION, "") },
        )

        SectionHeader(stringResource(R.string.servers_metadata))
        Text(stringResource(R.string.servers_metadata_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        servers.filter { !it.isCompanion }.forEach { s ->
            NavRow(s.name, { details = s }, subtitle = statusText(s, online[s.id]), icon = Icons.Filled.Cloud)
        }
        LauncherButton(
            stringResource(R.string.servers_pair_metadata), icon = Icons.Filled.Add,
            onClick = { target = PairTarget(PairedServer.KIND_METADATA, "") },
        )
    }

    PairDialog(target) { target = null }
    ServerDetailsDialog(details, online[details?.id]) { details = null }
}

@Composable
private fun statusText(server: PairedServer, online: Boolean?): String {
    val state = when (online) {
        true -> stringResource(R.string.servers_online)
        false -> stringResource(R.string.servers_offline)
        null -> stringResource(R.string.servers_checking)
    }
    val offers = listOfNotNull("SteamGridDB".takeIf { server.steamGridDb }, "IGDB".takeIf { server.igdb })
    return listOfNotNull(state, server.baseUrl.removePrefix("https://"), offers.takeIf { it.isNotEmpty() }?.joinToString(" · ")).joinToString(" · ")
}

@Composable
private fun PairDialog(target: PairTarget?, onDismiss: () -> Unit) {
    val repo = LocalContext.current.container.servers
    val scope = rememberCoroutineScope()
    var state by remember(target) { mutableStateOf<PairState>(PairState.Address) }
    var address by remember(target) { mutableStateOf(target?.address.orEmpty()) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun close() {
        job?.cancel()
        onDismiss()
    }
    val title = when {
        target?.hostName != null -> stringResource(R.string.servers_pair_title_host, target.hostName)
        target?.kind == PairedServer.KIND_COMPANION -> stringResource(R.string.servers_pair_companion_manual)
        else -> stringResource(R.string.servers_pair_metadata)
    }
    Modal(visible = target != null, onDismiss = ::close, title = title, maxWidth = 560.dp) {
        val t = target ?: return@Modal
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = state) {
                PairState.Address -> {
                    Text(stringResource(R.string.servers_pair_step1), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    LauncherTextField(stringResource(R.string.servers_address), address, { address = it }, placeholder = if (t.kind == PairedServer.KIND_COMPANION) "192.168.1.10:47910" else "192.168.1.20:47900")
                    val port = if (t.kind == PairedServer.KIND_COMPANION) PairedServer.COMPANION_PORT else PairedServer.METADATA_PORT
                    LauncherButton(stringResource(R.string.servers_pair), modifier = Modifier.fillMaxWidth(), primary = true, onClick = {
                        val url = PairedServer.normalizeAddress(address, port)
                        if (url == null) {
                            state = PairState.Failed(R.string.servers_error_address)
                            return@LauncherButton
                        }
                        state = PairState.Connecting
                        job = scope.launch {
                            state = try {
                                val server = repo.pair(url) { pin -> state = PairState.ShowPin(pin) }
                                if (server.kind != t.kind) PairState.Failed(R.string.servers_error_kind).also { repo.remove(server.id) } else PairState.Done(server)
                            } catch (e: ServerException) {
                                PairState.Failed(if (e.code == "pairing_closed") R.string.servers_error_closed else R.string.servers_error_failed)
                            } catch (e: PairingFailed) {
                                PairState.Failed(if (e.message == "timeout") R.string.servers_error_timeout else R.string.servers_error_failed)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                PairState.Failed(R.string.servers_error_unreachable)
                            }
                        }
                    })
                }
                PairState.Connecting -> Text(stringResource(R.string.servers_connecting), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
                is PairState.ShowPin -> {
                    Text(stringResource(R.string.servers_pin_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                    Text(
                        s.pin.chunked(3).joinToString(" "), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        fontSize = 52.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = LauncherTheme.colors.onSurface,
                    )
                    Text(stringResource(R.string.servers_pin_wait), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    LauncherButton(stringResource(R.string.action_cancel), modifier = Modifier.fillMaxWidth(), onClick = ::close)
                }
                is PairState.Done -> {
                    Text(stringResource(R.string.servers_paired, s.server.name), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.success)
                    LauncherButton(stringResource(R.string.action_done), modifier = Modifier.fillMaxWidth(), primary = true, onClick = ::close)
                }
                is PairState.Failed -> {
                    Text(stringResource(s.message), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.danger)
                    LauncherButton(stringResource(R.string.action_retry), modifier = Modifier.fillMaxWidth(), primary = true, onClick = { state = PairState.Address })
                }
            }
        }
    }
}

@Composable
private fun ServerDetailsDialog(server: PairedServer?, online: Boolean?, onDismiss: () -> Unit) {
    val repo = LocalContext.current.container.servers
    val scope = rememberCoroutineScope()
    Modal(visible = server != null, onDismiss = onDismiss, title = server?.name, maxWidth = 600.dp) {
        val s = server ?: return@Modal
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.Start) {
            InfoRow(stringResource(R.string.servers_address), s.baseUrl.removePrefix("https://"))
            InfoRow(stringResource(R.string.servers_state), when (online) {
                true -> stringResource(R.string.servers_online)
                false -> stringResource(R.string.servers_offline)
                null -> stringResource(R.string.servers_checking)
            })
            InfoRow("SteamGridDB", stringResource(if (s.steamGridDb) R.string.yes else R.string.no))
            InfoRow("IGDB", stringResource(if (s.igdb) R.string.yes else R.string.no))
            Text(stringResource(R.string.servers_fingerprint) + "\n" + Fingerprint.pretty(s.fingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherButton(stringResource(R.string.servers_check), modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { repo.refresh(s) } })
            HoldToConfirmButton(stringResource(R.string.servers_remove), { repo.remove(s.id); onDismiss() }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.servers_remove_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
    }
}
