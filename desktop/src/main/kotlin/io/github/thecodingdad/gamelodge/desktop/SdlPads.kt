package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import io.github.thecodingdad.gamelodge.util.Log
import java.io.File

/**
 * Game controllers through SDL3: the same SDL3.dll Moonlight-Qt ships, so the launcher and the
 * stream see the same controllers with the same names, ids and serial numbers ([key]).
 * Controllers keep their slot ([state] / [info] index) while connected; a new one takes the first
 * free slot. Only used from one thread (the pad poll thread).
 */
class SdlPads {
    /** One controller's buttons and axes. Sticks -1..1 (down positive, as SDL reports), triggers 0..1. */
    data class State(
        val a: Boolean, val b: Boolean, val x: Boolean, val y: Boolean,
        val lb: Boolean, val rb: Boolean, val start: Boolean, val back: Boolean, val guide: Boolean,
        val leftStickClick: Boolean, val rightStickClick: Boolean,
        val dpadUp: Boolean, val dpadDown: Boolean, val dpadLeft: Boolean, val dpadRight: Boolean,
        val leftStickX: Float, val leftStickY: Float, val rightStickX: Float, val rightStickY: Float,
        val leftTrigger: Float, val rightTrigger: Float,
    )

    /** What the launcher shows and keys a controller's settings by. */
    data class Info(
        val name: String, val vendorId: Int, val productId: Int, val serial: String?,
        val batteryPercent: Int?, val canVibrate: Boolean, val hasTouchpad: Boolean,
    ) {
        /**
         * The controller's settings key: "vendor:product:serial" where SDL reports a serial number
         * (most PlayStation and Nintendo pads), else "vendor:product" (Xbox pads have none, so
         * several of the same model share it).
         */
        val key: String get() = "%04x:%04x".format(vendorId, productId) + (serial?.takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
    }

    @Suppress("FunctionName")
    private interface Sdl3 : Library {
        fun SDL_SetHint(name: String, value: String): Byte
        fun SDL_Init(flags: Int): Byte
        fun SDL_GetError(): String?
        fun SDL_PumpEvents()
        fun SDL_FlushEvents(minType: Int, maxType: Int)
        fun SDL_UpdateGamepads()
        fun SDL_GetGamepads(count: IntByReference): Pointer?
        fun SDL_free(memory: Pointer)
        fun SDL_OpenGamepad(instanceId: Int): Pointer?
        fun SDL_CloseGamepad(gamepad: Pointer)
        fun SDL_GamepadConnected(gamepad: Pointer): Byte
        fun SDL_GetGamepadButton(gamepad: Pointer, button: Int): Byte
        fun SDL_GetGamepadAxis(gamepad: Pointer, axis: Int): Short
        fun SDL_GetGamepadName(gamepad: Pointer): String?
        fun SDL_GetGamepadVendor(gamepad: Pointer): Short
        fun SDL_GetGamepadProduct(gamepad: Pointer): Short
        fun SDL_GetGamepadSerial(gamepad: Pointer): String?
        fun SDL_GetGamepadPowerInfo(gamepad: Pointer, percent: IntByReference): Int
        fun SDL_GetGamepadProperties(gamepad: Pointer): Int
        fun SDL_GetBooleanProperty(props: Int, name: String, default: Byte): Byte
        fun SDL_GetNumGamepadTouchpads(gamepad: Pointer): Int
        fun SDL_RumbleGamepad(gamepad: Pointer, low: Short, high: Short, durationMs: Int): Byte
    }

    private var sdl: Sdl3? = null
    private val slots = ArrayList<Pair<Int, Pointer>?>() // slot -> (instance id, gamepad)
    private val infos = HashMap<Int, Info>() // slot -> info (refreshed with [refreshInfo])

    /** Loads SDL3 (next to Moonlight-Qt) and starts its gamepad support; throws when that fails. */
    fun init(library: File) {
        val lib = Native.load(library.path, Sdl3::class.java)
        // Input also while another window (a game, the stream) has the focus: the combinations.
        lib.SDL_SetHint("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1")
        // Device messages on SDL's own thread: no window of ours pumps them.
        lib.SDL_SetHint("SDL_JOYSTICK_THREAD", "1")
        if (lib.SDL_Init(SDL_INIT_GAMEPAD) == 0.toByte()) error("SDL_Init failed: ${lib.SDL_GetError()}")
        sdl = lib
        Log.i("Gamepads: SDL3 from ${library.path}")
    }

    /** Number of slots (some may be empty). */
    val count: Int get() = slots.size

    /** Takes in connected and gone controllers and their newest state. */
    fun update() {
        val lib = sdl ?: return
        lib.SDL_PumpEvents()
        // Nothing reads SDL's event queue: emptied so it doesn't fill up.
        lib.SDL_FlushEvents(0, 0xFFFF)
        lib.SDL_UpdateGamepads()
        val count = IntByReference()
        val array = lib.SDL_GetGamepads(count)
        val ids = array?.let { p -> p.getIntArray(0, count.value).toList().also { lib.SDL_free(p) } }.orEmpty()
        for (slot in slots.indices) {
            val (id, pad) = slots[slot] ?: continue
            if (id !in ids || lib.SDL_GamepadConnected(pad) == 0.toByte()) {
                lib.SDL_CloseGamepad(pad)
                slots[slot] = null
                infos.remove(slot)
            }
        }
        for (id in ids) {
            if (slots.any { it?.first == id }) continue
            val pad = lib.SDL_OpenGamepad(id) ?: continue
            val free = slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: slots.size.also { slots.add(null) }
            slots[free] = id to pad
            infos[free] = readInfo(lib, pad)
        }
        while (slots.isNotEmpty() && slots.last() == null) slots.removeAt(slots.size - 1)
    }

    /** The controller in [slot]; null when the slot is empty. */
    fun state(slot: Int): State? {
        val lib = sdl ?: return null
        val pad = slots.getOrNull(slot)?.second ?: return null
        fun button(b: Int) = lib.SDL_GetGamepadButton(pad, b) != 0.toByte()
        fun axis(a: Int) = (lib.SDL_GetGamepadAxis(pad, a) / 32767f).coerceIn(-1f, 1f)
        // Buttons by position, as SDL3 reports them: south = A on Xbox, B on a Nintendo pad.
        return State(
            a = button(BUTTON_SOUTH), b = button(BUTTON_EAST), x = button(BUTTON_WEST), y = button(BUTTON_NORTH),
            lb = button(BUTTON_LEFT_SHOULDER), rb = button(BUTTON_RIGHT_SHOULDER),
            start = button(BUTTON_START), back = button(BUTTON_BACK), guide = button(BUTTON_GUIDE),
            leftStickClick = button(BUTTON_LEFT_STICK), rightStickClick = button(BUTTON_RIGHT_STICK),
            dpadUp = button(BUTTON_DPAD_UP), dpadDown = button(BUTTON_DPAD_DOWN),
            dpadLeft = button(BUTTON_DPAD_LEFT), dpadRight = button(BUTTON_DPAD_RIGHT),
            leftStickX = axis(AXIS_LEFTX), leftStickY = axis(AXIS_LEFTY),
            rightStickX = axis(AXIS_RIGHTX), rightStickY = axis(AXIS_RIGHTY),
            leftTrigger = axis(AXIS_LEFT_TRIGGER).coerceAtLeast(0f), rightTrigger = axis(AXIS_RIGHT_TRIGGER).coerceAtLeast(0f),
        )
    }

    /** The controller in [slot] (null: empty); [refresh] reads the battery again. */
    fun info(slot: Int, refresh: Boolean = false): Info? {
        val lib = sdl ?: return null
        val pad = slots.getOrNull(slot)?.second ?: return null
        if (refresh) infos[slot] = readInfo(lib, pad)
        return infos[slot]
    }

    fun vibrate(slot: Int, strength: Float, millis: Int): Boolean {
        val lib = sdl ?: return false
        val pad = slots.getOrNull(slot)?.second ?: return false
        val level = (strength.coerceIn(0f, 1f) * 65535).toInt().toShort()
        return lib.SDL_RumbleGamepad(pad, level, level, millis) != 0.toByte()
    }

    private fun readInfo(lib: Sdl3, pad: Pointer): Info {
        val percent = IntByReference(-1)
        val power = lib.SDL_GetGamepadPowerInfo(pad, percent)
        val props = lib.SDL_GetGamepadProperties(pad)
        return Info(
            name = lib.SDL_GetGamepadName(pad) ?: "Controller",
            vendorId = lib.SDL_GetGamepadVendor(pad).toInt() and 0xFFFF,
            productId = lib.SDL_GetGamepadProduct(pad).toInt() and 0xFFFF,
            serial = lib.SDL_GetGamepadSerial(pad),
            // Only on battery: wired or unknown shows nothing.
            batteryPercent = percent.value.takeIf { power == POWERSTATE_ON_BATTERY && it >= 0 },
            canVibrate = lib.SDL_GetBooleanProperty(props, PROP_CAP_RUMBLE, 0) != 0.toByte(),
            hasTouchpad = lib.SDL_GetNumGamepadTouchpads(pad) > 0,
        )
    }

    private companion object {
        const val SDL_INIT_GAMEPAD = 0x2000
        const val POWERSTATE_ON_BATTERY = 1
        const val PROP_CAP_RUMBLE = "SDL.joystick.cap.rumble"
        const val BUTTON_SOUTH = 0
        const val BUTTON_EAST = 1
        const val BUTTON_WEST = 2
        const val BUTTON_NORTH = 3
        const val BUTTON_BACK = 4
        const val BUTTON_GUIDE = 5
        const val BUTTON_START = 6
        const val BUTTON_LEFT_STICK = 7
        const val BUTTON_RIGHT_STICK = 8
        const val BUTTON_LEFT_SHOULDER = 9
        const val BUTTON_RIGHT_SHOULDER = 10
        const val BUTTON_DPAD_UP = 11
        const val BUTTON_DPAD_DOWN = 12
        const val BUTTON_DPAD_LEFT = 13
        const val BUTTON_DPAD_RIGHT = 14
        const val AXIS_LEFTX = 0
        const val AXIS_LEFTY = 1
        const val AXIS_RIGHTX = 2
        const val AXIS_RIGHTY = 3
        const val AXIS_LEFT_TRIGGER = 4
        const val AXIS_RIGHT_TRIGGER = 5
    }
}
