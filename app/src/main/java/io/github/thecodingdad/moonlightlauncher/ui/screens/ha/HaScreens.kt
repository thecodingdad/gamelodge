package io.github.thecodingdad.moonlightlauncher.ui.screens.ha

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Login
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.MainActivity
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileEntity
import io.github.thecodingdad.moonlightlauncher.data.library.TitleNormalizer
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ha.HaConnection
import io.github.thecodingdad.moonlightlauncher.ha.HaEntity
import io.github.thecodingdad.moonlightlauncher.ha.HaRepository
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHintBar
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherTextField
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.SidePanel
import io.github.thecodingdad.moonlightlauncher.ui.components.TextKind
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.InfoRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SectionHeader
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SwitchRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

@Composable
fun connectionLabel(state: HaConnection) = stringResource(
    when (state) {
        HaConnection.NOT_CONFIGURED -> R.string.ha_not_configured
        HaConnection.DISCONNECTED -> R.string.ha_disconnected
        HaConnection.CONNECTING -> R.string.ha_connecting
        HaConnection.CONNECTED -> R.string.ha_connected
        HaConnection.AUTH_FAILED -> R.string.ha_auth_failed
    },
)

/** Slides in from the right on Y, from any screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HaPanel(visible: Boolean, onDismiss: () -> Unit, openSettings: () -> Unit) {
    val ha = LocalContext.current.container.ha
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    val connection by ha.client.connection.collectAsState()

    androidx.compose.runtime.LaunchedEffect(visible) { if (visible) ha.client.start() }
    SidePanel(visible = visible, onDismiss = onDismiss, width = 470.dp) {
        Text(stringResource(R.string.settings_home_assistant), style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface)
        Text(connectionLabel(connection), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        Spacer(Modifier.height(16.dp))
        if (connection == HaConnection.NOT_CONFIGURED || tiles.isEmpty()) {
            Text(stringResource(R.string.ha_panel_empty), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
            Spacer(Modifier.height(16.dp))
            LauncherButton(stringResource(R.string.ha_setup), onClick = { onDismiss(); openSettings() }, primary = true)
        } else {
            FlowRow(
                Modifier.verticalScroll(rememberScrollState()).weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                tiles.forEachIndexed { index, tile ->
                    HaTile(tile, entities[tile.entityId], Modifier.restoreFocusIf(index == 0))
                }
            }
        }
        ButtonHintBar(
            listOf(
                ButtonHint(PadButton.A, stringResource(R.string.ha_hint_toggle)),
                ButtonHint(PadButton.B, stringResource(R.string.action_close), onClick = onDismiss),
            ),
        )
    }
}

@Composable
fun HaSettings(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val ha = context.container.ha
    val scope = rememberCoroutineScope()
    val connection by ha.client.connection.collectAsState()
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    var url by remember { mutableStateOf(settings.haUrl.ifEmpty { ha.auth.baseUrl ?: "" }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { ha.client.start() }

    SettingsPage(stringResource(R.string.settings_home_assistant)) {
        InfoRow(stringResource(R.string.ha_status), connectionLabel(connection))
        LauncherTextField(
            stringResource(R.string.ha_url), url, { url = it; update { s -> s.copy(haUrl = it) } },
            kind = TextKind.URL, placeholder = "http://homeassistant.local:8123",
            modifier = Modifier.restoreFocusIf(true),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LauncherButton(
                stringResource(if (ha.auth.isLoggedIn) R.string.ha_relogin else R.string.ha_login),
                icon = Icons.Filled.Login, primary = !ha.auth.isLoggedIn, enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { ha.auth.login(url) }
                            .onSuccess { ha.client.restart() }
                            .onFailure { error = it.message }
                        busy = false
                        // Bring the launcher back in front of the browser tab.
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
            if (ha.auth.isLoggedIn) {
                HoldToConfirmButton(stringResource(R.string.ha_logout), onConfirmed = { ha.client.stop(); ha.auth.logout() }, icon = Icons.AutoMirrored.Filled.Logout)
            }
        }
        if (busy) {
            Row { CircularProgressIndicator(color = LauncherTheme.colors.accent); Text("  " + stringResource(R.string.ha_login_waiting), color = LauncherTheme.colors.onSurfaceMuted) }
        }
        error?.let { Text(it, color = LauncherTheme.colors.danger) }

        SectionHeader(stringResource(R.string.ha_tiles))
        SwitchRow(stringResource(R.string.ha_show_home_row), settings.showHaRowOnHome, { v -> update { it.copy(showHaRowOnHome = v) } }, icon = Icons.Filled.Home)
        tiles.forEachIndexed { index, tile ->
            TileRow(tile, entities[tile.entityId], ha, isFirst = index == 0, isLast = index == tiles.lastIndex)
        }
        LauncherButton(
            stringResource(R.string.ha_add_tile), icon = Icons.Filled.Add,
            enabled = ha.auth.isLoggedIn,
            onClick = {
                ha.client.start()
                picking = true
            },
        )
        if (!ha.auth.isLoggedIn) {
            Text(stringResource(R.string.ha_login_first), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        }
        Text(stringResource(R.string.ha_events_help), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    }

    EntityPicker(
        visible = picking,
        entities = entities.values.filter { it.domain in HaRepository.supportedDomains && it.id !in tiles.map { t -> t.entityId } },
        onPick = { entity -> scope.launch { ha.addTile(entity) } },
        onDismiss = { picking = false },
    )
}

@Composable
private fun TileRow(tile: HaTileEntity, entity: HaEntity?, ha: HaRepository, isFirst: Boolean, isLast: Boolean) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entity?.name ?: tile.entityId, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherButton(
                stringResource(if (tile.showOnHome) R.string.ha_on_home else R.string.ha_not_on_home),
                icon = Icons.Filled.Home,
                onClick = { scope.launch { ha.setShowOnHome(tile, !tile.showOnHome) } },
            )
            if (!isFirst) LauncherButton("", icon = Icons.Filled.ArrowUpward, onClick = { scope.launch { ha.move(tile, -1) } })
            if (!isLast) LauncherButton("", icon = Icons.Filled.ArrowDownward, onClick = { scope.launch { ha.move(tile, 1) } })
            LauncherButton("", icon = Icons.Filled.Delete, onClick = { scope.launch { ha.removeTile(tile.entityId) } })
        }
    }
}

/** Searchable entity list; the search uses the controller keyboard in controller mode. */
@Composable
private fun EntityPicker(visible: Boolean, entities: List<HaEntity>, onPick: (HaEntity) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(R.string.ha_add_tile), maxWidth = 760.dp) {
        LauncherTextField(stringResource(R.string.hint_search), query, { query = it })
        Spacer(Modifier.height(12.dp))
        if (entities.isEmpty()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(color = LauncherTheme.colors.accent)
                Text("  " + stringResource(R.string.ha_connecting), color = LauncherTheme.colors.onSurfaceMuted)
            }
            return@Modal
        }
        val q = TitleNormalizer.normalize(query)
        val shown = entities
            .filter { q.isEmpty() || TitleNormalizer.normalize(it.name).contains(q) || it.id.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy({ it.domain }, { it.name }))
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { entity ->
                FocusSurface(
                    onClick = { onPick(entity) },
                    modifier = Modifier.fillMaxWidth(),
                    color = LauncherTheme.colors.surfaceRaised,
                    focusScale = 1.02f,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(entity.name, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
                        Text("${entity.id}  ·  ${entity.state}", style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    }
                }
            }
        }
    }
}

/** Favourite tiles as a row on the hero home screen. */
@Composable
fun HaHomeRow() {
    val ha = LocalContext.current.container.ha
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    val shown = tiles.filter { it.showOnHome }
    if (shown.isEmpty()) return
    Column {
        Text(
            stringResource(R.string.settings_home_assistant),
            style = MaterialTheme.typography.titleLarge,
            color = LauncherTheme.colors.onSurface,
            modifier = Modifier.padding(start = 40.dp, top = 6.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { it.entityId }) { tile -> HaTile(tile, entities[tile.entityId]) }
        }
    }
}
