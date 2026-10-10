package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import io.github.thecodingdad.moonlightlauncher.ui.theme.LocalReduceMotion

/**
 * Element the user touched last. Touch doesn't move focus in Compose, so when the user picks
 * up the controller again, focus continues from here instead of the element focused earlier.
 */
object TouchFocus {
    private var pending: java.lang.ref.WeakReference<FocusRequester>? = null

    fun touched(requester: FocusRequester) {
        pending = java.lang.ref.WeakReference(requester)
    }

    /** Uptime of the last restore; screen-level focus restoring yields to it briefly. */
    var restoredAt = 0L
        private set

    /** Call when the input mode switches to controller. */
    fun restore() {
        val requester = pending?.get() ?: return
        pending = null
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) restoredAt = android.os.SystemClock.uptimeMillis()
    }

    fun justRestored() = android.os.SystemClock.uptimeMillis() - restoredAt < 500
}

/** True when the user navigates with gamepad/keyboard (focus ring visible). */
@Composable
fun isControllerMode(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

/**
 * The launcher's basic interactive element. Every focusable thing on screen is built on it,
 * so focus feedback (scale, ring, sound) and click/long-press behave identically everywhere.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(LauncherTheme.dimens.cornerRadius),
    color: Color = LauncherTheme.colors.surface,
    focusScale: Float = 1.06f,
    enabled: Boolean = true,
    /** False keeps the element out of D-pad navigation (still touchable), e.g. top tabs. */
    dpadFocusable: Boolean = true,
    clickSound: UiSound = UiSound.CONFIRM,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val controllerMode = isControllerMode()
    val feedback = LocalUiFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val currentOnFocused by rememberUpdatedState(onFocused)
    val requester = remember { FocusRequester() }
    // Touching an element makes it the focus target for the next controller input, and tells
    // the screen (which remembers positions via onFocused) that the user is here now.
    fun touched() {
        if (controllerMode || !dpadFocusable) return
        TouchFocus.touched(requester)
        currentOnFocused?.invoke()
    }

    val scale by animateFloatAsState(
        targetValue = if (focused && controllerMode && !reduceMotion) focusScale else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "focusScale",
    )

    LaunchedEffect(focused) {
        if (focused) {
            if (controllerMode) feedback?.play(UiSound.FOCUS)
            currentOnFocused?.invoke()
        }
    }

    val ring = LauncherTheme.colors.focusRing
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused && controllerMode) Modifier.shadow(18.dp, shape, ambientColor = ring, spotColor = ring) else Modifier)
            .clip(shape)
            .background(color)
            .then(if (dpadFocusable) Modifier.focusRequester(requester) else Modifier.focusProperties { canFocus = false })
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                onLongClick = onLongClick?.let { action ->
                    {
                        feedback?.play(UiSound.TOGGLE)
                        touched()
                        action()
                    }
                },
                onClick = {
                    feedback?.play(clickSound)
                    touched()
                    onClick()
                },
            )
            .then(if (focused && controllerMode) Modifier.border(3.dp, ring, shape) else Modifier),
    ) {
        content(focused)
    }
}
