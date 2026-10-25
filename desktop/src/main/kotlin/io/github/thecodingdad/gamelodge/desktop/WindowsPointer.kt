package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import kotlin.math.abs
import kotlin.math.sign

/**
 * GameLodge's own mouse emulation on the PC (outside the stream): the controllers move the Windows
 * pointer in every program. Same buttons as Moonlight's: A left, B right, X middle click, LB / RB
 * back / forward, right stick and D-pad scroll. Fed by the gamepad poller while [active].
 */
object WindowsPointer {
    @Volatile var active = false
        private set

    /** Called when it's switched on or off (a short notice). */
    @Volatile var onChange: (Boolean) -> Unit = {}

    private class Held(
        var a: Boolean = false, var b: Boolean = false, var x: Boolean = false, var lb: Boolean = false, var rb: Boolean = false,
        var last: Long = 0L,
    )
    private val held = HashMap<Int, Held>()
    private var restX = 0f
    private var restY = 0f
    private var nextScroll = 0L

    @Synchronized
    fun toggle() {
        active = !active
        if (!active) {
            // Nothing stays pressed when it goes off mid-click.
            held.values.forEach { h ->
                if (h.a) button(MOUSEEVENTF_LEFTUP)
                if (h.b) button(MOUSEEVENTF_RIGHTUP)
                if (h.x) button(MOUSEEVENTF_MIDDLEUP)
            }
            held.clear()
        }
        onChange(active)
    }

    @Synchronized
    fun update(
        pad: Int, lx: Float, ly: Float, rx: Float, ry: Float,
        a: Boolean, b: Boolean, x: Boolean, lb: Boolean, rb: Boolean,
        up: Boolean, down: Boolean, left: Boolean, right: Boolean, now: Long,
    ) {
        if (!active) return
        val h = held.getOrPut(pad) { Held() }
        val dt = if (h.last == 0L) 0f else ((now - h.last) / 1000f).coerceAtMost(0.05f)
        h.last = now
        val speed = SPEED * dpiScale()
        restX += curve(deadzone(lx)) * speed * dt
        restY += curve(deadzone(ly)) * speed * dt
        val dx = restX.toInt()
        val dy = restY.toInt()
        if (dx != 0 || dy != 0) {
            restX -= dx
            restY -= dy
            send(mouse(dx, dy, 0, MOUSEEVENTF_MOVE))
        }
        if (a != h.a) { h.a = a; button(if (a) MOUSEEVENTF_LEFTDOWN else MOUSEEVENTF_LEFTUP) }
        if (b != h.b) { h.b = b; button(if (b) MOUSEEVENTF_RIGHTDOWN else MOUSEEVENTF_RIGHTUP) }
        if (x != h.x) { h.x = x; button(if (x) MOUSEEVENTF_MIDDLEDOWN else MOUSEEVENTF_MIDDLEUP) }
        if (lb != h.lb) { h.lb = lb; send(mouse(0, 0, XBUTTON1, if (lb) MOUSEEVENTF_XDOWN else MOUSEEVENTF_XUP)) }
        if (rb != h.rb) { h.rb = rb; send(mouse(0, 0, XBUTTON2, if (rb) MOUSEEVENTF_XDOWN else MOUSEEVENTF_XUP)) }
        // Scrolling: notches repeat while the stick or D-pad is held, faster further out.
        val vy = when { up -> -1f; down -> 1f; else -> deadzone(ry) }
        val vx = when { left -> -1f; right -> 1f; else -> deadzone(rx) }
        if ((vx != 0f || vy != 0f) && now >= nextScroll) {
            if (vy != 0f) send(mouse(0, 0, (-vy.sign * WHEEL_DELTA), MOUSEEVENTF_WHEEL))
            if (vx != 0f) send(mouse(0, 0, (vx.sign * WHEEL_DELTA), MOUSEEVENTF_HWHEEL))
            val strength = maxOf(abs(vx), abs(vy))
            nextScroll = now + (SCROLL_SLOW_MS - (SCROLL_SLOW_MS - SCROLL_FAST_MS) * strength).toLong()
        }
    }

    private fun curve(v: Float) = v * v * v.sign

    private fun deadzone(v: Float) = if (abs(v) < DEADZONE) 0f else v

    private fun dpiScale(): Float = runCatching { java.awt.Toolkit.getDefaultToolkit().screenResolution / 96f }.getOrDefault(1f)

    private fun button(flags: Int) = send(mouse(0, 0, 0, flags))

    private fun mouse(dx: Int, dy: Int, data: Number, flags: Int) = WinUser.INPUT().apply {
        type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        input.setType("mi")
        input.mi.dx = WinDef.LONG(dx.toLong())
        input.mi.dy = WinDef.LONG(dy.toLong())
        input.mi.mouseData = WinDef.DWORD(data.toLong() and 0xffffffffL)
        input.mi.dwFlags = WinDef.DWORD(flags.toLong())
        input.mi.time = WinDef.DWORD(0)
        input.mi.dwExtraInfo = BaseTSD.ULONG_PTR(0)
    }

    private fun send(input: WinUser.INPUT) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val array = input.toArray(1) as Array<WinUser.INPUT>
            User32.INSTANCE.SendInput(WinDef.DWORD(1), array, input.size())
        }
    }

    private const val SPEED = 900f
    private const val DEADZONE = 0.15f
    private const val WHEEL_DELTA = 120
    private const val SCROLL_SLOW_MS = 220f
    private const val SCROLL_FAST_MS = 50f
    private const val MOUSEEVENTF_MOVE = 0x0001
    private const val MOUSEEVENTF_LEFTDOWN = 0x0002
    private const val MOUSEEVENTF_LEFTUP = 0x0004
    private const val MOUSEEVENTF_RIGHTDOWN = 0x0008
    private const val MOUSEEVENTF_RIGHTUP = 0x0010
    private const val MOUSEEVENTF_MIDDLEDOWN = 0x0020
    private const val MOUSEEVENTF_MIDDLEUP = 0x0040
    private const val MOUSEEVENTF_XDOWN = 0x0080
    private const val MOUSEEVENTF_XUP = 0x0100
    private const val MOUSEEVENTF_WHEEL = 0x0800
    private const val MOUSEEVENTF_HWHEEL = 0x1000
    private const val XBUTTON1 = 1
    private const val XBUTTON2 = 2
}
