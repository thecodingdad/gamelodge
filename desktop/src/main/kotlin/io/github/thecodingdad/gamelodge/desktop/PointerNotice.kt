package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.resources.*
import java.awt.Color
import java.awt.Font
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A short notice at the bottom of the screen when the mouse emulation goes on or off (above every program). */
object PointerNotice {
    private var window: JWindow? = null
    private var hide: Timer? = null

    fun show(on: Boolean) {
        val text = kotlinx.coroutines.runBlocking {
            org.jetbrains.compose.resources.getString(if (on) Res.string.mouse_on else Res.string.mouse_off)
        }
        SwingUtilities.invokeLater {
            hide?.stop()
            window?.dispose()
            val label = JLabel(text).apply {
                foreground = Color.WHITE
                font = font.deriveFont(Font.BOLD, 18f * Toolkit.getDefaultToolkit().screenResolution / 96f)
                border = BorderFactory.createEmptyBorder(12, 24, 12, 24)
                isOpaque = true
                background = Color(20, 22, 28)
            }
            val w = JWindow().apply {
                isAlwaysOnTop = true
                focusableWindowState = false
                contentPane.add(label)
                pack()
                val screen = Toolkit.getDefaultToolkit().screenSize
                setLocation((screen.width - width) / 2, screen.height - height - screen.height / 10)
                isVisible = true
            }
            window = w
            hide = Timer(1_500) { w.dispose(); if (window === w) window = null }.apply { isRepeats = false; start() }
        }
    }
}
