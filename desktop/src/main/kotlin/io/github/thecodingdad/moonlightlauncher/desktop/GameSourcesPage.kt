package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.nayuki.qrcodegen.QrCode
import io.github.thecodingdad.moonlightlauncher.LocalContainer
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary
import io.github.thecodingdad.moonlightlauncher.desktop.games.StoreDetection
import io.github.thecodingdad.moonlightlauncher.resources.*
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Store names as the stores write them. */
internal val storeNames = mapOf(
    "steam" to "Steam", "epic" to "Epic Games", "gog" to "GOG", "xbox" to "Xbox / Microsoft Store", "ea" to "EA app",
    "ubisoft" to "Ubisoft Connect", "battlenet" to "Battle.net", "amazon" to "Amazon Games",
)

/** Settings → Game sources: which stores feed the library, and the Steam account. */
@Composable
fun GameSourcesPage(library: PcLibrary, settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val counts by library.counts.collectAsState()
    val steam by library.steam.status.collectAsState()
    var qr by remember { mutableStateOf(false) }
    // Registry and folder checks: off the UI thread, once per visit.
    val installed by androidx.compose.runtime.produceState<Map<String, Boolean?>>(emptyMap()) {
        value = withContext(Dispatchers.IO) { library.sources.associateWith { StoreDetection.installed(it) } }
    }
    SettingsPage(stringResource(Res.string.settings_game_sources)) {
        Text(stringResource(Res.string.game_sources_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        // A scan running: which stores are still being looked at.
        val scanning by library.scanning.collectAsState()
        val hostRepository = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.hosts
        NavRow(
            stringResource(Res.string.game_sources_scan_now),
            { library.rescan(); hostRepository.refreshAppLists() },
            subtitle = stringResource(Res.string.game_sources_scan_now_sub),
            icon = androidx.compose.material.icons.Icons.Filled.Refresh,
        )
        scanning?.let { p ->
            Text(
                stringResource(Res.string.task_pc_scan) + " · " + p.pending.joinToString { storeNames[it] ?: it },
                style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { p.done.toFloat() / p.total.coerceAtLeast(1) },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(), color = LauncherTheme.colors.accent,
            )
        }
        library.sources.forEachIndexed { index, source ->
            val present = installed[source] != false
            val on = source !in settings.disabledGameSources
            val count = counts[source]
            SwitchRow(
                storeNames[source] ?: source, on && present,
                onChange = { enable -> update { s -> s.copy(disabledGameSources = if (enable) s.disabledGameSources - source else s.disabledGameSources + source) } },
                subtitle = when {
                    !present -> stringResource(Res.string.game_source_not_installed)
                    !on -> null
                    count == null || count.first == 0 -> stringResource(Res.string.game_source_none)
                    else -> stringResource(Res.string.game_source_count, count.first, count.second)
                },
                initialFocus = index == 0,
                enabled = present,
            )
        }

        SectionHeader(stringResource(Res.string.steam_account))
        Text(stringResource(Res.string.steam_account_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        Text(
            if (steam.signedIn) stringResource(Res.string.steam_signed_in, steam.accountName ?: "Steam") else stringResource(Res.string.steam_signed_out),
            style = MaterialTheme.typography.bodyLarge, color = if (steam.signedIn) LauncherTheme.colors.success else LauncherTheme.colors.onSurface,
        )
        if (steam.signedIn) {
            LauncherButton(stringResource(Res.string.steam_sign_out), onClick = { library.steam.signOut(); library.rescan() }, icon = Icons.AutoMirrored.Filled.Logout)
        } else {
            LauncherButton(stringResource(Res.string.steam_sign_in), onClick = { qr = true }, icon = Icons.Filled.QrCode2, primary = true)
        }
            io.github.thecodingdad.moonlightlauncher.desktop.games.CustomGamesSection(settings, update)
    }
    if (qr) SteamQrDialog(library) { qr = false }
}

/** QR code for the Steam app; closes by itself once the user confirmed there. */
@Composable
private fun SteamQrDialog(library: PcLibrary, onDismiss: () -> Unit) {
    val deviceName = LocalContainer.current.platform.deviceName
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) {
        failed = false
        url = null
        val ok = library.steam.signInWithQr(deviceName) { url = it }
        if (ok) {
            library.rescan()
            onDismiss()
        } else {
            failed = true
        }
    }
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.steam_qr_title), maxWidth = 560.dp) {
        val current = url
        when {
            failed -> {
                Text(stringResource(Res.string.steam_qr_failed), color = LauncherTheme.colors.danger)
                LauncherButton(stringResource(Res.string.action_retry), onClick = { scope.launch { attempt++ } }, primary = true, modifier = Modifier.fillMaxWidth())
            }
            current == null -> {
                Text(stringResource(Res.string.steam_qr_connecting), color = LauncherTheme.colors.onSurfaceMuted)
                CircularProgressIndicator(Modifier.size(36.dp).align(Alignment.CenterHorizontally), color = LauncherTheme.colors.accent)
            }
            else -> {
                Text(stringResource(Res.string.steam_qr_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp),
                ) { QrImage(current, Modifier.size(260.dp)) }
            }
        }
    }
}

/** Black modules on white, drawn from the text. */
@Composable
private fun QrImage(text: String, modifier: Modifier) {
    val code = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier) {
        val cell = size.minDimension / code.size
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code.getModule(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
