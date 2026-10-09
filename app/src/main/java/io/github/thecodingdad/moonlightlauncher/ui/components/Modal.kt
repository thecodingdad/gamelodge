package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.ui.input.PadBlocker
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/**
 * In-window modal: scrim, focus trap, gamepad shortcut blocking and B/back to close.
 * Deliberately not a platform Dialog, which would get its own window and bypass the
 * activity-level gamepad handling.
 */
@Composable
fun Modal(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    maxWidth: Dp = 620.dp,
    /** Element that gets focus when the modal opens; defaults to the first focusable child. */
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        ModalScaffold(onDismiss, alignment = Alignment.Center) {
            Column(
                modifier = modifier
                    .widthIn(max = maxWidth)
                    .padding(24.dp)
                    .background(LauncherTheme.colors.surface, RoundedCornerShape(24.dp))
                    .padding(28.dp)
                    .trapFocus(initialFocus),
            ) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 18.dp))
                }
                content()
            }
        }
    }
}

/** Panel sliding in from the right edge (Home Assistant panel, quick settings). */
@Composable
fun SidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 460.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        ModalScaffold(onDismiss, alignment = Alignment.CenterEnd) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = width)
                        .background(LauncherTheme.colors.surface)
                        .padding(24.dp)
                        .trapFocus(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun ModalScaffold(onDismiss: () -> Unit, alignment: Alignment, content: @Composable BoxScope.() -> Unit) {
    PadBlocker()
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LauncherTheme.colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = alignment,
    ) {
        // Inner clicks must not dismiss.
        Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            content()
        }
    }
}

/**
 * Keeps D-pad focus inside the modifier's subtree and moves it there initially.
 * Exiting is cancelled, so the screen behind a modal can never be focused.
 */
@Composable
fun Modifier.trapFocus(initialFocus: FocusRequester? = null): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Let children lay out before requesting focus into the group.
        delay(30)
        runCatching { (initialFocus ?: requester).requestFocus() }
    }
    return this
        .focusRequester(requester)
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusRestorer()
        .focusGroup()
}
