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
 * and process names. Empty fields keep what the scan found (shown as the placeholder).
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
        var launch by remember(e.id) { mutableStateOf(current.launch.orEmpty()) }
        var args by remember(e.id) { mutableStateOf(current.arguments.orEmpty()) }
        var dir by remember(e.id) { mutableStateOf(current.workingDir.orEmpty()) }
        var processes by remember(e.id) { mutableStateOf(current.processNames?.joinToString(", ").orEmpty()) }
        fun save(o: LaunchOverride?) = update { s ->
            val map = LaunchOverride.decode(s.pcLaunchOverrides)
            s.copy(pcLaunchOverrides = LaunchOverride.encode(if (o == null || o.isEmpty) map - e.id else map + (e.id to o)))
        }
        Column(Modifier.heightIn(max = 620.dp).controllerScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.launch_options_intro), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            LauncherTextField(stringResource(Res.string.custom_path), launch, { launch = it.trim().removeSurrounding("\"") }, placeholder = e.launch ?: e.install)
            LauncherButton(stringResource(Res.string.emu_pc_browse), icon = Icons.Filled.FolderOpen, onClick = {
                FilePickers.pickFile(launch.ifBlank { e.launch })?.let { launch = it }
            })
            LauncherTextField(stringResource(Res.string.custom_args), args, { args = it }, placeholder = e.launchArgs)
            LauncherTextField(stringResource(Res.string.custom_workdir), dir, { dir = it.trim().removeSurrounding("\"") }, placeholder = e.workingDir ?: e.installDir)
            LauncherTextField(
                stringResource(Res.string.launch_options_processes), processes, { processes = it },
                placeholder = e.processNames.joinToString(", ").ifEmpty { null },
            )
            Text(stringResource(Res.string.launch_options_processes_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            ButtonRow {
                LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
                    save(
                        LaunchOverride(
                            launch = launch.ifBlank { null }, arguments = args.trim().ifEmpty { null }, workingDir = dir.ifBlank { null },
                            processNames = processes.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null },
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
