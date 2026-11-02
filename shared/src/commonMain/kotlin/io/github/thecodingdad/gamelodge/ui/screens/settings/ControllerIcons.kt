package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The icon shown for a controller everywhere it appears. [GENERIC] and [RETRO] reuse Material
 * glyphs (a modern and a classic pad); the brand kinds are the vendor logos (single-path, from
 * the public simple-icons set, tinted like any other icon). A controller's kind is picked by hand
 * or, by default, detected from its USB vendor id ([controllerIconAuto]).
 */
enum class ControllerIconKind(val icon: ImageVector, private val label: StringResource) {
    GENERIC(Icons.Filled.SportsEsports, Res.string.controller_icon_generic),
    HANDHELD(brandIcon("Handheld", HANDHELD_PATH, PathFillType.EvenOdd), Res.string.controller_icon_handheld),
    XBOX(brandIcon("Xbox", XBOX_PATH), Res.string.controller_icon_xbox),
    PLAYSTATION(brandIcon("PlayStation", PLAYSTATION_PATH), Res.string.controller_icon_playstation),
    NINTENDO(brandIcon("NintendoSwitch", NINTENDO_PATH), Res.string.controller_icon_nintendo),
    GAMECUBE(brandIcon("GameCube", GAMECUBE_PATH), Res.string.controller_icon_gamecube),
    STEAM(brandIcon("Steam", STEAM_PATH), Res.string.controller_icon_steam),
    RETRO(Icons.Filled.Gamepad, Res.string.controller_icon_retro);

    @Composable fun label(): String = stringResource(label)
}

/** The kind detected from a USB vendor id, or [GENERIC] for an unknown one. */
fun controllerIconAuto(vendorId: Int): ControllerIconKind = when (vendorId) {
    0x045E -> ControllerIconKind.XBOX        // Microsoft
    0x054C -> ControllerIconKind.PLAYSTATION // Sony
    0x057E -> ControllerIconKind.NINTENDO    // Nintendo
    0x28DE -> ControllerIconKind.STEAM       // Valve
    0x2DC8 -> ControllerIconKind.RETRO       // 8BitDo (SNES-style pads)
    0x2020 -> ControllerIconKind.HANDHELD    // AYN Odin built-in controls
    else -> ControllerIconKind.GENERIC
}

/** The kind to use: the one the user chose ([iconId] = its name), else detected from [vendorId]. */
fun controllerIconKind(iconId: String?, vendorId: Int): ControllerIconKind =
    iconId?.let { id -> ControllerIconKind.entries.firstOrNull { it.name == id } } ?: controllerIconAuto(vendorId)

/**
 * The button-hint layout that matches a controller's vendor (Xbox, PlayStation or Nintendo), or
 * null when the pad has no own button style and the global default should be used.
 */
fun buttonLayoutForVendor(vendorId: Int): ButtonLayout? = when (controllerIconAuto(vendorId)) {
    ControllerIconKind.XBOX -> ButtonLayout.XBOX
    ControllerIconKind.PLAYSTATION -> ButtonLayout.PLAYSTATION
    ControllerIconKind.NINTENDO -> ButtonLayout.NINTENDO
    else -> null
}

private fun brandIcon(name: String, pathData: String, fillType: PathFillType = PathFillType.NonZero): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes(pathData), pathFillType = fillType, fill = SolidColor(Color.Black))
    }.build()

// A generic handheld: a landscape device with a centred screen, a round d-pad and two buttons
// (cut out via the even-odd fill), for built-in console controls like the Odin's.
private const val HANDHELD_PATH =
    "M4 6h16a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z" +
        "M9 9h6v6H9z" +
        "M6.3 12m-1.3 0a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z" +
        "M17.3 10.5m-0.9 0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0z" +
        "M18.9 13.6m-0.9 0a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0z"

private const val XBOX_PATH =
    "M4.102 21.033C6.211 22.881 8.977 24 12 24c3.026 0 5.789-1.119 7.902-2.967 1.877-1.912-4.316-8.709-7.902-11.417-3.582 2.708-9.779 9.505-7.898 11.417zm11.16-14.406c2.5 2.961 7.484 10.313 6.076 12.912C23.002 17.48 24 14.861 24 12.004c0-3.34-1.365-6.362-3.57-8.536 0 0-.027-.022-.082-.042-.063-.022-.152-.045-.281-.045-.592 0-1.985.434-4.805 3.246zM3.654 3.426c-.057.02-.082.041-.086.042C1.365 5.642 0 8.664 0 12.004c0 2.854.998 5.473 2.661 7.533-1.401-2.605 3.579-9.951 6.08-12.91-2.82-2.813-4.216-3.245-4.806-3.245-.131 0-.223.021-.281.046v-.002zM12 3.551S9.055 1.828 6.755 1.746c-.903-.033-1.454.295-1.521.339C7.379.646 9.659 0 11.984 0H12c2.334 0 4.605.646 6.766 2.085-.068-.046-.615-.372-1.52-.339C14.946 1.828 12 3.545 12 3.545v.006z"

private const val PLAYSTATION_PATH =
    "M8.984 2.596v17.547l3.915 1.261V6.688c0-.69.304-1.151.794-.991.636.18.76.814.76 1.505v5.875c2.441 1.193 4.362-.002 4.362-3.152 0-3.237-1.126-4.675-4.438-5.827-1.307-.448-3.728-1.186-5.39-1.502zm4.656 16.241l6.296-2.275c.715-.258.826-.625.246-.818-.586-.192-1.637-.139-2.357.123l-4.205 1.5V14.98l.24-.085s1.201-.42 2.913-.615c1.696-.18 3.785.03 5.437.661 1.848.601 2.04 1.472 1.576 2.072-.465.6-1.622 1.036-1.622 1.036l-8.544 3.107V18.86zM1.807 18.6c-1.9-.545-2.214-1.668-1.352-2.32.801-.586 2.16-1.052 2.16-1.052l5.615-2.013v2.313L4.205 17c-.705.271-.825.632-.239.826.586.195 1.637.15 2.343-.12L8.247 17v2.074c-.12.03-.256.044-.39.073-1.939.331-3.996.196-6.038-.479z"

private const val NINTENDO_PATH =
    "M14.176 24h3.674c3.376 0 6.15-2.774 6.15-6.15V6.15C24 2.775 21.226 0 17.85 0H14.1c-.074 0-.15.074-.15.15v23.7c-.001.076.075.15.226.15zm4.574-13.199c1.351 0 2.399 1.125 2.399 2.398 0 1.352-1.125 2.4-2.399 2.4-1.35 0-2.4-1.049-2.4-2.4-.075-1.349 1.05-2.398 2.4-2.398zM11.4 0H6.15C2.775 0 0 2.775 0 6.15v11.7C0 21.226 2.775 24 6.15 24h5.25c.074 0 .15-.074.15-.149V.15c.001-.076-.075-.15-.15-.15zM9.676 22.051H6.15c-2.326 0-4.201-1.875-4.201-4.201V6.15c0-2.326 1.875-4.201 4.201-4.201H9.6l.076 20.102zM3.75 7.199c0 1.275.975 2.25 2.25 2.25s2.25-.975 2.25-2.25c0-1.273-.975-2.25-2.25-2.25s-2.25.977-2.25 2.25z"

private const val GAMECUBE_PATH =
    "M6.816 15.126l4.703 2.715v-5.433L6.814 9.695v5.432zm-2.025 1.168l6.73 3.882v3.82L1.481 18.206V6.616l3.31 1.91v7.769zM12 6.145L7.298 8.863 12 11.579l4.704-2.717L12 6.146zm0-2.332l5.659 3.274 3.31-1.91L12 0 1.975 5.79 5.28 7.695zm7.207 12.48v-3.947l-2.023 1.167v1.614l-4.703 2.715v.005-5.436L22.518 6.62v11.587L12.48 24v-3.817l6.727-3.887z"

private const val STEAM_PATH =
    "M11.979 0C5.678 0 .511 4.86.022 11.037l6.432 2.658c.545-.371 1.203-.59 1.912-.59.063 0 .125.004.188.006l2.861-4.142V8.91c0-2.495 2.028-4.524 4.524-4.524 2.494 0 4.524 2.031 4.524 4.527s-2.03 4.525-4.524 4.525h-.105l-4.076 2.911c0 .052.004.105.004.159 0 1.875-1.515 3.396-3.39 3.396-1.635 0-3.016-1.173-3.331-2.727L.436 15.27C1.862 20.307 6.486 24 11.979 24c6.627 0 11.999-5.373 11.999-12S18.605 0 11.979 0zM7.54 18.21l-1.473-.61c.262.543.714.999 1.314 1.25 1.297.539 2.793-.076 3.332-1.375.263-.63.264-1.319.005-1.949s-.75-1.121-1.377-1.383c-.624-.26-1.29-.249-1.878-.03l1.523.63c.956.4 1.409 1.5 1.009 2.455-.397.957-1.497 1.41-2.454 1.012H7.54zm11.415-9.303c0-1.662-1.353-3.015-3.015-3.015-1.665 0-3.015 1.353-3.015 3.015 0 1.665 1.35 3.015 3.015 3.015 1.663 0 3.015-1.35 3.015-3.015zm-5.273-.005c0-1.252 1.013-2.266 2.265-2.266 1.249 0 2.266 1.014 2.266 2.266 0 1.251-1.017 2.265-2.266 2.265-1.253 0-2.265-1.014-2.265-2.265z"
