package io.github.thecodingdad.gamelodge.ui.screens.ha

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.mouseDragScroll
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.HaTileEntity
import io.github.thecodingdad.gamelodge.data.library.TitleNormalizer
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.ha.HaConnection
import io.github.thecodingdad.gamelodge.ha.HaEntity
import io.github.thecodingdad.gamelodge.ha.HaRepository
import io.github.thecodingdad.gamelodge.ui.components.ButtonHint
import io.github.thecodingdad.gamelodge.ui.components.ButtonHintBar
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.SidePanel
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

@Composable
fun connectionLabel(state: HaConnection) = stringResource(
    when (state) {
        HaConnection.NOT_CONFIGURED -> Res.string.ha_not_configured
        HaConnection.DISCONNECTED -> Res.string.ha_disconnected
        HaConnection.CONNECTING -> Res.string.ha_connecting
        HaConnection.CONNECTED -> Res.string.ha_connected
        HaConnection.AUTH_FAILED -> Res.string.ha_auth_failed
    },
)

/** Slides in from the right on Y, from any screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HaPanel(visible: Boolean, onDismiss: () -> Unit, openSettings: () -> Unit) {
    val ha = io.github.thecodingdad.gamelodge.LocalContainer.current.ha
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    val connection by ha.client.connection.collectAsState()

    androidx.compose.runtime.LaunchedEffect(visible) { if (visible) ha.client.start() }
    SidePanel(visible = visible, onDismiss = onDismiss, width = 470.dp) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            io.github.thecodingdad.gamelodge.ui.components.CloseArrow(onDismiss)
            Text(stringResource(Res.string.settings_home_assistant), style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface, modifier = Modifier.weight(1f))
            // Entities and sign-in are edited in the settings.
            LauncherButton("", onClick = { onDismiss(); openSettings() }, icon = Icons.Filled.Settings)
        }
        Text(connectionLabel(connection), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        Spacer(Modifier.height(16.dp))
        val haFocus = remember { HaTileFocus() }
        if (connection == HaConnection.NOT_CONFIGURED || tiles.isEmpty()) {
            Text(stringResource(Res.string.ha_panel_empty), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
            Spacer(Modifier.height(16.dp))
            LauncherButton(stringResource(Res.string.ha_setup), onClick = { onDismiss(); openSettings() }, primary = true)
        } else {
            FlowRow(
                Modifier.controllerScroll().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalHaTileFocus provides haFocus) {
                    tiles.forEachIndexed { index, tile ->
                        HaTile(tile, entities[tile.entityId], Modifier.restoreFocusIf(index == 0))
                    }
                }
            }
        }
        ButtonHintBar(
            haTileHints(haFocus.tile) + ButtonHint(PadButton.B, stringResource(Res.string.action_close), onClick = onDismiss),
            inPopup = true,
        )
    }
}

@Composable
fun HaSettings(
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    hosts: List<io.github.thecodingdad.gamelodge.data.remote.HostState> = emptyList(),
    openHost: (String) -> Unit = {},
    openPage: (io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId) -> Unit = {},
) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val ha = c.ha
    val scope = rememberCoroutineScope()
    val connection by ha.client.connection.collectAsState()
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    var loginShown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { ha.client.start() }

    SettingsPage(stringResource(Res.string.settings_home_assistant)) {
        val colors = LauncherTheme.colors
        val url = settings.haUrl.ifEmpty { ha.auth.baseUrl.orEmpty() }
        val loggedIn = ha.auth.isLoggedIn
        val (color, icon) = when (connection) {
            HaConnection.CONNECTED -> colors.success to Icons.Filled.CheckCircle
            HaConnection.CONNECTING -> colors.onSurfaceMuted to Icons.Filled.Sync
            HaConnection.AUTH_FAILED -> colors.danger to Icons.Filled.Lock
            HaConnection.DISCONNECTED -> colors.warning to Icons.Filled.CloudOff
            HaConnection.NOT_CONFIGURED -> colors.onSurfaceMuted to Icons.Filled.Home
        }
        io.github.thecodingdad.gamelodge.ui.components.StatusCard(
            connectionLabel(connection), color, icon,
            details = listOfNotNull(if (loggedIn) url.ifEmpty { null } else stringResource(Res.string.ha_status_not_configured_sub)),
            busy = connection == HaConnection.CONNECTING,
        ) {
            LauncherButton(
                stringResource(if (loggedIn) Res.string.ha_relogin else Res.string.ha_login), onClick = { loginShown = true },
                icon = Icons.Filled.Login, primary = !loggedIn || connection == HaConnection.AUTH_FAILED,
            )
            if (loggedIn) HoldToConfirmButton(stringResource(Res.string.ha_logout), onConfirmed = { ha.client.stop(); ha.auth.logout() }, icon = Icons.AutoMirrored.Filled.Logout)
        }

        SectionHeader(stringResource(Res.string.ha_tiles))
        Text(stringResource(Res.string.ha_entities_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        HaTileList(enabled = loggedIn)

        SectionHeader(stringResource(Res.string.ha_game_actions))
        Text(stringResource(Res.string.ha_game_actions_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        if (!loggedIn) Text(stringResource(Res.string.ha_game_actions_needs_ha), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        val gameActions = io.github.thecodingdad.gamelodge.ha.GameActionSettings.decode(settings.gameActions)
        fun saveGameActions(change: (io.github.thecodingdad.gamelodge.ha.GameActionSettings) -> io.github.thecodingdad.gamelodge.ha.GameActionSettings) =
            update { s -> s.copy(gameActions = io.github.thecodingdad.gamelodge.ha.GameActionSettings.encode(change(io.github.thecodingdad.gamelodge.ha.GameActionSettings.decode(s.gameActions)))) }
        GameActionsList(gameActions.global, { list -> saveGameActions { it.copy(global = list) } }, enabled = loggedIn)

        if (hosts.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.ha_host_actions))
            Text(stringResource(Res.string.ha_host_actions_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            val names = listOf(
                stringResource(Res.string.host_wake), stringResource(Res.string.power_sleep),
                stringResource(Res.string.power_shutdown), stringResource(Res.string.power_restart),
            )
            val none = stringResource(Res.string.ha_host_actions_none)
            hosts.forEach { host ->
                val set by androidx.compose.runtime.produceState(none, host.uuid) {
                    val cfg = c.hostPower.config(host.uuid)
                    value = listOf(cfg.haWakeEntity, cfg.haSleepEntity, cfg.haShutdownEntity, cfg.haRestartEntity)
                        .mapIndexedNotNull { i, v -> names[i].takeIf { v != null } }.joinToString(", ").ifEmpty { none }
                }
                NavRow(host.name, { openHost(host.uuid) }, set, Icons.Filled.Computer)
            }
        }
        SectionHeader(stringResource(Res.string.mqtt_title))
        Text(stringResource(Res.string.mqtt_section_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        MqttRow(settings, update)

        Text(stringResource(Res.string.ha_events_help), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlso(
            io.github.thecodingdad.gamelodge.ui.screens.settings.SeeAlsoLink(
                stringResource(Res.string.settings_home_screen),
                io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Page(io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId.HOME_SCREEN),
                highlight = stringResource(Res.string.home_row_ha), subtitle = stringResource(Res.string.home_row_ha),
            ),
        )
    }

    if (loginShown) LoginDialog(settings, update) { loginShown = false }
}

/** Address of Home Assistant and the sign-in in the browser. */
@Composable
internal fun LoginDialog(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit, onDismiss: () -> Unit) {
    val ha = io.github.thecodingdad.gamelodge.LocalContainer.current.ha
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(settings.haUrl.ifEmpty { ha.auth.baseUrl ?: "" }) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The login page for the phone, as long as a phone login waits.
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = job != null
    fun start(onPhone: Boolean) {
        error = null
        job = scope.launch {
            runCatching { ha.auth.login(url, if (onPhone) { u -> phoneUrl = u } else null) }
                .onSuccess { ha.client.restart(); onDismiss() }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
            job = null
            phoneUrl = null
            // Bring the launcher back in front of the browser tab.
            if (!onPhone) io.github.thecodingdad.gamelodge.ui.PlatformUi.current.bringToFront()
        }
    }
    fun cancel() { job?.cancel(); job = null; phoneUrl = null }
    Modal(visible = true, onDismiss = { cancel(); onDismiss() }, title = stringResource(Res.string.ha_login_title), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        LauncherTextField(
            stringResource(Res.string.ha_url), url, { url = it; update { s -> s.copy(haUrl = it) } },
            kind = TextKind.URL, placeholder = "http://homeassistant.local:8123",
        )
        val qr = phoneUrl
        if (qr != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                io.github.thecodingdad.gamelodge.ui.components.QrCard(qr, 180.dp)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.ha_login_phone_hint), color = LauncherTheme.colors.onSurface)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(color = LauncherTheme.colors.accent, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("  " + stringResource(Res.string.ha_login_phone_waiting), color = LauncherTheme.colors.onSurfaceMuted)
                    }
                }
            }
        } else if (busy) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(color = LauncherTheme.colors.accent)
                Text("  " + stringResource(Res.string.ha_login_waiting), color = LauncherTheme.colors.onSurfaceMuted)
            }
        }
        error?.let { Text(it, color = LauncherTheme.colors.danger) }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow {
            if (!busy) {
                LauncherButton(
                    stringResource(Res.string.ha_login_here), icon = Icons.Filled.Login, primary = true, enabled = url.isNotBlank(),
                    onClick = { start(onPhone = false) },
                )
                LauncherButton(
                    stringResource(Res.string.ha_login_phone), icon = Icons.Filled.QrCode2, enabled = url.isNotBlank(),
                    onClick = { start(onPhone = true) },
                )
            }
            LauncherButton(stringResource(Res.string.action_cancel), onClick = { if (busy) cancel() else onDismiss() })
        }
    }
}

/**
 * The Home Assistant tiles, like the start tab's rows: moved by their handle (or with the
 * controller), removed with ✕, a tile's own setting behind the row; new ones added below
 * (Settings › Home Assistant and the quick menu's edit button). [enabled]: signed in.
 */
@Composable
fun HaTileList(enabled: Boolean) {
    val ha = io.github.thecodingdad.gamelodge.LocalContainer.current.ha
    val scope = rememberCoroutineScope()
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var tileFor by remember { mutableStateOf<String?>(null) }
    io.github.thecodingdad.gamelodge.ui.screens.settings.ReorderableList(
        items = tiles, id = { it.entityId },
        onReorder = { ids -> scope.launch { ha.reorder(ids) } },
    ) { _, tile ->
        val entity = entities[tile.entityId]
        val group = EntityGroup.of(tile.entityId.substringBefore('.'))
        io.github.thecodingdad.gamelodge.ui.screens.settings.RemovableRow(
            tile.label ?: entity?.name ?: tile.entityId,
            subtitle = listOfNotNull(group?.let { stringResource(it.label) }, if (tile.showOnHome) stringResource(Res.string.ha_tile_on_home_short) else null).joinToString(" · "),
            icon = group?.icon,
            onRemove = { scope.launch { ha.removeTile(tile.entityId) } },
            removeLabel = stringResource(Res.string.ha_tile_remove),
            onClick = { tileFor = tile.entityId },
        )
    }
    LauncherButton(
        stringResource(Res.string.ha_add_tile), icon = Icons.Filled.Add, enabled = enabled,
        primary = tiles.isEmpty(), onClick = { ha.client.start(); picking = true },
    )
    tiles.firstOrNull { it.entityId == tileFor }?.let { tile ->
        TileDialog(tile, entities[tile.entityId], ha) { tileFor = null }
    }
    EntityPicker(
        visible = picking, title = stringResource(Res.string.ha_add_tile),
        entities = entities.values.filter { it.id !in tiles.map { t -> t.entityId } },
        domains = HaRepository.supportedDomains,
        onPick = { id -> entities[id]?.let { scope.launch { ha.addTile(it) } } },
        onDismiss = { picking = false },
    )
}

/** The quick menu's Home Assistant tab: its tiles edited in a popup (like quick access). */
@Composable
fun HaTileEditor(visible: Boolean, onDismiss: () -> Unit) {
    io.github.thecodingdad.gamelodge.ui.components.Modal(visible = visible, onDismiss = onDismiss, title = stringResource(Res.string.ha_tiles), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Column(
            Modifier.weight(1f, fill = false).popupHeightIn(640.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { HaTileList(enabled = true) }
    }
}

/**
 * One tile's own settings: its name in GameLodge (empty = Home Assistant's) and whether it shows on
 * the start tab (its place and removal are in the list).
 */
@Composable
private fun TileDialog(tile: HaTileEntity, entity: HaEntity?, ha: HaRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haName = entity?.name ?: tile.entityId
    var name by remember { mutableStateOf(tile.label.orEmpty()) }
    // Restarted per keystroke: the last name typed is the one stored.
    LaunchedEffect(name) { if (name != tile.label.orEmpty()) ha.setLabel(tile.entityId, name) }
    Modal(visible = true, onDismiss = onDismiss, title = name.ifBlank { haName }, size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        Text(tile.entityId, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        LauncherTextField(
            stringResource(Res.string.ha_tile_name), name,
            { name = it },
            placeholder = haName,
        )
        SwitchRow(stringResource(Res.string.ha_tile_on_home), tile.showOnHome, { scope.launch { ha.setShowOnHome(tile, it) } }, icon = Icons.Filled.Home, initialFocus = true)
    }
}

/** Favourite tiles as a row on the hero home screen. */
@Composable
fun HaHomeRow() {
    val ha = io.github.thecodingdad.gamelodge.LocalContainer.current.ha
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    val shown = tiles.filter { it.showOnHome }
    if (shown.isEmpty()) return
    Column {
        Text(
            stringResource(Res.string.settings_home_assistant),
            style = MaterialTheme.typography.titleLarge,
            color = LauncherTheme.colors.onSurface,
            modifier = Modifier.padding(start = 40.dp, top = 6.dp),
        )
        val tileRow = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.foundation.lazy.LazyRow(
            state = tileRow,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 40.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.mouseDragScroll(tileRow, androidx.compose.foundation.gestures.Orientation.Horizontal),
        ) {
            items(shown, key = { it.entityId }) { tile -> HaTile(tile, entities[tile.entityId]) }
        }
    }
}
