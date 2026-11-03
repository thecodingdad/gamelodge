package io.github.thecodingdad.gamelodge.desktop.games

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.desktop.FilePickers
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.stream.StartAction
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

/** What the start actions popup edits: the game's own command, a store action (read only) or an own one. */
private sealed interface Editing {
    data object Play : Editing
    data class Store(val action: CustomAction) : Editing
    /** [action] null: a new one. */
    data class Own(val action: CustomAction?) : Editing
}

/**
 * Start actions of one scanned PC game (tile menu): "play" (the game's command, changeable),
 * the store's launch options and own actions (launcher, DX11 variant, mod manager …). One of them
 * is the default of a plain start; "start with…" offers all of them, also to paired launchers
 * when this PC is their streaming host.
 */
@Composable
fun LaunchSettingsDialog(
    title: String?,
    original: GameEntry?,
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember(original?.id) { mutableStateOf<Editing?>(null) }
    val e = original
    val override = e?.let { LaunchOverride.decode(settings.pcLaunchOverrides)[it.id] } ?: LaunchOverride()
    fun save(o: LaunchOverride) = update { s ->
        val id = e?.id ?: return@update s
        val map = LaunchOverride.decode(s.pcLaunchOverrides)
        s.copy(pcLaunchOverrides = LaunchOverride.encode(if (o.isEmpty) map - id else map + (id to o)))
    }
    val ids = buildList {
        add(StartAction.PLAY)
        e?.storeActions?.forEach { add(it.id) }
        override.actions?.forEach { add(it.id) }
    }
    val default = override.defaultAction?.takeIf { it in ids } ?: StartAction.PLAY
    val playName = stringResource(Res.string.action_play)
    val defaultLabel = stringResource(Res.string.start_action_default)

    Modal(visible = e != null && editing == null, onDismiss = onDismiss, title = stringResource(Res.string.start_actions), size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD) {
        val game = e ?: return@Modal
        Column(Modifier.weight(1f, fill = false).popupHeightIn(620.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title ?: game.name, style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            Text(stringResource(Res.string.start_actions_intro), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            NavRow(
                playName, { editing = Editing.Play }, subtitle = (override.launch ?: game.launch ?: game.install).orEmpty(),
                icon = Icons.Filled.PlayArrow, trailing = defaultLabel.takeIf { default == StartAction.PLAY }, initialFocus = true,
            )
            game.storeActions.forEach { a ->
                NavRow(
                    a.name, { editing = Editing.Store(a) }, subtitle = listOfNotNull(a.launch, a.arguments).joinToString(" "),
                    icon = Icons.Filled.Terminal, trailing = defaultLabel.takeIf { default == a.id },
                )
            }
            override.actions.orEmpty().forEach { a ->
                NavRow(
                    a.name, { editing = Editing.Own(a) }, subtitle = listOfNotNull(a.launch, a.arguments).joinToString(" "),
                    icon = Icons.Filled.Terminal, trailing = defaultLabel.takeIf { default == a.id },
                )
            }
            LauncherButton(stringResource(Res.string.start_action_add), icon = Icons.Filled.Add, onClick = { editing = Editing.Own(null) })
        }
    }

    // One action: its fields, "use as default" and (own ones) delete.
    val current = editing
    Modal(
        visible = e != null && current != null, onDismiss = { editing = null },
        title = when (current) {
            Editing.Play -> playName
            is Editing.Store -> current.action.name
            is Editing.Own -> current.action?.name ?: stringResource(Res.string.start_action_add)
            null -> null
        },
        size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD,
    ) {
        val game = e ?: return@Modal
        val c = current ?: return@Modal
        val id = when (c) {
            Editing.Play -> StartAction.PLAY
            is Editing.Store -> c.action.id
            is Editing.Own -> c.action?.id
        }
        Column(Modifier.weight(1f, fill = false).popupHeightIn(620.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (c) {
                Editing.Play -> PlayCommandFields(game, override, onSave = { save(it); editing = null })
                is Editing.Store -> {
                    Text(stringResource(Res.string.start_action_store), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                    Text(listOfNotNull(c.action.launch, c.action.arguments).joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurface)
                }
                is Editing.Own -> OwnActionFields(game, c.action, onSave = { a ->
                    val list = override.actions.orEmpty()
                    save(override.copy(actions = if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a))
                    editing = null
                })
            }
            if (id != null && id != default) {
                LauncherButton(stringResource(Res.string.start_action_make_default), icon = Icons.Filled.CheckCircle, onClick = {
                    save(override.copy(defaultAction = id.takeIf { it != StartAction.PLAY }))
                    editing = null
                })
            }
            if (c is Editing.Own && c.action != null) {
                HoldToConfirmButton(stringResource(Res.string.start_action_delete), onConfirmed = {
                    save(override.copy(
                        actions = override.actions.orEmpty().filter { it.id != c.action.id }.ifEmpty { null },
                        defaultAction = override.defaultAction.takeIf { it != c.action.id },
                    ))
                    editing = null
                }, icon = Icons.Filled.Delete)
            }
        }
    }
}

/**
 * The game's own start command: program or link, arguments, working folder and process names.
 * The fields start with the current values; what equals the scan's value (or is cleared) keeps
 * following the scan.
 */
@Composable
private fun PlayCommandFields(e: GameEntry, current: LaunchOverride, onSave: (LaunchOverride) -> Unit) {
    // The scan's values, also what the fields start with when there is no own value.
    val found = Found(e.launch ?: e.install, e.launchArgs, e.workingDir ?: e.installDir, e.processNames.joinToString(", "))
    var launch by remember(e.id) { mutableStateOf(current.launch ?: found.launch.orEmpty()) }
    var args by remember(e.id) { mutableStateOf(current.arguments ?: found.args.orEmpty()) }
    var dir by remember(e.id) { mutableStateOf(current.workingDir ?: found.dir.orEmpty()) }
    var processes by remember(e.id) { mutableStateOf(current.processNames?.joinToString(", ") ?: found.processes) }
    Text(stringResource(Res.string.launch_options_intro), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    LauncherTextField(stringResource(Res.string.custom_path), launch, { launch = it.trim().removeSurrounding("\"") }, placeholder = found.launch)
    LauncherButton(stringResource(Res.string.emu_pc_browse), icon = Icons.Filled.FolderOpen, onClick = {
        FilePickers.pickFile(launch.ifBlank { e.launch })?.let { launch = it }
    })
    LauncherTextField(stringResource(Res.string.custom_args), args, { args = it }, placeholder = found.args)
    LauncherTextField(stringResource(Res.string.custom_workdir), dir, { dir = it.trim().removeSurrounding("\"") }, placeholder = found.dir)
    LauncherTextField(stringResource(Res.string.launch_options_processes), processes, { processes = it }, placeholder = found.processes.ifEmpty { null })
    Text(stringResource(Res.string.launch_options_processes_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    ButtonRow {
        LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
            // Only what differs from the scan is kept: the rest follows later scans.
            val names = processNames(processes)
            onSave(
                current.copy(
                    launch = launch.ifBlank { null }?.takeIf { it != found.launch },
                    arguments = args.trim().ifEmpty { null }?.takeIf { it != found.args?.trim() },
                    workingDir = dir.ifBlank { null }?.takeIf { it != found.dir },
                    processNames = names.ifEmpty { null }?.takeIf { it.joinToString(", ") != found.processes },
                ),
            )
        })
        LauncherButton(stringResource(Res.string.launch_options_reset), icon = Icons.Filled.Restore, onClick = { onSave(current.withoutCommand()) })
    }
}

/** An own start action: name, program or link, arguments, working folder, the game's processes. */
@Composable
private fun OwnActionFields(e: GameEntry, action: CustomAction?, onSave: (CustomAction) -> Unit) {
    val key = action?.id ?: "new"
    var name by remember(key) { mutableStateOf(action?.name.orEmpty()) }
    var launch by remember(key) { mutableStateOf(action?.launch.orEmpty()) }
    var args by remember(key) { mutableStateOf(action?.arguments.orEmpty()) }
    var dir by remember(key) { mutableStateOf(action?.workingDir.orEmpty()) }
    var processes by remember(key) { mutableStateOf(action?.processNames?.joinToString(", ").orEmpty()) }
    Text(stringResource(Res.string.start_action_own_intro), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    LauncherTextField(stringResource(Res.string.custom_name), name, { name = it })
    LauncherTextField(stringResource(Res.string.custom_path), launch, { launch = it.trim().removeSurrounding("\"") })
    LauncherButton(stringResource(Res.string.emu_pc_browse), icon = Icons.Filled.FolderOpen, onClick = {
        FilePickers.pickFile(launch.ifBlank { e.launch ?: e.installDir })?.let { launch = it }
    })
    LauncherTextField(stringResource(Res.string.custom_args), args, { args = it })
    LauncherTextField(stringResource(Res.string.custom_workdir), dir, { dir = it.trim().removeSurrounding("\"") })
    LauncherTextField(
        stringResource(Res.string.launch_options_processes), processes, { processes = it },
        placeholder = e.processNames.joinToString(", ").ifEmpty { null },
    )
    Text(stringResource(Res.string.start_action_processes_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    LauncherButton(stringResource(Res.string.action_save), primary = true, enabled = name.isNotBlank() && launch.isNotBlank(), onClick = {
        onSave(
            CustomAction(
                id = action?.id ?: "own:${java.util.UUID.randomUUID()}",
                name = name.trim(), launch = launch,
                arguments = args.trim().ifEmpty { null },
                workingDir = dir.ifBlank { null },
                processNames = processNames(processes).ifEmpty { null },
            ),
        )
    })
}

private fun processNames(text: String) = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** What the scan found for a game. */
private data class Found(val launch: String?, val args: String?, val dir: String?, val processes: String)
