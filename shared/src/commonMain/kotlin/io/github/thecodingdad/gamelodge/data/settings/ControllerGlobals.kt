package io.github.thecodingdad.gamelodge.data.settings

import io.github.thecodingdad.gamelodge.input.ComboAction

/** True while the former global button layout / combinations still differ from the built-in ones. */
val LauncherSettings.hasControllerGlobals: Boolean
    get() {
        val d = LauncherSettings()
        return buttonLayout != d.buttonLayout || comboForward != d.comboForward || ComboAction.entries.any { combo(it) != d.combo(it) }
    }

/**
 * The button layout and combinations used to be global (each pad could differ); now every pad has
 * its own (by name on the PC). Global values the user had changed become the own values of the
 * [known] pads that had none, then the globals go back to the built-in ones.
 */
fun LauncherSettings.withControllerGlobalsMoved(known: Set<String>): LauncherSettings {
    if (!hasControllerGlobals) return this
    val d = LauncherSettings()
    val changed = ComboAction.entries.filter { combo(it) != d.combo(it) }
    val combos = known.associateWith { key ->
        val own = controllerCombos[key] ?: PadCombos()
        own.copy(
            combos = changed.filter { it.name !in own.combos }.associate { it.name to combo(it).toList() } + own.combos,
            forward = own.forward ?: comboForward.takeIf { it != d.comboForward },
        )
    }.filterValues { !it.isEmpty }
    val layouts = if (buttonLayout != d.buttonLayout) known.associateWith { buttonLayout } else emptyMap()
    return copy(
        controllerCombos = controllerCombos + combos,
        controllerLayouts = layouts + controllerLayouts,
        buttonLayout = d.buttonLayout,
        comboForward = d.comboForward,
        comboHome = d.comboHome,
        comboQuickMenu = d.comboQuickMenu,
        comboMouse = d.comboMouse,
        comboStats = d.comboStats,
    )
}
