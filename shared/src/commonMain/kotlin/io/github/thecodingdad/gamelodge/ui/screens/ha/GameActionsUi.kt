package io.github.thecodingdad.gamelodge.ui.screens.ha

import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.ha.GameAction
import io.github.thecodingdad.gamelodge.ha.GameActionSettings
import io.github.thecodingdad.gamelodge.ha.GameActionTrigger
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val BEFORE_DELAYS = listOf(0, 2, 5, 10, 15, 30, 60)
private val AFTER_DELAYS = listOf(0, 60, 120, 300, 600, 900, 1800)

/** "5 s", "2 min" or "none". */
@Composable
private fun delayLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(Res.string.game_action_delay_none)
    seconds % 60 == 0 -> stringResource(Res.string.game_action_minutes, seconds / 60)
    else -> stringResource(Res.string.game_action_seconds, seconds)
}

@Composable
private fun actionSummary(a: GameAction): String = when (a.trigger) {
    GameActionTrigger.BEFORE_START -> when {
        a.delaySeconds <= 0 -> stringResource(Res.string.game_action_before)
        a.startWaits -> stringResource(Res.string.game_action_sub_before_wait, delayLabel(a.delaySeconds))
        else -> stringResource(Res.string.game_action_sub_before_later, delayLabel(a.delaySeconds))
    }
    GameActionTrigger.AFTER_END ->
        if (a.delaySeconds <= 0) stringResource(Res.string.game_action_after)
        else stringResource(Res.string.game_action_sub_after_delay, delayLabel(a.delaySeconds))
}

/** A list of game actions with "add"; each opens its dialog. Start actions first, then end actions. */
@Composable
fun GameActionsList(actions: List<GameAction>, onChange: (List<GameAction>) -> Unit, enabled: Boolean = true) {
    val entities by io.github.thecodingdad.gamelodge.LocalContainer.current.ha.client.entities.collectAsState()
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    actions.withIndex().sortedBy { it.value.trigger.ordinal }.forEach { (index, a) ->
        NavRow(
            actionLabel(a.action, entities) ?: a.action, { if (enabled) editing = index }, actionSummary(a),
            if (a.trigger == GameActionTrigger.BEFORE_START) Icons.Filled.PlayArrow else Icons.Filled.Stop,
        )
    }
    LauncherButton(stringResource(Res.string.game_action_add), onClick = { adding = true }, icon = Icons.Filled.Add, enabled = enabled)
    editing?.let { index ->
        actions.getOrNull(index)?.let { current ->
            GameActionDialog(
                current,
                onSave = { changed -> onChange(actions.toMutableList().also { it[index] = changed }); editing = null },
                onRemove = { onChange(actions.toMutableList().also { it.removeAt(index) }); editing = null },
                onDismiss = { editing = null },
            )
        }
    }
    if (adding) GameActionDialog(null, onSave = { onChange(actions + it); adding = false }, onRemove = null, onDismiss = { adding = false })
}

/** One action: what (Home Assistant), when, with which delay and order; saved with Save. */
@Composable
private fun GameActionDialog(initial: GameAction?, onSave: (GameAction) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val ha = io.github.thecodingdad.gamelodge.LocalContainer.current.ha
    val entities by ha.client.entities.collectAsState()
    var action by remember { mutableStateOf(initial?.action) }
    var trigger by remember { mutableStateOf(initial?.trigger ?: GameActionTrigger.BEFORE_START) }
    var delay by remember { mutableStateOf(initial?.delaySeconds ?: 0) }
    var startWaits by remember { mutableStateOf(initial?.startWaits ?: true) }
    var picking by remember { mutableStateOf(false) }
    Modal(visible = true, onDismiss = onDismiss, title = stringResource(Res.string.game_action_title), maxWidth = 680.dp, closeButton = false) {
        // Scrolls on small screens; the buttons below stay.
        androidx.compose.foundation.layout.Column(Modifier.popupHeightIn(420.dp).controllerScroll(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        NavRow(
            stringResource(Res.string.game_action_entity), { ha.client.start(); picking = true },
            actionLabel(action, entities) ?: stringResource(Res.string.not_set), initialFocus = true,
        )
        ChoiceRow(
            stringResource(Res.string.game_action_when), GameActionTrigger.entries, trigger,
            label = { stringResource(if (it == GameActionTrigger.BEFORE_START) Res.string.game_action_before else Res.string.game_action_after) },
            onSelect = { trigger = it; delay = 0 },
        )
        val delays = if (trigger == GameActionTrigger.BEFORE_START) BEFORE_DELAYS else AFTER_DELAYS
        ChoiceRow(
            stringResource(Res.string.game_action_delay), delays, delay.takeIf { it in delays } ?: 0,
            label = { delayLabel(it) }, onSelect = { delay = it },
            subtitle = if (trigger == GameActionTrigger.AFTER_END && delay > 0) stringResource(Res.string.game_action_after_sub) else null,
        )
        if (trigger == GameActionTrigger.BEFORE_START && delay > 0) {
            ChoiceRow(
                stringResource(Res.string.game_action_mode), listOf(true, false), startWaits,
                label = { stringResource(if (it) Res.string.game_action_start_waits else Res.string.game_action_runs_later) },
                onSelect = { startWaits = it },
                subtitle = stringResource(if (startWaits) Res.string.game_action_mode_sub_waits else Res.string.game_action_mode_sub_later),
            )
        }
        }
        ButtonRow {
            LauncherButton(stringResource(Res.string.action_save), primary = true, enabled = action != null, onClick = {
                action?.let { onSave(GameAction(it, trigger, delay, startWaits)) }
            })
            LauncherButton(stringResource(Res.string.action_cancel), onClick = onDismiss)
        }
        if (onRemove != null) {
            HoldToConfirmButton(stringResource(Res.string.game_action_remove), onConfirmed = onRemove, icon = Icons.Filled.Delete, modifier = Modifier.fillMaxWidth())
        }
    }
    EntityPicker(
        visible = picking, title = stringResource(Res.string.game_action_entity),
        entities = entities.values, domains = actionDomains,
        onPick = { if (it != null) action = it }, onDismiss = { picking = false },
        switchDefault = true,
    )
}

/** Tile menu → Actions: the game's own actions instead of the global ones. */
@Composable
fun GameActionsDialog(entry: LibraryEntry?, onDismiss: () -> Unit) {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
    val all = GameActionSettings.decode(settings.gameActions)
    fun save(change: (GameActionSettings) -> GameActionSettings) {
        scope.launch { c.settings.update { s -> s.copy(gameActions = GameActionSettings.encode(change(GameActionSettings.decode(s.gameActions)))) } }
    }
    Modal(visible = entry != null, onDismiss = onDismiss, title = entry?.item?.title, maxWidth = 720.dp) {
        val e = entry ?: return@Modal
        val own = all.games[e.item.id]
        Text(stringResource(Res.string.ha_game_actions), style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.accentText)
        SwitchRow(
            stringResource(Res.string.game_actions_own), own != null,
            { on -> save { s -> s.copy(games = if (on) s.games + (e.item.id to s.global) else s.games - e.item.id) } },
            subtitle = stringResource(Res.string.game_actions_own_sub), initialFocus = true,
        )
        if (own == null) {
            Text(stringResource(Res.string.game_actions_global_info, all.global.size), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        } else {
            if (own.isEmpty()) Text(stringResource(Res.string.game_actions_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
            GameActionsList(own, { list -> save { s -> s.copy(games = s.games + (e.item.id to list)) } })
        }
    }
}
