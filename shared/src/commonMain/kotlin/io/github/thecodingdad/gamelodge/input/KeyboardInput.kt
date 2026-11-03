package io.github.thecodingdad.gamelodge.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import io.github.thecodingdad.gamelodge.ui.input.PadButton

/** What a keyboard key can do in GameLodge: a controller button, or open the quick menu / go home. */
enum class KeyAction(val button: PadButton?) {
    A(PadButton.A), B(PadButton.B), X(PadButton.X), Y(PadButton.Y),
    L1(PadButton.L1), R1(PadButton.R1), L2(PadButton.L2), R2(PadButton.R2),
    START(PadButton.START), SELECT(PadButton.SELECT),
    QUICK_MENU(null), HOME(null),
}

/** A key with modifiers, e.g. Ctrl+Alt+M (the system-wide quick menu shortcut). */
data class KeyShortcut(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val key: String) {
    val hasModifier: Boolean get() = ctrl || alt || shift

    /** "Ctrl+Alt+M": also the stored form. */
    override fun toString(): String = listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, key).joinToString("+")

    companion object {
        fun parse(text: String): KeyShortcut? {
            val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            val key = parts.lastOrNull()?.takeIf { it in KeyboardKeys.shortcutNames } ?: return null
            val mods = parts.dropLast(1)
            return KeyShortcut("Ctrl" in mods, "Alt" in mods, "Shift" in mods, key).takeIf { it.hasModifier }
        }
    }
}

/**
 * Keyboard use of GameLodge: which key does what ([mapping], from the settings), recording a key,
 * and whether the keyboard was used last (the hint bar then shows keys instead of buttons).
 *
 * Enter, Esc and the arrow keys always confirm, go back and move the focus. Only keys that never
 * type text can be assigned, so a text field keeps letters, digits, Space and Backspace.
 */
object KeyboardKeys {
    /** Keys that can be assigned to an action, by their stored name. */
    val assignable: List<Pair<String, Key>> = listOf(
        "F1" to Key.F1, "F2" to Key.F2, "F3" to Key.F3, "F4" to Key.F4, "F5" to Key.F5, "F6" to Key.F6,
        "F7" to Key.F7, "F8" to Key.F8, "F9" to Key.F9, "F10" to Key.F10, "F12" to Key.F12,
        "PageUp" to Key.PageUp, "PageDown" to Key.PageDown, "Home" to Key.MoveHome, "End" to Key.MoveEnd,
        "Insert" to Key.Insert, "Menu" to Key.Menu,
    )

    /** Keys of a system-wide shortcut (always with Ctrl / Alt / Shift, so letters are fine). */
    val shortcutKeys: List<Pair<String, Key>> = ('A'..'Z').map { c -> c.toString() to letterKey(c) } +
        ('0'..'9').map { c -> c.toString() to digitKey(c) } +
        assignable.filter { it.first.startsWith("F") }

    val shortcutNames: Set<String> = shortcutKeys.map { it.first }.toSet()

    /** Built-in assignment (F11 stays full screen on the PC). */
    val defaults: Map<KeyAction, String> = mapOf(
        KeyAction.X to "F2", KeyAction.Y to "F1", KeyAction.L1 to "PageUp", KeyAction.R1 to "PageDown",
        KeyAction.L2 to "Home", KeyAction.R2 to "End", KeyAction.START to "F3", KeyAction.SELECT to "F4",
        KeyAction.QUICK_MENU to "F5", KeyAction.HOME to "F6",
    )

    /** Fixed keys of confirm and back, shown in the hints. */
    const val CONFIRM_KEY = "Enter"
    const val BACK_KEY = "Esc"

    /** The user's assignment: action name → key name ("" = none); missing = [defaults]. */
    @Volatile var mapping: Map<String, String> = emptyMap()

    /** The system-wide quick menu shortcut (null = off). */
    @Volatile var quickMenuShortcut: KeyShortcut? = null

    /** The key of [action] in [map] (null = none). */
    fun keyOf(action: KeyAction, map: Map<String, String> = mapping): String? =
        (map[action.name] ?: defaults[action])?.takeIf { it.isNotEmpty() }

    fun nameOf(key: Key): String? = assignable.firstOrNull { it.second == key }?.first

    fun shortcutNameOf(key: Key): String? = shortcutKeys.firstOrNull { it.second == key }?.first

    /** What [key] does in GameLodge (null = nothing of ours). */
    fun actionFor(key: Key): KeyAction? {
        val name = nameOf(key) ?: return null
        return KeyAction.entries.firstOrNull { keyOf(it) == name }
    }

    /** Recording a key for an action: receives the pressed key's name (only [assignable] keys), null = Esc. */
    @Volatile var recording: ((String?) -> Unit)? = null

    /** Recording the system-wide shortcut: receives it (only with a modifier), null = Esc. */
    @Volatile var recordingShortcut: ((KeyShortcut?) -> Unit)? = null

    /** The keyboard was used last (not a controller, touch or mouse): hints show keys. */
    var active by mutableStateOf(false)
        private set

    fun onKeyboard() { if (!active) active = true }

    /** A real keyboard is connected (set by the platform; the PC always has one). */
    var connected by mutableStateOf(false)

    /**
     * Whether hints show keys: the keyboard was used last, or there is a keyboard but no controller
     * ([controllers]: connected controllers).
     */
    fun showsKeys(controllers: Int): Boolean = active || (connected && controllers == 0)

    /** Hints at all: switched on and something to press them with ([controllers] connected). */
    fun hintsShown(enabled: Boolean, controllers: Int): Boolean = enabled && (controllers > 0 || connected || active)

    fun onOtherInput() { if (active) active = false }

    private fun letterKey(c: Char): Key = when (c) {
        'A' -> Key.A; 'B' -> Key.B; 'C' -> Key.C; 'D' -> Key.D; 'E' -> Key.E; 'F' -> Key.F; 'G' -> Key.G
        'H' -> Key.H; 'I' -> Key.I; 'J' -> Key.J; 'K' -> Key.K; 'L' -> Key.L; 'M' -> Key.M; 'N' -> Key.N
        'O' -> Key.O; 'P' -> Key.P; 'Q' -> Key.Q; 'R' -> Key.R; 'S' -> Key.S; 'T' -> Key.T; 'U' -> Key.U
        'V' -> Key.V; 'W' -> Key.W; 'X' -> Key.X; 'Y' -> Key.Y; else -> Key.Z
    }

    private fun digitKey(c: Char): Key = when (c) {
        '0' -> Key.Zero; '1' -> Key.One; '2' -> Key.Two; '3' -> Key.Three; '4' -> Key.Four
        '5' -> Key.Five; '6' -> Key.Six; '7' -> Key.Seven; '8' -> Key.Eight; else -> Key.Nine
    }
}
