package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import io.github.thecodingdad.gamelodge.resources.*
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

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
    /** A name the user gave this controller (null = its device name). */
    val customName: String? = null,
    /** A highlight colour (ARGB) the user gave this controller (null = the default accent). */
    val colorArgb: Int? = null,
    /** The icon kind the user chose ([ControllerIconKind] name); null = detect from the vendor id. */
    val iconId: String? = null,
) {
    /** Stable id for this controller's own settings: the device descriptor, else its name. */
    val key: String get() = descriptor.ifEmpty { name }

    /** What to show for this controller everywhere: the user's name, else the device name. */
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: name

    /** The icon to show for this controller everywhere: the chosen kind, else the detected one. */
    val displayIcon: androidx.compose.ui.graphics.vector.ImageVector get() = controllerIconKind(iconId, vendorId).icon
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

/**
 * A name and a highlight colour for one controller, to tell it apart everywhere it appears. The
 * name opens the on-screen keyboard; the colour uses the same picker as the accent (swatches plus
 * an own colour), with "default" clearing it. Storage is the caller's; this only renders and calls back.
 */
@Composable
fun ControllerIdentityRows(
    deviceName: String,
    customName: String?,
    colorArgb: Int?,
    iconId: String?,
    vendorId: Int,
    onName: (String?) -> Unit,
    onColor: (Int?) -> Unit,
    onIcon: (String?) -> Unit,
) {
    val keyboard = io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController.current
    val nameTitle = stringResource(Res.string.controller_name)
    NavRow(
        title = nameTitle,
        subtitle = customName?.takeIf { it.isNotBlank() } ?: deviceName,
        onClick = { keyboard.open(nameTitle, customName.orEmpty(), controller = true) { t -> onName(t.trim().ifBlank { null }) } },
        icon = Icons.Filled.Edit,
    )
    // The icon: "automatic" (detect from the controller's vendor) or one of the known kinds.
    val iconTint = colorArgb?.let { androidx.compose.ui.graphics.Color(it) }
    ChoiceRow(
        title = stringResource(Res.string.controller_icon),
        options = listOf<String?>(null) + ControllerIconKind.entries.map { it.name },
        selected = iconId,
        label = { id -> id?.let { k -> ControllerIconKind.valueOf(k).label() } ?: stringResource(Res.string.controller_icon_auto) },
        onSelect = { onIcon(it) },
        icon = controllerIconKind(iconId, vendorId).icon,
        optionIcon = { id ->
            val kind = id?.let { ControllerIconKind.valueOf(it) } ?: controllerIconAuto(vendorId)
            androidx.compose.material3.Icon(kind.icon, null, modifier = androidx.compose.ui.Modifier.size(24.dp), tint = iconTint ?: LauncherTheme.colors.onSurface)
        },
    )
    var picker by remember { mutableStateOf(false) }
    val seed = colorArgb ?: LauncherTheme.colors.accent.toArgb()
    val defaultLabel = stringResource(Res.string.controller_color_default)
    NavRow(
        title = stringResource(Res.string.controller_color),
        onClick = { picker = true },
        subtitle = if (colorArgb == null) defaultLabel else null,
        trailingContent = { ColorDot(seed) },
    )
    if (picker) AccentColorPicker(
        seed, onChange = { onColor(it) }, onDismiss = { picker = false },
        title = stringResource(Res.string.controller_color),
        onAutomatic = { onColor(null) }, automatic = colorArgb == null,
        autoTitle = defaultLabel, autoSub = null,
    )
}
