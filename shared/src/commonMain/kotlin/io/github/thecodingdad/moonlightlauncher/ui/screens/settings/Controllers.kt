package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.runtime.Composable
import io.github.thecodingdad.moonlightlauncher.ui.PlatformUi

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
)

/** Connected controllers, updated when one connects, disconnects or changes (battery). */
@Composable
fun rememberControllers(): List<ControllerInfo> = PlatformUi.current.rememberControllers()

fun vibrateController(id: Int) = PlatformUi.current.vibrateController(id)

@Composable
fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) = PlatformUi.current.ControllerDetailDialog(controller, onDismiss)
