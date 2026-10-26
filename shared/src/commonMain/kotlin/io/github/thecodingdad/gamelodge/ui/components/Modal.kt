package io.github.thecodingdad.gamelodge.ui.components

import io.github.thecodingdad.gamelodge.ui.components.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.focus.onFocusChanged
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
import io.github.thecodingdad.gamelodge.ui.input.PadBlocker
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import io.github.thecodingdad.gamelodge.resources.*

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
    /** Back arrow left of the title (touch / mouse); off for dialogs with their own cancel / done button. */
    closeButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = Overlay(visible, onDismiss) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        ModalScaffold(onDismiss, alignment = Alignment.Center, scrim = PopupScrim) {
            // Entries spaced and settings rows raised, so they stand apart on the popup's surface;
            // scroll areas may grow on tall screens.
            val heightScale = popupHeightScale()
            Column(
                modifier = modifier
                    .widthIn(max = maxWidth)
                    .padding(24.dp)
                    .background(LauncherTheme.colors.surface, RoundedCornerShape(24.dp))
                    .padding(28.dp)
                    .trapFocus(initialFocus),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                if (title != null) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        if (closeButton) CloseArrow(onDismiss)
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface)
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalRowSurface provides LauncherTheme.colors.surfaceRaised,
                    LocalPopupHeightScale provides heightScale,
                ) { content() }
            }
        }
    }
}

/**
 * [content] on its own over the dimmed, blurred screen, without a popup frame (e.g. a tile
 * preview); B / back closes it.
 */
@Composable
fun Spotlight(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = Overlay(visible, onDismiss) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        ModalScaffold(onDismiss, alignment = Alignment.Center, scrim = PopupScrim) {
            Column(Modifier.padding(24.dp).trapFocus(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

/** Round back arrow that closes a popup or panel; touch and mouse only (controllers close with B). */
@Composable
fun CloseArrow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = LauncherTheme.colors.surfaceRaised,
        dpadFocusable = false,
        clickSound = io.github.thecodingdad.gamelodge.ui.feedback.UiSound.BACK,
        modifier = modifier.padding(end = 14.dp).size(40.dp),
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
            org.jetbrains.compose.resources.stringResource(Res.string.hint_back),
            tint = LauncherTheme.colors.onSurface, modifier = Modifier.align(Alignment.Center).size(22.dp),
        )
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
                        // The panel draws into a camera notch / hole; its content stays clear of it.
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
            // Taps beside the popup do nothing (and don't reach the screen behind): it closes with
            // its back arrow / cancel button, B or the system back.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = alignment,
    ) {
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
    // The focused element went away or was disabled (e.g. a button that greys out once used):
    // the focus comes back here, so the D-pad never reaches the page behind the popup.
    var hasFocus by remember { mutableStateOf(false) }
    var lost by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val controllerNow by androidx.compose.runtime.rememberUpdatedState(controllerMode)
    LaunchedEffect(lost) {
        if (lost == 0) return@LaunchedEffect
        delay(30)
        if (!hasFocus && controllerNow && (entry == null || OverlayHost.isTop(entry))) runCatching { requester.requestFocus() }
    }
    return this
        .onFocusChanged {
            if (hasFocus && !it.hasFocus) lost++
            hasFocus = it.hasFocus
        }
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
    class Entry(internal val layer: Any?) {
        internal var content by mutableStateOf<@Composable () -> Unit>({})
        internal var visible by mutableStateOf(false)
        internal var onDismiss: () -> Unit = {}
    }

    internal val entries = mutableStateListOf<Entry>()

    /** True while any popup/panel of the launcher's own window is shown (drives the backdrop blur). */
    val anyVisible: Boolean get() = entries.any { it.visible && it.layer == null }

    /** The topmost of its window's popups (another window's don't cover it). */
    fun isTop(entry: Entry): Boolean = entries.lastOrNull { it.visible && it.layer == entry.layer } === entry

    fun top(): Entry? = entries.lastOrNull { it.visible }

    /** Closes every open popup and panel, topmost first (the Home button). */
    fun dismissAll() {
        entries.filter { it.visible }.asReversed().forEach { it.onDismiss() }
    }
}

val LocalOverlayEntry = androidx.compose.runtime.compositionLocalOf<OverlayHost.Entry?> { null }

/**
 * The window popups belong to: null for the launcher's own, a key of its own for another window
 * (the quick menu over a game on the PC), whose [OverlayOutlet] draws only its popups.
 */
val LocalOverlayLayer = androidx.compose.runtime.staticCompositionLocalOf<Any?> { null }

/** Place once per window, above the main content. */
@Composable
fun OverlayOutlet() {
    val layer = LocalOverlayLayer.current
    OverlayHost.entries.filter { it.layer == layer }.forEach { entry ->
        androidx.compose.runtime.key(entry) {
            androidx.compose.runtime.CompositionLocalProvider(LocalOverlayEntry provides entry) { entry.content() }
        }
    }
}

@Composable
private fun Overlay(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val layer = LocalOverlayLayer.current
    val entry = remember(layer) { OverlayHost.Entry(layer) }
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
