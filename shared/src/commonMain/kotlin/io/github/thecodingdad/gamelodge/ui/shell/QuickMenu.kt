package io.github.thecodingdad.gamelodge.ui.shell

import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Speed
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.ui.Modifier
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SportsEsports
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.stream.PowerAction
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import io.github.thecodingdad.gamelodge.ui.RunningSession
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.SidePanel
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SliderRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.thecodingdad.gamelodge.resources.*

/** Tabs of the quick menu, in order; the rail on the left switches between them. */
enum class QuickMenuTab(val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    RUNNING(Icons.Filled.PlayCircle),
    QUICK(Icons.Filled.Tune),
    HOSTS(Icons.Filled.Computer),
    CONTROLLERS(Icons.Filled.SportsEsports),
    HOME_ASSISTANT(Icons.Filled.Home),
    SETTINGS(Icons.Filled.Settings),
}

/**
 * Y or Select (hold) anywhere in the launcher, or the status bar's host and controller icons: what
 * is running (resume, quit, stream controls), quick access (toggles, volume, brightness), hosts,
 * controllers and Home Assistant as tabs. The rail on the left switches them by touch or mouse,
 * LB/RB with a controller.
 */
@Composable
fun QuickMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    running: List<RunningSession>,
    onResume: (RunningSession) -> Unit,
    /** Opens on this tab (status bar icons); null: what is running, else quick access. */
    requestedTab: QuickMenuTab? = null,
    openHostSettings: (io.github.thecodingdad.gamelodge.data.remote.HostState) -> Unit = {},
    openControllerSettings: () -> Unit = {},
    openHaSettings: () -> Unit = {},
) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val platform = io.github.thecodingdad.gamelodge.ui.PlatformUi.current
    val local by io.github.thecodingdad.gamelodge.stream.LocalSessions.running.collectAsState()

    SidePanel(visible = visible, onDismiss = onDismiss, width = 600.dp) {
        val hasRunning = running.isNotEmpty() || local != null
        val tabs = remember(hasRunning) { QuickMenuTab.entries.filter {
            (it != QuickMenuTab.RUNNING || hasRunning) && (it != QuickMenuTab.SETTINGS || platform.hasQuickMenuSettings)
        } }
        var tab by remember(visible, requestedTab) {
            mutableStateOf(requestedTab ?: if (hasRunning) QuickMenuTab.RUNNING else QuickMenuTab.QUICK)
        }
        if (tab !in tabs) tab = QuickMenuTab.QUICK
        fun step(delta: Int) { tab = tabs[(tabs.indexOf(tab) + delta).coerceIn(0, tabs.lastIndex)] }
        io.github.thecodingdad.gamelodge.ui.input.PadAction(io.github.thecodingdad.gamelodge.ui.input.PadButton.L1) { step(-1) }
        io.github.thecodingdad.gamelodge.ui.input.PadAction(io.github.thecodingdad.gamelodge.ui.input.PadButton.R1) { step(1) }

        val haFocus = remember { io.github.thecodingdad.gamelodge.ui.screens.ha.HaTileFocus() }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            QuickRail(tabs, tab, onDismiss) { tab = it }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                var editing by remember(visible) { mutableStateOf(false) }
                if (platform.hasQuickAccessEditor) platform.QuickAccessEditor(editing) { editing = false }
                var haEditing by remember(visible) { mutableStateOf(false) }
                io.github.thecodingdad.gamelodge.ui.screens.ha.HaTileEditor(haEditing) { haEditing = false }
                Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        when (tab) {
                            QuickMenuTab.RUNNING -> Res.string.qm_section_running
                            QuickMenuTab.QUICK -> Res.string.qm_section_quick
                            QuickMenuTab.HOSTS -> Res.string.settings_hosts
                            QuickMenuTab.CONTROLLERS -> Res.string.settings_controllers
                            QuickMenuTab.HOME_ASSISTANT -> Res.string.settings_home_assistant
                            QuickMenuTab.SETTINGS -> Res.string.tab_settings
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge, color = LauncherTheme.colors.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 18.dp, top = 18.dp, bottom = 6.dp),
                )
                if (tab == QuickMenuTab.QUICK && platform.hasQuickAccessEditor) {
                    EditButton(Modifier.padding(top = 12.dp, end = 14.dp)) { editing = true }
                }
                // Home Assistant: its tiles, edited the same way (once signed in).
                if (tab == QuickMenuTab.HOME_ASSISTANT && c.ha.auth.isLoggedIn) {
                    EditButton(Modifier.padding(top = 12.dp, end = 14.dp)) { haEditing = true }
                }
                }
                // Each tab its own scroll position and focus start.
                androidx.compose.runtime.key(tab) {
                androidx.compose.runtime.CompositionLocalProvider(io.github.thecodingdad.gamelodge.ui.screens.ha.LocalHaTileFocus provides haFocus) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().controllerScroll(rememberScrollState())
                            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when (tab) {
                            QuickMenuTab.RUNNING -> {
                                local?.let { LocalRunningCard(it, onDismiss) }
                                running.forEachIndexed { i, session ->
                                    RunningCard(
                                        session, initialFocus = local == null && i == 0,
                                        onResume = { onResume(session); onDismiss() },
                                        onQuit = {
                                            onDismiss()
                                            io.github.thecodingdad.gamelodge.ui.PlatformUi.current.bringToFront()
                                        },
                                    )
                                }
                            }
                            QuickMenuTab.QUICK -> {
                                // Running background work (the status bar spinner is touch only).
                                TasksRow()
                                val settings by c.settings.settings.collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
                                val navigate = io.github.thecodingdad.gamelodge.ui.screens.settings.LocalSettingsNavigator.current
                                QuickToggles(rememberQuickTiles(settings.quickTiles)) { target -> onDismiss(); navigate(target, null) }
                                if (platform.hasSystemControls) {
                                    androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                                    platform.SystemControls(visible)
                                }
                            }
                            QuickMenuTab.HOSTS -> HostsTab(running, openHostSettings)
                            QuickMenuTab.CONTROLLERS -> ControllersTab(openControllerSettings)
                            QuickMenuTab.HOME_ASSISTANT -> HomeAssistantSection(openHaSettings)
                            QuickMenuTab.SETTINGS -> platform.QuickMenuSettingsTab()
                        }
                    }
                }
                }
            }
        }
        // Like the launcher's own screens: only with a controller connected; touchable as well.
        // On a Home Assistant tile: what A and the stick do there. (B to close is left out: obvious.)
        val tileHints = io.github.thecodingdad.gamelodge.ui.screens.ha.haTileHints(haFocus.tile.takeIf { tab == QuickMenuTab.HOME_ASSISTANT })
        io.github.thecodingdad.gamelodge.ui.components.ButtonHintBar(
            tileHints.ifEmpty {
                listOf(io.github.thecodingdad.gamelodge.ui.components.ButtonHint(io.github.thecodingdad.gamelodge.ui.input.PadButton.A, stringResource(Res.string.hint_select)))
            } + io.github.thecodingdad.gamelodge.ui.components.ButtonHint(
                io.github.thecodingdad.gamelodge.ui.input.PadButton.L1, stringResource(Res.string.hint_tabs), { step(1) },
                secondButton = io.github.thecodingdad.gamelodge.ui.input.PadButton.R1,
            ),
            inPopup = true,
        )
    }
}

/** Small pencil next to the tab title (reachable with the D-pad from the first row). */
@Composable
private fun EditButton(modifier: Modifier, onClick: () -> Unit) {
    val colors = LauncherTheme.colors
    io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
        onClick = onClick, modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Icon(
            Icons.Filled.Edit, stringResource(Res.string.qm_edit), tint = colors.onSurfaceMuted,
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

/** Paired hosts with their state, wake and power actions. */
@Composable
private fun HostsTab(running: List<RunningSession>, openSettings: (io.github.thecodingdad.gamelodge.data.remote.HostState) -> Unit) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val all by c.hosts.hosts.collectAsState()
    val paired = remember(all) { all.values.filter { it.paired }.sortedBy { it.name.lowercase() } }
    if (paired.isEmpty()) {
        Text(stringResource(Res.string.hosts_none_paired), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
    }
    paired.forEachIndexed { i, host ->
        HostCard(host, running.firstOrNull { it.host.uuid == host.uuid }?.appName, openSettings, initialFocus = i == 0)
    }
}

/** Connected controllers (battery, details with rumble test) and the way to their settings. */
@Composable
private fun ControllersTab(openSettings: () -> Unit) {
    val colors = LauncherTheme.colors
    val controllers = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers()
    var selected by remember { mutableStateOf<io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo?>(null) }
    io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerDetailDialog(selected, onDismiss = { selected = null })
    Text(stringResource(Res.string.controller_popup_sub), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
    if (controllers.isEmpty()) Text(stringResource(Res.string.controllers_none), color = colors.onSurface)
    controllers.forEachIndexed { i, controller ->
        val details = listOfNotNull(
            stringResource(if (controller.external) Res.string.controller_external else Res.string.controller_builtin),
            controller.batteryPercent?.let { stringResource(Res.string.controller_battery, it) },
        ).joinToString("  ·  ")
        io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow(
            controller.name, { selected = controller }, subtitle = details, icon = Icons.Filled.SportsEsports,
            initialFocus = i == 0,
        )
    }
    LauncherButton(
        stringResource(Res.string.controller_popup_settings), onClick = openSettings, icon = Icons.Filled.Settings,
        modifier = Modifier.restoreFocusIf(controllers.isEmpty()),
    )
}

/** Tab icons on the left edge of the panel; touch and mouse switch, LB/RB with a controller. */
@Composable
private fun QuickRail(tabs: List<QuickMenuTab>, current: QuickMenuTab, onDismiss: () -> Unit, onSelect: (QuickMenuTab) -> Unit) {
    val colors = LauncherTheme.colors
    Column(
        Modifier.fillMaxHeight().width(72.dp).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        io.github.thecodingdad.gamelodge.ui.components.CloseArrow(onDismiss)
        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
        tabs.forEach { t ->
            val selected = t == current
            io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                onClick = { onSelect(t) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = if (selected) colors.accent.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent,
                focusScale = 1f,
                dpadFocusable = false,
            ) {
                androidx.compose.material3.Icon(
                    t.icon, null,
                    tint = if (selected) colors.accent else colors.onSurfaceMuted,
                    modifier = Modifier.padding(12.dp).size(26.dp),
                )
            }
        }
    }
}

/**
 * A game or app running on a host: resume the stream or quit it there (held). The stream this
 * launcher runs gets its controls as well: performance, keyboard, server commands, disconnect.
 */
@Composable
private fun RunningCard(session: RunningSession, initialFocus: Boolean, onResume: () -> Unit, onQuit: () -> Unit) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val quitting by io.github.thecodingdad.gamelodge.stream.Quitting.keys.collectAsState()
    val active by c.launcher.activeStream.collectAsState()
    val own = active?.hostUuid == session.host.uuid
    val canResume = session.entry?.remoteTargets?.any { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId } == true
    RunningCardLayout(
        entry = session.entry,
        title = session.appName.ifBlank { session.entry?.item?.title.orEmpty() },
        where = stringResource(Res.string.qm_running_on_host, session.host.name),
        onResume = onResume.takeIf { canResume },
        initialFocus = initialFocus,
        quitting = io.github.thecodingdad.gamelodge.stream.Quitting.hostKey(session.host) in quitting,
        onQuit = {
            // The menu stays open until the game is gone (its card then disappears); for our own
            // stream the launcher comes back to the front afterwards.
            io.github.thecodingdad.gamelodge.stream.Quitting.quitHost(
                c, session.host, session.appName.ifBlank { session.entry?.item?.title ?: session.host.name },
                onDone = { if (own) onQuit() },
            )
        },
        extra = if (own) { { StreamControlsSection(c.streamControls, onQuit) } } else null,
    )
}

/** Controls of the stream this launcher runs, as far as its client offers them. */
@Composable
private fun StreamControlsSection(controls: io.github.thecodingdad.gamelodge.stream.StreamControls, onLeave: () -> Unit) {
    val commands = remember { controls.serverCommands() }
    var commandList by remember { mutableStateOf(false) }
    io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
        LauncherButton(stringResource(Res.string.qm_disconnect), { controls.disconnect(); onLeave() }, icon = Icons.Filled.LinkOff)
        // The stream's own performance overlay (shown in the stream, not here).
        LauncherButton(stringResource(Res.string.qm_overlay), { controls.toggleOverlay() }, icon = Icons.Filled.Speed)
        if (commands.isNotEmpty()) {
            LauncherButton(stringResource(Res.string.qm_server_commands), { commandList = true }, icon = Icons.Filled.Terminal)
        }
    }
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = commandList, onDismiss = { commandList = false }, title = stringResource(Res.string.qm_server_commands),
    ) {
        Column(
            Modifier.weight(1f, fill = false).popupHeightIn(520.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.qm_server_commands_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            commands.forEachIndexed { i, command ->
                LauncherButton(command, { controls.runServerCommand(i); commandList = false }, Modifier.fillMaxWidth(), Icons.Filled.Terminal)
            }
        }
    }
}

/** A game the launcher started on this PC. */
@Composable
private fun LocalRunningCard(session: io.github.thecodingdad.gamelodge.stream.LocalSessions.Running, onDismiss: () -> Unit) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val quitting by io.github.thecodingdad.gamelodge.stream.Quitting.keys.collectAsState()
    val key = "local:${session.entry.item.id}"
    val platform = io.github.thecodingdad.gamelodge.ui.PlatformUi.current
    RunningCardLayout(
        entry = session.entry,
        title = session.entry.item.title,
        where = stringResource(Res.string.qm_running_here),
        onResume = { session.resume(); onDismiss() },
        initialFocus = true,
        quitting = key in quitting,
        onQuit = {
            io.github.thecodingdad.gamelodge.stream.Quitting.start(
                c.scope, key, session.entry.item.title,
                quit = { session.quit() },
                running = { io.github.thecodingdad.gamelodge.stream.LocalSessions.running.value?.entry?.item?.id == session.entry.item.id },
                onDone = { onDismiss(); platform.bringToFront() },
            )
        },
    )
}

@Composable
private fun RunningCardLayout(
    entry: io.github.thecodingdad.gamelodge.data.db.LibraryEntry?,
    title: String,
    where: String,
    onResume: (() -> Unit)?,
    initialFocus: Boolean,
    onQuit: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    /** Being quit: a spinner on the quit button, nothing else to do on this card meanwhile. */
    quitting: Boolean = false,
) {
    val colors = LauncherTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(LauncherTheme.dimens.cornerRadius))
            .background(colors.surfaceRaised).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (entry != null) {
                io.github.thecodingdad.gamelodge.ui.components.CoverImage(
                    entry, Modifier.width(54.dp).aspectRatio(0.75f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                    showTitleOnPlaceholder = false,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(where, style = MaterialTheme.typography.labelMedium, color = colors.accent)
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow(spacing = 10.dp) {
            if (onResume != null) {
                LauncherButton(
                    stringResource(Res.string.qm_resume), onResume, Modifier.restoreFocusIf(initialFocus), Icons.Filled.PlayArrow,
                    primary = true, enabled = !quitting,
                )
            }
            if (quitting) {
                LauncherButton(stringResource(Res.string.qm_quitting), {}, Modifier.restoreFocusIf(true), busy = true)
            } else {
                HoldToConfirmButton(
                    stringResource(Res.string.qm_quit_game), onConfirmed = onQuit, icon = Icons.Filled.StopCircle,
                    modifier = Modifier.restoreFocusIf(initialFocus && onResume == null),
                )
            }
        }
        if (!quitting) extra?.invoke()
    }
}

/** The configured tiles as round toggles, four to a row (long press / hold A: their settings). */
@Composable
private fun QuickToggles(
    tiles: List<TileModel>,
    onSettings: (io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tiles.chunked(TOGGLES_PER_ROW).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { i, tile ->
                    QuickToggle(
                        tile,
                        onLongClick = quickTileSettings(tile.key)?.let { target -> { onSettings(target) } },
                        modifier = Modifier.weight(1f).then(if (r == 0 && i == 0) Modifier.restoreFocusIf(true) else Modifier),
                    )
                }
                repeat(TOGGLES_PER_ROW - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickToggle(tile: TileModel, onLongClick: (() -> Unit)?, modifier: Modifier) {
    val colors = LauncherTheme.colors
    val active = tile.tint != colors.onSurfaceMuted
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
            onClick = tile.onClick,
            onLongClick = onLongClick,
            shape = androidx.compose.foundation.shape.CircleShape,
            color = if (active) tile.tint.copy(alpha = 0.2f) else colors.surfaceRaised,
            focusScale = 1.08f,
        ) {
            Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                if (tile.packageName != null) {
                    io.github.thecodingdad.gamelodge.ui.components.AppIcon(tile.packageName, Modifier.size(30.dp))
                } else if (tile.icon != null) {
                    androidx.compose.material3.Icon(tile.icon, null, tint = tile.tint, modifier = Modifier.size(26.dp))
                }
            }
        }
        Text(
            tile.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private const val TOGGLES_PER_ROW = 4

/** Home Assistant tiles (formerly a panel of its own). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HomeAssistantSection(openSettings: () -> Unit) {
    val ha = io.github.thecodingdad.gamelodge.LocalContainer.current.ha
    val tiles by ha.tiles.collectAsState(emptyList())
    val entities by ha.client.entities.collectAsState()
    val connection by ha.client.connection.collectAsState()
    LaunchedEffect(Unit) { ha.client.start() }
    if (connection == io.github.thecodingdad.gamelodge.ha.HaConnection.NOT_CONFIGURED || tiles.isEmpty()) {
        Text(stringResource(Res.string.ha_panel_empty), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        LauncherButton(stringResource(Res.string.ha_setup), icon = Icons.Filled.Settings, onClick = openSettings, modifier = Modifier.restoreFocusIf(true))
    } else {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tiles.forEachIndexed { i, tile ->
                io.github.thecodingdad.gamelodge.ui.screens.ha.HaTile(
                    tile, entities[tile.entityId], Modifier.restoreFocusIf(i == 0), width = 210.dp,
                )
            }
        }
    }
}


