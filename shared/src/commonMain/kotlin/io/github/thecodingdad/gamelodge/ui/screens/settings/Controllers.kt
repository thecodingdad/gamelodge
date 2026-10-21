package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.runtime.getValue
import io.github.thecodingdad.gamelodge.resources.*
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import io.github.thecodingdad.gamelodge.ui.PlatformUi

data class ControllerInfo(
    val id: Int,
    val name: String,
    val external: Boolean,
    val batteryPercent: Int?,
    val canVibrate: Boolean,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val descriptor: String = "",
    val hasTouchpad: Boolean = false,
    /** Odin Mapping's copy of an external controller (no battery, no rumble). */
    val odinCopy: Boolean = false,
) {
    /** Stable id for this controller's own settings: the device descriptor, else its name. */
    val key: String get() = descriptor.ifEmpty { name }
}

/** The controller used last (its layout decides the button hints); set by the platform on pad input. */
object ActivePad {
    var key by androidx.compose.runtime.mutableStateOf<String?>(null)
}

/** The layout a controller uses: its own, else the default of all controllers. */
fun io.github.thecodingdad.gamelodge.data.settings.LauncherSettings.layoutFor(key: String?): io.github.thecodingdad.gamelodge.data.settings.ButtonLayout =
    key?.let { controllerLayouts[it] } ?: buttonLayout

/** Default button layout of all controllers (Settings → Controllers). */
@androidx.compose.runtime.Composable
fun ControllerLayoutDefaultRow(
    settings: io.github.thecodingdad.gamelodge.data.settings.LauncherSettings,
    update: ((io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> Unit,
    initialFocus: Boolean = false,
    title: String? = null,
) {
    ChoiceRow(
        title ?: org.jetbrains.compose.resources.stringResource(io.github.thecodingdad.gamelodge.resources.Res.string.setting_button_layout),
        io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.entries, settings.buttonLayout,
        label = { layoutLabel(it) },
        onSelect = { v -> update { it.copy(buttonLayout = v) } },
        subtitle = org.jetbrains.compose.resources.stringResource(io.github.thecodingdad.gamelodge.resources.Res.string.setting_button_layout_sub),
        initialFocus = initialFocus,
    )
}

/** One controller's layout: the default of all controllers or its own (controller dialog). */
@androidx.compose.runtime.Composable
fun ControllerLayoutOverrideRow(
    controller: ControllerInfo,
    settings: io.github.thecodingdad.gamelodge.data.settings.LauncherSettings,
    update: ((io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> Unit,
) {
    val default = org.jetbrains.compose.resources.stringResource(io.github.thecodingdad.gamelodge.resources.Res.string.controller_layout_default, layoutLabel(settings.buttonLayout))
    ChoiceRow(
        org.jetbrains.compose.resources.stringResource(io.github.thecodingdad.gamelodge.resources.Res.string.setting_button_layout),
        listOf<io.github.thecodingdad.gamelodge.data.settings.ButtonLayout?>(null) + io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.entries,
        settings.controllerLayouts[controller.key],
        label = { it?.let { l -> layoutLabel(l) } ?: default },
        onSelect = { v -> update { s -> s.copy(controllerLayouts = if (v == null) s.controllerLayouts - controller.key else s.controllerLayouts + (controller.key to v)) } },
    )
}

@androidx.compose.runtime.Composable
private fun layoutLabel(l: io.github.thecodingdad.gamelodge.data.settings.ButtonLayout) = org.jetbrains.compose.resources.stringResource(
    if (l == io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.XBOX) io.github.thecodingdad.gamelodge.resources.Res.string.button_layout_xbox
    else io.github.thecodingdad.gamelodge.resources.Res.string.button_layout_nintendo,
)

/** Connected controllers, updated when one connects, disconnects or changes (battery). */
@Composable
fun rememberControllers(): List<ControllerInfo> = PlatformUi.current.rememberControllers()

fun vibrateController(id: Int) = PlatformUi.current.vibrateController(id)

@Composable
fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) = PlatformUi.current.ControllerDetailDialog(controller, onDismiss)
