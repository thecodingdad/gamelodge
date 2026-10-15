package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
) = Overlay(visible, onDismiss) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        ModalScaffold(onDismiss, alignment = Alignment.Center, scrim = PopupScrim) {
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
) = Overlay(visible, onDismiss) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        ModalScaffold(onDismiss, alignment = Alignment.CenterEnd, scrim = PopupScrim) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        // Fixed width: the panel must not change size with its content (tabs).
                        .width(width)
                        .background(LauncherTheme.colors.surface)
                        // Out of a camera notch / hole on top (portrait) or the side.
                        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout)
                        .padding(24.dp)
                        .trapFocus(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun ModalScaffold(
    onDismiss: () -> Unit,
    alignment: Alignment,
    scrim: androidx.compose.ui.graphics.Brush,
    content: @Composable BoxScope.() -> Unit,
) {
    PadBlocker()
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
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
    // Only the topmost overlay keeps the focus; a popup opened above takes it over, and when
    // that popup closes, focus returns here (focusRestorer remembers the last child).
    val entry = LocalOverlayEntry.current
    val isTop = entry == null || OverlayHost.isTop(entry)
    var first by remember { mutableStateOf(true) }
    // Re-run when the user picks up the controller: a popup opened by touch must own the focus.
    val controllerMode = isControllerMode()
    LaunchedEffect(isTop, controllerMode) {
        if (!isTop) return@LaunchedEffect
        // Touch: no automatic focus (it would open the system keyboard on text fields); the
        // effect runs again as soon as the controller is used.
        if (!controllerMode) return@LaunchedEffect
        // Let children lay out (and a touched element inside this popup restore) first.
        delay(30)
        if (!first && TouchFocus.justRestored()) return@LaunchedEffect
        runCatching { if (first) (initialFocus ?: requester).requestFocus() else requester.requestFocus() }
        first = false
    }
    return this
        .focusRequester(requester)
        .focusProperties { onExit = { if (entry == null || OverlayHost.isTop(entry)) cancelFocusChange() } }
        .focusRestorer()
        .focusGroup()
}

/** Same dimming for every popup and panel; the launcher behind is blurred as well (LauncherApp). */
private val PopupScrim: androidx.compose.ui.graphics.Brush =
    androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))

/**
 * All popups and panels are drawn in one layer above the launcher ([OverlayOutlet]), wherever
 * they are declared. That gives them one consistent backdrop (dimmed + blurred) and a clear
 * stacking order: the most recently opened one is on top and owns the focus.
 */
object OverlayHost {
    class Entry {
        internal var content by mutableStateOf<@Composable () -> Unit>({})
        internal var visible by mutableStateOf(false)
        internal var onDismiss: () -> Unit = {}
    }

    internal val entries = mutableStateListOf<Entry>()

    /** True while any popup/panel is shown (drives the backdrop blur). */
    val anyVisible: Boolean get() = entries.any { it.visible }

    fun isTop(entry: Entry): Boolean = entries.lastOrNull { it.visible } === entry

    fun top(): Entry? = entries.lastOrNull { it.visible }

    /** Closes every open popup and panel, topmost first (the Home button). */
    fun dismissAll() {
        entries.filter { it.visible }.asReversed().forEach { it.onDismiss() }
    }
}

val LocalOverlayEntry = androidx.compose.runtime.compositionLocalOf<OverlayHost.Entry?> { null }

/** Place once, above the main content. */
@Composable
fun OverlayOutlet() {
    OverlayHost.entries.forEach { entry ->
        androidx.compose.runtime.key(entry) {
            androidx.compose.runtime.CompositionLocalProvider(LocalOverlayEntry provides entry) { entry.content() }
        }
    }
}

@Composable
private fun Overlay(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val entry = remember { OverlayHost.Entry() }
    androidx.compose.runtime.SideEffect {
        entry.content = content
        entry.onDismiss = onDismiss
    }
    androidx.compose.runtime.DisposableEffect(entry) {
        OverlayHost.entries.add(entry)
        onDispose { OverlayHost.entries.remove(entry) }
    }
    LaunchedEffect(visible) {
        // A newly opened overlay moves to the top of the stack.
        if (visible && OverlayHost.entries.lastOrNull() !== entry && entry in OverlayHost.entries) {
            OverlayHost.entries.remove(entry)
            OverlayHost.entries.add(entry)
        }
        entry.visible = visible
    }
}
