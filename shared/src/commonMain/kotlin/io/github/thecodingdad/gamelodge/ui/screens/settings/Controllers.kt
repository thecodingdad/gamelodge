package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
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
    val displayIcon: androidx.compose.ui.graphics.vector.ImageVector get() = controllerIconKind(iconId, vendorId, productId).icon
}

/** The controller used last (its layout decides the button hints); set by the platform on pad input. */
object ActivePad {
    var key by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The layout detected from the active controller's vendor (null = unknown → the global default). */
    var detectedLayout by androidx.compose.runtime.mutableStateOf<io.github.thecodingdad.gamelodge.data.settings.ButtonLayout?>(null)
}

/**
 * The layout a controller uses: its own override, else the layout [detected] from its type
 * (a Sony pad shows PlayStation glyphs, a Nintendo pad Nintendo), else the default of all controllers.
 */
fun io.github.thecodingdad.gamelodge.data.settings.LauncherSettings.layoutFor(
    key: String?,
    detected: io.github.thecodingdad.gamelodge.data.settings.ButtonLayout? = null,
): io.github.thecodingdad.gamelodge.data.settings.ButtonLayout =
    key?.let { controllerLayouts[it] } ?: detected ?: buttonLayout

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
    when (l) {
        io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.XBOX -> io.github.thecodingdad.gamelodge.resources.Res.string.button_layout_xbox
        io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.NINTENDO -> io.github.thecodingdad.gamelodge.resources.Res.string.button_layout_nintendo
        io.github.thecodingdad.gamelodge.data.settings.ButtonLayout.PLAYSTATION -> io.github.thecodingdad.gamelodge.resources.Res.string.button_layout_playstation
    },
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
    productId: Int,
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
    // The icon: a row that opens a popup with a grid of just the glyphs (no labels).
    val iconTint = colorArgb?.let { androidx.compose.ui.graphics.Color(it) }
    var iconPicker by remember { mutableStateOf(false) }
    NavRow(
        title = stringResource(Res.string.controller_icon),
        onClick = { iconPicker = true },
        subtitle = if (iconId == null) stringResource(Res.string.controller_icon_auto) else null,
        trailingContent = {
            androidx.compose.material3.Icon(
                controllerIconKind(iconId, vendorId, productId).icon, null,
                tint = iconTint ?: LauncherTheme.colors.onSurface,
                modifier = androidx.compose.ui.Modifier.size(26.dp),
            )
        },
    )
    if (iconPicker) ControllerIconPickerPopup(
        selected = iconId, iconTint = iconTint,
        onSelect = { onIcon(it); iconPicker = false },
        onDismiss = { iconPicker = false },
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

/** Popup to pick a controller's icon: just the glyphs in a grid, no labels. */
@Composable
private fun ControllerIconPickerPopup(
    selected: String?,
    iconTint: androidx.compose.ui.graphics.Color?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = true, onDismiss = onDismiss,
        title = stringResource(Res.string.controller_icon), maxWidth = 560.dp,
    ) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.weight(1f, fill = false)
                .popupHeightIn(520.dp).controllerScroll(),
        ) {
            ControllerIconGrid(selected = selected, iconTint = iconTint, onSelect = onSelect)
        }
    }
}

/** All controller icons as a compact grid of glyphs only; the first tile ("automatic") clears the choice. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ControllerIconGrid(selected: String?, iconTint: androidx.compose.ui.graphics.Color?, onSelect: (String?) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    ) {
        IconTile(androidx.compose.material.icons.Icons.Filled.Autorenew, selected == null, iconTint) { onSelect(null) }
        ControllerIconKind.entries.forEach { kind ->
            IconTile(kind.icon, selected == kind.name, iconTint) { onSelect(kind.name) }
        }
    }
}

@Composable
private fun IconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    iconTint: androidx.compose.ui.graphics.Color?,
    onClick: () -> Unit,
) {
    val colors = LauncherTheme.colors
    io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (selected) colors.accent.copy(alpha = 0.28f) else colors.surfaceRaised,
        focusScale = 1.1f,
        modifier = androidx.compose.ui.Modifier.size(52.dp).restoreFocusIf(selected),
    ) {
        androidx.compose.material3.Icon(
            icon, null,
            tint = iconTint ?: colors.onSurface,
            modifier = androidx.compose.ui.Modifier.size(28.dp).align(androidx.compose.ui.Alignment.Center),
        )
    }
}
