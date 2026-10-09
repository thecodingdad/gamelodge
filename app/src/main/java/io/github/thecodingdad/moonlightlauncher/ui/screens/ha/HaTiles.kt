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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileEntity
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileType
import io.github.thecodingdad.moonlightlauncher.ha.HaEntity
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

fun HaTileType.icon(): ImageVector = when (this) {
    HaTileType.LIGHT -> Icons.Filled.Lightbulb
    HaTileType.SWITCH -> Icons.Filled.ToggleOn
    HaTileType.BUTTON -> Icons.Filled.PlayCircle
    HaTileType.SENSOR -> Icons.Filled.Sensors
    HaTileType.COVER -> Icons.Filled.Blinds
    HaTileType.CLIMATE -> Icons.Filled.Thermostat
}

/**
 * One smart-home tile. A = primary action; D-pad left/right adjusts the value
 * (brightness, cover position, target temperature) in steps.
 */
@Composable
fun HaTile(tile: HaTileEntity, entity: HaEntity?, modifier: Modifier = Modifier, width: Dp = 200.dp) {
    val ha = LocalContext.current.container.ha
    val scope = rememberCoroutineScope()
    val feedback = LocalUiFeedback.current
    val colors = LauncherTheme.colors
    val active = entity?.isOn == true

    fun adjust(direction: Int) {
        entity ?: return
        feedback?.play(UiSound.TOGGLE)
        scope.launch {
            when (tile.type) {
                HaTileType.LIGHT -> ha.setBrightness(entity, ((entity.brightnessPercent ?: 0) + direction * 10).coerceIn(0, 100))
                HaTileType.COVER -> ha.setCoverPosition(entity, ((entity.coverPosition ?: 0) + direction * 10).coerceIn(0, 100))
                HaTileType.CLIMATE -> entity.targetTemperature?.let { ha.setTemperature(entity, it + direction * 0.5) }
                else -> Unit
            }
        }
    }
    val adjustable = tile.type in setOf(HaTileType.LIGHT, HaTileType.COVER, HaTileType.CLIMATE)

    FocusSurface(
        onClick = { if (entity != null && tile.type != HaTileType.SENSOR) scope.launch { ha.primaryAction(entity) } },
        modifier = modifier
            .width(width)
            .height(112.dp)
            .then(
                if (adjustable) Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { adjust(-1); true }
                        Key.DirectionRight -> { adjust(1); true }
                        else -> false
                    }
                } else Modifier,
            ),
        color = if (active) colors.accent.copy(alpha = 0.28f) else colors.surfaceRaised,
        focusScale = 1.05f,
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tile.type.icon(), null, tint = if (active) colors.accent else colors.onSurfaceMuted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    tile.label ?: entity?.name ?: tile.entityId,
                    style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(stateText(tile, entity), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted, maxLines = 1)
            val level = when (tile.type) {
                HaTileType.LIGHT -> entity?.brightnessPercent?.takeIf { active }
                HaTileType.COVER -> entity?.coverPosition
                else -> null
            }
            if (level != null) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surface)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).background(colors.accent))
                }
            }
        }
    }
}

@Composable
private fun stateText(tile: HaTileEntity, entity: HaEntity?): String {
    if (entity == null || entity.isUnavailable) return stringResource(R.string.ha_unavailable)
    return when (tile.type) {
        HaTileType.LIGHT -> entity.brightnessPercent?.takeIf { entity.isOn }?.let { "$it %" } ?: stringResource(if (entity.isOn) R.string.ha_on else R.string.ha_off)
        HaTileType.SWITCH -> when (entity.state) {
            "on" -> stringResource(R.string.ha_on)
            "off" -> stringResource(R.string.ha_off)
            "locked" -> stringResource(R.string.ha_locked)
            "unlocked" -> stringResource(R.string.ha_unlocked)
            else -> entity.state
        }
        HaTileType.BUTTON -> stringResource(R.string.ha_run)
        HaTileType.COVER -> entity.coverPosition?.let { "$it %" } ?: entity.state
        HaTileType.CLIMATE -> listOfNotNull(entity.currentTemperature?.let { "$it°" }, entity.targetTemperature?.let { "→ $it°" }).joinToString(" ").ifEmpty { entity.state }
        HaTileType.SENSOR -> listOfNotNull(entity.state, entity.unit).joinToString(" ")
    }
}
