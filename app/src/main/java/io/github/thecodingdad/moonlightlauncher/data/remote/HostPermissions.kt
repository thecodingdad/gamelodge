package io.github.thecodingdad.moonlightlauncher.data.remote

/**
 * Per-client permissions of Apollo/Vibepollo hosts (see Artemis' ComputerDetails#toString).
 * Sunshine does not report permissions (value < 0) and allows everything.
 */
enum class HostPermission(val bit: Int) {
    CONTROLLER_INPUT(0x100),
    TOUCH_INPUT(0x200),
    MOUSE_INPUT(0x800),
    KEYBOARD_INPUT(0x1000),
    CLIPBOARD_SET(0x10000),
    CLIPBOARD_READ(0x20000),
    SERVER_COMMANDS(0x100000),
    LIST_APPS(0x1000000),
    VIEW_STREAMS(0x2000000),
    LAUNCH_APPS(0x4000000),
    ;

    companion object {
        /** What the launcher needs for normal use. */
        val essential = listOf(LAUNCH_APPS, CONTROLLER_INPUT, LIST_APPS)

        fun known(permission: Int): Boolean = permission >= 0

        fun has(permission: Int, p: HostPermission): Boolean = !known(permission) || (permission and p.bit) != 0

        fun missingEssential(permission: Int): List<HostPermission> = essential.filterNot { has(permission, it) }
    }
}
