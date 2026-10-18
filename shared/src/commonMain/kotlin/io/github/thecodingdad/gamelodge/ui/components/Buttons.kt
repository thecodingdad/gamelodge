package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiSound
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pill button used for actions (Play, Settings entries, dialog buttons). */
@Composable
fun LauncherButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onFocused: (() -> Unit)? = null,
) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp),
        color = if (primary) colors.accent else colors.surfaceRaised,
        focusScale = 1.05f,
        enabled = enabled,
        onFocused = onFocused,
    ) {
        val content = if (primary) colors.onAccent else colors.onSurface
        Row(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Destructive actions (shut down host, quit game) must be held: A on a gamepad or a long
 * touch. A ring-like bar fills up; releasing early cancels.
 */
@Composable
fun HoldToConfirmButton(
    text: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    holdMillis: Int = 1200,
    /** Settings row look: full width, left-aligned title with [subtitle] (null: pill button). */
    subtitle: String? = null,
    row: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LauncherTheme.colors
    val feedback = LocalUiFeedback.current
    val shape = if (row) RoundedCornerShape(LauncherTheme.dimens.cornerRadius) else RoundedCornerShape(26.dp)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val jobHolder = remember { arrayOfNulls<Job>(1) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val controllerMode = isControllerMode()

    fun start() {
        if (!enabled || jobHolder[0]?.isActive == true) return
        jobHolder[0] = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
            feedback?.play(UiSound.CONFIRM)
            onConfirmed()
            progress.snapTo(0f)
        }
    }

    fun cancel() {
        jobHolder[0]?.cancel()
        scope.launch { progress.animateTo(0f, tween(150)) }
    }

    Box(
        modifier = modifier
            .then(if (row) Modifier.fillMaxWidth().heightIn(min = 68.dp) else Modifier.heightIn(min = 52.dp))
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
            .clip(shape)
            .background(colors.danger.copy(alpha = 0.18f))
            .then(if (focused && controllerMode) Modifier.border(3.dp, colors.focusRing, shape) else Modifier)
            .onPreviewKeyEvent { e ->
                val confirmKey = e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter
                if (!confirmKey) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) start() else if (e.type == KeyEventType.KeyUp) cancel()
                true
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    start()
                    tryAwaitRelease()
                    cancel()
                })
            }
            .focusable(interactionSource = interaction),
    ) {
        // The fill follows the button's own size (fillMaxHeight would stretch it in scrolling parents).
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(colors.danger.copy(alpha = 0.55f)),
            )
        }
        if (row) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (icon != null) Icon(icon, null, tint = colors.danger, modifier = Modifier.size(26.dp))
                Column {
                    Text(text, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                }
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (icon != null) Icon(icon, null, tint = colors.onSurface, modifier = Modifier.size(22.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
            }
        }
    }
}

/** Small round status dot (host online/offline, entity on/off). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/**
 * Row of action buttons that wraps onto the next line when the screen is too narrow
 * (portrait), instead of squeezing the last buttons.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
