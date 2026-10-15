package io.github.thecodingdad.moonlightlauncher.ui.screens.ha

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileEntity
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileType
import io.github.thecodingdad.moonlightlauncher.ha.HaEntity
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.resources.*

fun HaTileType.icon(): ImageVector = when (this) {
    HaTileType.LIGHT -> Icons.Filled.Lightbulb
    HaTileType.SWITCH -> Icons.Filled.ToggleOn
    HaTileType.BUTTON -> Icons.Filled.PlayCircle
    HaTileType.SENSOR -> Icons.Filled.Sensors
    HaTileType.COVER -> Icons.Filled.Blinds
    HaTileType.CLIMATE -> Icons.Filled.Thermostat
}

/**
 * One smart-home tile. A = primary action. The value (brightness, cover position, target
 * temperature) is set with the right stick while focused, or by sliding a finger across the
 * tile. D-pad and left stick only move the focus.
 */
@Composable
fun HaTile(tile: HaTileEntity, entity: HaEntity?, modifier: Modifier = Modifier, width: Dp = 200.dp) {
    val ha = io.github.thecodingdad.moonlightlauncher.LocalContainer.current.ha
    val scope = rememberCoroutineScope()
    val feedback = LocalUiFeedback.current
    val colors = LauncherTheme.colors
    val active = entity?.isOn == true

    val adjustable = tile.type in setOf(HaTileType.LIGHT, HaTileType.COVER, HaTileType.CLIMATE)
    // Optimistic value while sliding / stepping, until Home Assistant reports the new state.
    var pending by remember(tile.entityId) { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    // Gesture/stick handlers outlive recompositions: always use the latest entity.
    val latestEntity by androidx.compose.runtime.rememberUpdatedState(entity)
    val current = pending ?: when (tile.type) {
        HaTileType.LIGHT -> entity?.brightnessPercent?.takeIf { active } ?: 0
        HaTileType.COVER -> entity?.coverPosition ?: 0
        else -> 0
    }
    var sendJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun send(percent: Int, immediate: Boolean) {
        val e = latestEntity ?: return
        pending = percent
        sendJob?.cancel()
        sendJob = scope.launch {
            // Coalesce rapid changes (stick repeat, finger slide) into few service calls.
            if (!immediate) kotlinx.coroutines.delay(250)
            when (tile.type) {
                HaTileType.LIGHT -> ha.setBrightness(e, percent)
                HaTileType.COVER -> ha.setCoverPosition(e, percent)
                else -> Unit
            }
        }
    }
    // Drop the optimistic value once Home Assistant reports a state and nothing is pending.
    androidx.compose.runtime.LaunchedEffect(entity) {
        if (!dragging && sendJob?.isActive != true) pending = null
    }
    fun step(direction: Int) {
        val e = entity ?: return
        feedback?.play(UiSound.FOCUS)
        if (tile.type == HaTileType.CLIMATE) {
            e.targetTemperature?.let { t -> scope.launch { ha.setTemperature(e, t + direction * 0.5) } }
        } else {
            send((current + direction * 5).coerceIn(0, 100), immediate = false)
        }
    }
    var focused by remember { mutableStateOf(false) }
    PadAction(PadButton.RS_RIGHT, enabled = focused && adjustable) { step(1) }
    PadAction(PadButton.RS_LEFT, enabled = focused && adjustable) { step(-1) }

    FocusSurface(
        onClick = { if (entity != null && tile.type != HaTileType.SENSOR) scope.launch { ha.primaryAction(entity) } },
        modifier = modifier
            .width(width)
            .height(76.dp)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .then(
                // Touch: slide horizontally across the tile to set the value.
                if (adjustable && tile.type != HaTileType.CLIMATE) Modifier.pointerInput(tile.entityId) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; pending?.let { send(it, immediate = true) } },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val percent = (change.position.x / size.width * 100).toInt().coerceIn(0, 100)
                        send(percent, immediate = false)
                    }
                } else Modifier,
            ),
        // Opaque mix: a translucent tile would let the focus shadow show through as a box.
        color = if (active) colors.accent.copy(alpha = 0.28f).compositeOver(colors.surfaceRaised) else colors.surfaceRaised,
        focusScale = 1.05f,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            // Compact: icon next to a one-line name and the state below it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tile.type.icon(), null, tint = if (active) colors.accent else colors.onSurfaceMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        tile.label ?: entity?.name ?: tile.entityId,
                        style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pending?.let { "$it %" } ?: stateText(tile, entity),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 1,
                    )
                }
            }
            val level = when (tile.type) {
                HaTileType.LIGHT, HaTileType.COVER -> current
                else -> null
            }
            if (level != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.surface)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).background(colors.accent))
                }
            }
        }
    }
}

@Composable
private fun stateText(tile: HaTileEntity, entity: HaEntity?): String {
    if (entity == null || entity.isUnavailable) return stringResource(Res.string.ha_unavailable)
    return when (tile.type) {
        HaTileType.LIGHT -> entity.brightnessPercent?.takeIf { entity.isOn }?.let { "$it %" } ?: stringResource(if (entity.isOn) Res.string.ha_on else Res.string.ha_off)
        HaTileType.SWITCH -> when (entity.state) {
            "on" -> stringResource(Res.string.ha_on)
            "off" -> stringResource(Res.string.ha_off)
            "locked" -> stringResource(Res.string.ha_locked)
            "unlocked" -> stringResource(Res.string.ha_unlocked)
            else -> entity.state
        }
        HaTileType.BUTTON -> stringResource(Res.string.ha_run)
        HaTileType.COVER -> entity.coverPosition?.let { "$it %" } ?: entity.state
        HaTileType.CLIMATE -> listOfNotNull(entity.currentTemperature?.let { "$it°" }, entity.targetTemperature?.let { "→ $it°" }).joinToString(" ").ifEmpty { entity.state }
        HaTileType.SENSOR -> listOfNotNull(entity.state, entity.unit).joinToString(" ")
    }
}
