package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.settings.CardSize
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.components.ControllerKeyboardHost
import io.github.thecodingdad.gamelodge.ui.components.KeyboardController
import io.github.thecodingdad.gamelodge.ui.components.LocalButtonLayout
import io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.LocalOverlayLayer
import io.github.thecodingdad.gamelodge.ui.components.LocalShowHintBar
import io.github.thecodingdad.gamelodge.ui.components.OverlayOutlet
import io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.LocalInputDispatcher
import io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers
import io.github.thecodingdad.gamelodge.ui.screens.settings.layoutFor
import io.github.thecodingdad.gamelodge.ui.shell.HintHolder
import io.github.thecodingdad.gamelodge.ui.shell.LocalHintHolder
import io.github.thecodingdad.gamelodge.ui.shell.QuickMenu
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.ui.theme.fittedDensity
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * The quick menu over a game or stream on the PC: a window of its own at the right edge of the
 * game's monitor, always on top and never activated. The game keeps the focus, so it stays in full
 * screen (Windows draws the window over it); the pads drive the menu, and in a stream Moonlight-Qt
 * holds them back from the host and sets the mouse free meanwhile ([onMenu]).
 */
object QuickMenuOverlay {
    /** The window exists; [open] slides the panel in and out. */
    val shown = mutableStateOf(false)
    val open = mutableStateOf(false)

    /** Where it shows: the monitor's right edge (in Java's coordinates), and the density the menu uses there. */
    internal class Placement(val bounds: java.awt.Rectangle, val density: Float)
    internal var placement: Placement? = null

    /** On the UI thread. */
    fun toggle(uiScale: Float) = if (open.value) close() else show(uiScale)

    fun show(uiScale: Float) {
        placement = placeAtForeground(uiScale) ?: return
        shown.value = true
        open.value = true
    }

    fun close() {
        open.value = false
    }

    /** Right edge of the monitor the window in front is on. */
    private fun placeAtForeground(uiScale: Float): Placement? {
        val screen = ForegroundScreen.find(uiScale) ?: return null
        // Sized like the launcher's own quick menu on that screen.
        val width = screen.userUnits(PANEL_WIDTH_DP).coerceAtMost(screen.bounds.width)
        val b = screen.bounds
        return Placement(java.awt.Rectangle(b.x + b.width - width, b.y, width, b.height), screen.density)
    }

    /** Width of the quick menu's panel (QuickMenu's SidePanel). */
    private const val PANEL_WIDTH_DP = 600f
}

/**
 * The monitor of the window in front (a game or stream): its bounds in Java's units and the density
 * the launcher uses there ([uiScale]: the launcher's UI scale).
 */
internal class ForegroundScreen(val bounds: java.awt.Rectangle, val density: Float, private val scale: Double) {
    /** [dp] in Java's units on that monitor. */
    fun userUnits(dp: Float): Int = ceil(dp * density / scale).toInt()

    companion object {
        fun find(uiScale: Float): ForegroundScreen? = runCatching {
            val user32 = User32.INSTANCE
            val monitor = user32.MonitorFromWindow(user32.GetForegroundWindow(), WinUser.MONITOR_DEFAULTTOPRIMARY)
            val info = WinUser.MONITORINFO()
            user32.GetMonitorInfo(monitor, info)
            val r = info.rcMonitor
            val dpiX = IntByReference(96)
            val dpiY = IntByReference(96)
            // Effective DPI of that monitor (Windows' scale setting).
            NativeLibrary.getInstance("shcore").getFunction("GetDpiForMonitor").invokeInt(arrayOf(monitor, 0, dpiX, dpiY))
            val w = r.right - r.left
            val h = r.bottom - r.top
            val density = fittedDensity(dpiX.value / 96f * uiScale, w, h, CardSize.MEDIUM)
            // Java places windows in its own units (pixels / the monitor's scale): the screen of that monitor.
            val config = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }
                .firstOrNull { c ->
                    val b = c.bounds
                    val scale = c.defaultTransform.scaleX
                    (kotlin.math.abs(b.x - r.left) <= 2 || kotlin.math.abs(b.x * scale - r.left) <= 2) &&
                        (kotlin.math.abs(b.y - r.top) <= 2 || kotlin.math.abs(b.y * scale - r.top) <= 2)
                } ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            ForegroundScreen(config.bounds, density, config.defaultTransform.scaleX)
        }.onFailure { Log.w("Foreground screen not found", it) }.getOrNull()
    }
}

/**
 * The overlay window while [QuickMenuOverlay.shown]. [openSettings] shows a settings page in the
 * launcher's window (the overlay closes); [onMenu] tells a running stream that the menu is open.
 */
@Composable
fun QuickMenuOverlayWindow(
    container: io.github.thecodingdad.gamelodge.LauncherContainer,
    vm: LauncherViewModel,
    feedback: UiFeedback,
    gamepads: Gamepads,
    openSettings: (SettingsTarget) -> Unit,
    onMenu: (Boolean) -> Unit,
) {
    val shown by QuickMenuOverlay.shown
    val place = QuickMenuOverlay.placement
    if (!shown || place == null) return
    val open by QuickMenuOverlay.open
    LaunchedEffect(open) {
        if (!open) {
            delay(250) // let the panel slide out
            QuickMenuOverlay.shown.value = false
        }
    }
    val dispatcher = remember { InputDispatcher() }
    Window(
        create = {
            ComposeWindow().apply {
                title = "GameLodge"
                isUndecorated = true
                isResizable = false
                isAlwaysOnTop = true
                // Never activated (the game keeps the focus and its full screen), no taskbar button.
                focusableWindowState = false
                isAutoRequestFocus = false
                type = java.awt.Window.Type.UTILITY
                // Through Java: it applies its own bounds when it shows the window.
                bounds = place.bounds
                // A native window now, so Windows knows before it shows that it never activates.
                addNotify()
                val hwnd = WinDef.HWND(Native.getComponentPointer(this))
                val user32 = User32.INSTANCE
                user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE) or WS_EX_NOACTIVATE)
            }
        },
        dispose = ComposeWindow::dispose,
    ) {
        DisposableEffect(window) {
            gamepads.overlay = Gamepads.Overlay(window, dispatcher)
            onMenu(true)
            onDispose {
                gamepads.overlay = null
                onMenu(false)
            }
        }
        val settings by vm.settings.collectAsState()
        val keyboard = remember { KeyboardController() }
        val hints = remember { HintHolder() }
        val modeManager = LocalInputModeManager.current
        // Opened with a controller combination: the focus shows right away.
        LaunchedEffect(Unit) { modeManager.requestInputMode(InputMode.Keyboard) }
        val focusManager = LocalFocusManager.current
        LauncherTheme(settings) {
            CompositionLocalProvider(
                LocalContainer provides container,
                LocalOverlayLayer provides QuickMenuOverlay,
                LocalDensity provides Density(place.density, LocalDensity.current.fontScale),
                LocalInputDispatcher provides dispatcher,
                LocalUiFeedback provides feedback,
                LocalKeyboardController provides keyboard,
                LocalHintHolder provides hints,
                LocalButtonLayout provides settings.layoutFor(ActivePad.key, ActivePad.detectedLayout),
                // Laid out like the launcher's own quick menu, not like a narrow screen.
                LocalCompactWidth provides false,
                LocalShowHintBar provides io.github.thecodingdad.gamelodge.input.KeyboardKeys.hintsShown(settings.showHintBar, rememberControllers().size),
                io.github.thecodingdad.gamelodge.ui.components.LocalKeyHints provides io.github.thecodingdad.gamelodge.input.KeyboardKeys.showsKeys(rememberControllers().size),
            ) {
                io.github.thecodingdad.gamelodge.ui.input.RightStickScrollDriver()
                Box(
                    Modifier.fillMaxSize()
                        .background(LauncherTheme.colors.surface)
                        // The pad's D-pad / stick arrive as arrow keys: they move the focus.
                        .onKeyEvent { e -> arrowFocus(e, focusManager, consumeAtEdge = true) }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press && modeManager.inputMode != InputMode.Touch) {
                                        gamepads.otherInput()
                                        modeManager.requestInputMode(InputMode.Touch)
                                    }
                                }
                            }
                        },
                ) {
                    val running by vm.runningSessions.collectAsState()
                    QuickMenu(
                        visible = open,
                        onDismiss = QuickMenuOverlay::close,
                        running = running,
                        onResume = { session ->
                            QuickMenuOverlay.close()
                            val entry = session.entry
                            val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                            if (entry != null && target != null) vm.launch(entry, target)
                        },
                        openHostSettings = { openSettings(SettingsTarget.Host(it.uuid)) },
                        openControllerSettings = { openSettings(SettingsTarget.Controllers) },
                        openHaSettings = { openSettings(SettingsTarget.Page(SettingsPageId.HOME_ASSISTANT)) },
                    )
                    ControllerKeyboardHost(keyboard)
                    OverlayOutlet()
                }
            }
        }
    }
}

internal const val WS_EX_NOACTIVATE = 0x08000000
