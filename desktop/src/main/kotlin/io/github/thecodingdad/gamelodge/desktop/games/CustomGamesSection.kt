package io.github.thecodingdad.gamelodge.desktop.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.desktop.FilePickers
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.UUID

/** Game sources → own games and apps: the list and the dialog to add or change one. */
@Composable
fun CustomGamesSection(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val games = remember(settings.pcCustomGames) { CustomGame.decode(settings.pcCustomGames) }
    fun save(change: (List<CustomGame>) -> List<CustomGame>) = update { it.copy(pcCustomGames = CustomGame.encode(change(CustomGame.decode(it.pcCustomGames)))) }
    var editing by remember { mutableStateOf<CustomGame?>(null) }
    // Network drives can take long or hang: checked off the UI thread (unknown = fine).
    val present by androidx.compose.runtime.produceState(emptyMap<String, Boolean>(), games) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { games.filterNot { it.isLink }.associate { it.path to File(it.path).exists() } }
    }

    SectionHeader(stringResource(Res.string.custom_games))
    Text(stringResource(Res.string.custom_games_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    val missing = stringResource(Res.string.custom_missing)
    games.sortedBy { it.name.lowercase() }.forEach { g ->
        NavRow(
            g.name, { editing = g },
            subtitle = if (present[g.path] != false) g.path else "$missing · ${g.path}",
            icon = if (g.app) Icons.Filled.Apps else Icons.Filled.SportsEsports,
        )
    }
    LauncherButton(stringResource(Res.string.custom_add), icon = Icons.Filled.Add, onClick = { editing = CustomGame("", "", "") })

    Modal(visible = editing != null, onDismiss = { editing = null }, title = stringResource(Res.string.custom_title), maxWidth = 720.dp) {
        val g = editing ?: return@Modal
        var name by remember(g) { mutableStateOf(g.name) }
        var path by remember(g) { mutableStateOf(g.path) }
        var args by remember(g) { mutableStateOf(g.arguments.orEmpty()) }
        var dir by remember(g) { mutableStateOf(g.workingDir.orEmpty()) }
        var app by remember(g) { mutableStateOf(g.app) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherTextField(stringResource(Res.string.custom_name), name, { name = it })
            LauncherTextField(stringResource(Res.string.custom_path), path, { path = it.trim().removeSurrounding("\"") }, placeholder = "C:\\Games\\Game\\game.exe")
            LauncherButton(stringResource(Res.string.emu_pc_browse), icon = Icons.Filled.FolderOpen, onClick = {
                FilePickers.pickFile(path.ifBlank { null })?.let { picked ->
                    path = picked
                    if (name.isBlank()) name = File(picked).nameWithoutExtension
                }
            })
            LauncherTextField(stringResource(Res.string.custom_args), args, { args = it })
            LauncherTextField(stringResource(Res.string.custom_workdir), dir, { dir = it.trim().removeSurrounding("\"") })
            SwitchRow(stringResource(Res.string.custom_is_app), app, { app = it }, subtitle = stringResource(Res.string.custom_is_app_sub))
            ButtonRow {
                LauncherButton(stringResource(Res.string.action_save), primary = true, onClick = {
                    if (name.isBlank() || path.isBlank()) return@LauncherButton
                    val saved = g.copy(
                        id = g.id.ifEmpty { UUID.randomUUID().toString() }, name = name.trim(), path = path,
                        arguments = args.trim().ifEmpty { null }, workingDir = dir.ifEmpty { null }, app = app,
                    )
                    save { list -> list.filterNot { it.id == saved.id } + saved }
                    editing = null
                })
                if (g.id.isNotEmpty()) HoldToConfirmButton(stringResource(Res.string.custom_remove), icon = Icons.Filled.Delete, onConfirmed = {
                    save { list -> list.filterNot { it.id == g.id } }
                    editing = null
                })
            }
        }
    }
}
