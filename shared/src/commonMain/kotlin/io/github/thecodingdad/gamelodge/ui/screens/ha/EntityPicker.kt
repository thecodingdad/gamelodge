package io.github.thecodingdad.gamelodge.ui.screens.ha

import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.fadingEdges
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Window
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.library.TitleNormalizer
import io.github.thecodingdad.gamelodge.ha.HaAction
import io.github.thecodingdad.gamelodge.ha.HaEntity
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherTextField
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.mouseDragScroll
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Kinds of entities the picker filters by. */
enum class EntityGroup(val domains: Set<String>, val label: StringResource, val icon: ImageVector) {
    SWITCH(setOf("switch", "input_boolean"), Res.string.ha_group_switch, Icons.Filled.ToggleOn),
    SCRIPT(setOf("script"), Res.string.ha_group_script, Icons.Filled.Description),
    BUTTON(setOf("button", "input_button"), Res.string.ha_group_button, Icons.Filled.RadioButtonChecked),
    SCENE(setOf("scene"), Res.string.ha_group_scene, Icons.Filled.Palette),
    LIGHT(setOf("light"), Res.string.ha_group_light, Icons.Filled.Lightbulb),
    FAN(setOf("fan"), Res.string.ha_group_fan, Icons.Filled.Air),
    COVER(setOf("cover"), Res.string.ha_group_cover, Icons.Filled.Window),
    CLIMATE(setOf("climate"), Res.string.ha_group_climate, Icons.Filled.Thermostat),
    SENSOR(setOf("sensor", "binary_sensor"), Res.string.ha_group_sensor, Icons.Filled.Sensors),
    LOCK(setOf("lock"), Res.string.ha_group_lock, Icons.Filled.Lock),
    MEDIA(setOf("media_player"), Res.string.ha_group_media, Icons.Filled.Tv),
    AUTOMATION(setOf("automation"), Res.string.ha_group_automation, Icons.Filled.AutoMode);

    companion object {
        fun of(domain: String) = entries.firstOrNull { domain in it.domains }
    }
}

/** Domains that can serve as a host power action (switches with on or off). */
val actionDomains = setOf("script", "button", "input_button", "switch", "input_boolean", "scene")

/** Domains for game actions: the host power ones plus lights (on with brightness, or off). */
val gameActionDomains = actionDomains + "light"

/**
 * Picks a Home Assistant entity: search (focused when it opens), kind chips, list. With
 * [switchDefault] set, a switchable entity asks next whether to switch it on or off (preset to
 * [switchDefault]) and the result is an [HaAction] value; [allowNone] offers clearing the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntityPicker(
    visible: Boolean,
    title: String,
    entities: Collection<HaEntity>,
    domains: Set<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    allowNone: Boolean = false,
    switchDefault: Boolean? = null,
) {
    var query by remember(visible) { mutableStateOf("") }
    var group by remember(visible) { mutableStateOf<EntityGroup?>(null) }
    var pendingSwitch by remember(visible) { mutableStateOf<HaEntity?>(null) }
    var switchOn by remember(visible) { mutableStateOf(switchDefault ?: true) }
    var brightness by remember(visible) { mutableStateOf<Int?>(null) }
    val search = remember { FocusRequester() }
    val available = entities.filter { it.domain in domains }
    Modal(visible = visible, onDismiss = onDismiss, title = title, maxWidth = 760.dp, initialFocus = search) {
        val pending = pendingSwitch
        if (pending != null) {
            // Second step for switches: on or off.
            Text(pending.name, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
            Text(pending.id, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            ChoiceRow(
                stringResource(Res.string.ha_switch_action), listOf(true, false), switchOn,
                label = { stringResource(if (it) Res.string.ha_turn_on else Res.string.ha_turn_off) },
                onSelect = { switchOn = it }, initialFocus = true,
            )
            if (pending.domain == "light" && switchOn) {
                ChoiceRow(
                    stringResource(Res.string.ha_light_brightness), HaAction.brightnessSteps, brightness,
                    label = { if (it == null) stringResource(Res.string.ha_light_brightness_keep) else "$it %" },
                    onSelect = { brightness = it },
                )
            }
            ButtonRow {
                LauncherButton(stringResource(Res.string.ha_pick_apply), primary = true, onClick = { onPick(HaAction.encode(pending.id, switchOn, brightness.takeIf { pending.domain == "light" })); onDismiss() })
                LauncherButton(stringResource(Res.string.action_cancel), onClick = { pendingSwitch = null })
            }
            return@Modal
        }
        LauncherTextField(stringResource(Res.string.hint_search), query, { query = it }, modifier = Modifier.focusRequester(search))
        val groups = EntityGroup.entries.filter { g -> available.any { it.domain in g.domains } }
        if (groups.size > 1) {
            FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(Res.string.ha_pick_all), null, group == null) { group = null }
                groups.forEach { g -> Chip(stringResource(g.label), g.icon, group == g) { group = if (group == g) null else g } }
            }
        }
        if (available.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = LauncherTheme.colors.accent)
                Text("  " + stringResource(Res.string.ha_connecting), color = LauncherTheme.colors.onSurfaceMuted)
            }
            return@Modal
        }
        val q = TitleNormalizer.normalize(query)
        val shown = available
            .filter { group == null || it.domain in group!!.domains }
            .filter { q.isEmpty() || TitleNormalizer.normalize(it.name).contains(q) || it.id.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy({ EntityGroup.of(it.domain)?.ordinal ?: 99 }, { it.name.lowercase() }))
        fun choose(e: HaEntity) {
            if (switchDefault != null && e.domain in HaAction.switchable) pendingSwitch = e
            else { onPick(e.id); onDismiss() }
        }
        val list = io.github.thecodingdad.gamelodge.ui.input.rememberStickLazyListState()
        LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).popupHeightIn(400.dp).fadingEdges(list).mouseDragScroll(list), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowNone && q.isEmpty() && group == null) {
                item(key = "none") { EntityRow(Icons.Filled.Block, stringResource(Res.string.not_set), null) { onPick(null); onDismiss() } }
            }
            items(shown, key = { it.id }) { e ->
                EntityRow(EntityGroup.of(e.domain)?.icon ?: Icons.Filled.Movie, e.name, "${e.id}  ·  ${e.state}") { choose(e) }
            }
            if (shown.isEmpty()) {
                item(key = "empty") { Text(stringResource(Res.string.ha_pick_no_match), color = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick, shape = RoundedCornerShape(18.dp), focusScale = 1.05f,
        color = if (selected) colors.accent else colors.surfaceRaised,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val content = if (selected) colors.onAccent else colors.onSurface
            if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

@Composable
private fun EntityRow(icon: ImageVector, name: String, detail: String?, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.surfaceRaised, focusScale = 1.02f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = LauncherTheme.colors.accentText, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = LauncherTheme.colors.onSurface)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            }
        }
    }
}

/** How a stored action reads: entity name, for switches with on or off. */
@Composable
fun actionLabel(action: String?, entities: Map<String, HaEntity>): String? {
    action ?: return null
    val id = HaAction.entity(action)
    val name = entities[id]?.name ?: id
    val brightness = HaAction.brightness(action)
    return when (HaAction.on(action)) {
        true -> "$name · ${stringResource(Res.string.ha_turn_on)}" + (brightness?.let { " · $it %" } ?: "")
        false -> "$name · ${stringResource(Res.string.ha_turn_off)}"
        null -> name
    }
}
