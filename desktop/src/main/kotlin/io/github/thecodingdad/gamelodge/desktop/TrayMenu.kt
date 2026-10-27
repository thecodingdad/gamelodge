package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.util.Log

/**
 * The mouse pointer in Java's coordinates. Tray icon events report it in physical pixels on a
 * scaled screen (150 %: the menu would open off towards the corner), so it comes from Windows and
 * is scaled for the monitor it is on.
 */
private fun pointer(): java.awt.Point? {
    if (!isWindows) return null
    return runCatching {
        val p = com.sun.jna.platform.win32.WinDef.POINT()
        com.sun.jna.platform.win32.User32.INSTANCE.GetCursorPos(p)
        val config = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration }.firstOrNull { c ->
            val b = c.bounds
            val scale = c.defaultTransform.scaleX
            p.x >= b.x && p.y >= b.y && p.x < b.x + b.width * scale && p.y < b.y + b.height * scale
        } ?: return@runCatching null
        val b = config.bounds
        val scale = config.defaultTransform.scaleX
        java.awt.Point(b.x + ((p.x - b.x) / scale).toInt(), b.y + ((p.y - b.y) / scale).toInt())
    }.getOrNull()
}

/** An entry of the tray icon's menu. */
class TrayItem(val label: String, val onClick: () -> Unit)

/**
 * The launcher's tray icon: a left click opens the window, a right click shows a small menu in the
 * launcher's look (scaled like everything else; Java's own tray menu ignores Windows' scaling).
 * The menu closes when an entry is picked or something else is clicked.
 */
@Composable
fun LauncherTray(icon: java.awt.Image, tooltip: String, settings: LauncherSettings, items: List<TrayItem>, onOpen: () -> Unit) {
    var menuAt by remember { mutableStateOf<java.awt.Point?>(null) }
    val open by rememberUpdatedState(onOpen)
    DisposableEffect(icon, tooltip) {
        if (!java.awt.SystemTray.isSupported()) return@DisposableEffect onDispose { }
        val trayIcon = java.awt.TrayIcon(icon, tooltip).apply {
            isImageAutoSize = true
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    when (e.button) {
                        java.awt.event.MouseEvent.BUTTON1 -> java.awt.EventQueue.invokeLater { menuAt = null; open() }
                        java.awt.event.MouseEvent.BUTTON3 -> java.awt.EventQueue.invokeLater { menuAt = pointer() ?: e.locationOnScreen }
                    }
                }
            })
        }
        val tray = java.awt.SystemTray.getSystemTray()
        runCatching { tray.add(trayIcon) }.onFailure { Log.w("Tray icon not added", it) }
        onDispose { tray.remove(trayIcon) }
    }
    val at = menuAt ?: return
    val state = rememberWindowState(size = DpSize.Unspecified, position = WindowPosition(at.x.dp, at.y.dp))
    Window(
        onCloseRequest = { menuAt = null },
        state = state,
        title = "GameLodge",
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        LaunchedEffect(window) {
            // Above and left of the pointer like Windows' own menus, inside the screen.
            androidx.compose.runtime.withFrameNanos { }
            val screen = window.graphicsConfiguration.bounds
            val x = (at.x - window.width).coerceIn(screen.x, screen.x + screen.width - window.width)
            val y = (at.y - window.height).coerceIn(screen.y, screen.y + screen.height - window.height)
            window.setLocation(x, y)
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) = Unit
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { menuAt = null }
            })
            if (isWindows) WindowsForeground.claim(window)
        }
        LauncherTheme(settings) {
            val colors = LauncherTheme.colors
            Column(
                Modifier.widthIn(min = 180.dp).background(colors.surface).border(1.dp, colors.surfaceRaised).padding(vertical = 6.dp),
            ) {
                items.forEach { item ->
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Text(
                        item.label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                        modifier = Modifier.width(220.dp)
                            .hoverable(interaction)
                            .background(if (hovered) colors.surfaceRaised else colors.surface)
                            .clickable(interaction, indication = null) { menuAt = null; item.onClick() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
