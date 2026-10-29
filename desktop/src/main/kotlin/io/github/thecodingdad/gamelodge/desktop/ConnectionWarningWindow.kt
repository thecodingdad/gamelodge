package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.stream.StreamQuality
import io.github.thecodingdad.gamelodge.ui.shell.ConnectionWarningPill
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/**
 * The connection warning over a stream on the PC: a small see-through window at the top of the
 * stream's monitor while there is a warning. Never activated and clicks go through, so the stream
 * keeps focus, full screen and input.
 */
@Composable
fun ConnectionWarningWindow(settings: LauncherSettings) {
    val warning by StreamQuality.warning.collectAsState()
    val shown = warning ?: return
    // Placed once per warning on the monitor the stream is on.
    val screen = remember { ForegroundScreen.find(settings.uiScale / 100f) } ?: return
    val width = screen.userUnits(WIDTH_DP).coerceAtMost(screen.bounds.width)
    val height = screen.userUnits(HEIGHT_DP)
    val bounds = java.awt.Rectangle(screen.bounds.x + (screen.bounds.width - width) / 2, screen.bounds.y, width, height)
    Window(
        create = {
            ComposeWindow().apply {
                title = "GameLodge"
                isUndecorated = true
                isTransparent = true
                isResizable = false
                isAlwaysOnTop = true
                focusableWindowState = false
                isAutoRequestFocus = false
                type = java.awt.Window.Type.UTILITY
                this.bounds = bounds
                addNotify()
                val hwnd = WinDef.HWND(Native.getComponentPointer(this))
                val user32 = User32.INSTANCE
                user32.SetWindowLong(
                    hwnd, WinUser.GWL_EXSTYLE,
                    user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE) or WS_EX_NOACTIVATE or WS_EX_TRANSPARENT,
                )
            }
        },
        dispose = ComposeWindow::dispose,
    ) {
        LauncherTheme(settings) {
            CompositionLocalProvider(LocalDensity provides Density(screen.density, LocalDensity.current.fontScale)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    ConnectionWarningPill(shown, Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

private const val WIDTH_DP = 720f
private const val HEIGHT_DP = 64f
/** Clicks go through to the stream. */
private const val WS_EX_TRANSPARENT = 0x00000020
