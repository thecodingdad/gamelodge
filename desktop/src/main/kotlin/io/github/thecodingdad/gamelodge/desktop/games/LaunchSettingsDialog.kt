package io.github.thecodingdad.gamelodge.desktop.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
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
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Launch options of one scanned PC game (tile menu): program or link, arguments, working folder
 * and process names. The fields start with the current values; what equals the scan's value (or is
 * cleared) keeps following the scan.
 */
@Composable
fun LaunchSettingsDialog(
    title: String?,
    original: GameEntry?,
    settings: LauncherSettings,
    update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    Modal(visible = original != null, onDismiss = onDismiss, title = title, maxWidth = 760.dp) {
        val e = original ?: return@Modal
        val current = remember(e.id) { LaunchOverride.decode(settings.pcLaunchOverrides)[e.id] ?: LaunchOverride() }
        // The scan's values, also what the fields start with when there is no own value.
        val found = Found(e.launch ?: e.install, e.launchArgs, e.workingDir ?: e.installDir, e.processNames.joinToString(", "))
        var launch by remember(e.id) { mutableStateOf(current.launch ?: found.launch.orEmpty()) }
        var args by remember(e.id) { mutableStateOf(current.arguments ?: found.args.orEmpty()) }
        var dir by remember(e.id) { mutableStateOf(current.workingDir ?: found.dir.orEmpty()) }
        var processes by remember(e.id) { mutableStateOf(current.processNames?.joinToString(", ") ?: found.processes) }
        fun save(o: LaunchOverride?) = update { s ->
            val map = LaunchOverride.decode(s.pcLaunchOverrides)
            s.copy(pcLaunchOverrides = LaunchOverride.encode(if (o == null || o.isEmpty) map - e.id else map + (e.id to o)))
        }
        Column(Modifier.heightIn(max = 620.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.launch_options_intro), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherTextField(stringResource(Res.string.custom_path), launch, { launch = it.trim().removeSurrounding("\"") }, placeholder = found.launch)
            LauncherButton(stringResource(Res.string.emu_pc_browse), icon = Icons.Filled.FolderOpen, onClick = {
                FilePickers.pickFile(launch.ifBlank { e.launch })?.let { launch = it }
            })
            LauncherTextField(stringResource(Res.string.custom_args), args, { args = it }, placeholder = found.args)
            LauncherTextField(stringResource(Res.string.custom_workdir), dir, { dir = it.trim().removeSurrounding("\"") }, placeholder = found.dir)
            LauncherTextField(
                stringResource(Res.string.launch_options_processes), processes, { processes = it },
                placeholder = found.processes.ifEmpty { null },
            )
            Text(stringResource(Res.string.launch_options_processes_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            ButtonRow {
                LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
                    // Only what differs from the scan is kept: the rest follows later scans.
                    val names = processes.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                    save(
                        LaunchOverride(
                            launch = launch.ifBlank { null }?.takeIf { it != found.launch },
                            arguments = args.trim().ifEmpty { null }?.takeIf { it != found.args?.trim() },
                            workingDir = dir.ifBlank { null }?.takeIf { it != found.dir },
                            processNames = names.ifEmpty { null }?.takeIf { it.joinToString(", ") != found.processes },
                        ),
                    )
                    onDismiss()
                })
                LauncherButton(stringResource(Res.string.launch_options_reset), icon = Icons.Filled.Restore, onClick = {
                    save(null)
                    onDismiss()
                })
            }
        }
    }
}

/** What the scan found for a game. */
private data class Found(val launch: String?, val args: String?, val dir: String?, val processes: String)
