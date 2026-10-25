package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.AWTEvent
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/**
 * The mouse pointer goes away while the launcher is used with a controller (it sat in the middle
 * of the screen) and comes back with the next real mouse input, also the emulated one: the
 * Windows pointer moves then. Compose's own synthetic moves (content scrolling under a resting
 * pointer) don't count, only AWT's mouse events.
 */
object PadCursor {
    var hidden by mutableStateOf(false)
        private set

    val blank: PointerIcon by lazy {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "none"))
    }

    private var installed = false

    fun controllerUsed() {
        install()
        hidden = true
    }

    private fun install() {
        if (installed) return
        installed = true
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (hidden && event is MouseEvent && event.id in SHOWING) hidden = false
        }, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
    }

    private val SHOWING = setOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_WHEEL)
}
