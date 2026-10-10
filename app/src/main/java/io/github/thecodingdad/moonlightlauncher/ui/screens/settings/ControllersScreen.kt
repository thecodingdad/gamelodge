package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import android.hardware.input.InputManager
import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

data class ControllerInfo(
    val id: Int,
    val name: String,
    val external: Boolean,
    val batteryPercent: Int?,
    val canVibrate: Boolean,
    val vendorId: Int = 0,
    val productId: Int = 0,
)

/** All connected gamepads (built-in, Bluetooth, USB). */
fun connectedControllers(): List<ControllerInfo> = InputDevice.getDeviceIds().toList()
    .mapNotNull { InputDevice.getDevice(it) }
    .filter { d ->
        (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
    .filter { !it.isVirtual }
    .distinctBy { it.descriptor }
    .map { d ->
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() }?.let { (it * 100).toInt() }
        } else null
        val vibrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) d.vibratorManager.vibratorIds.isNotEmpty() else d.vibrator.hasVibrator()
        ControllerInfo(d.id, d.name, d.isExternal, battery, vibrate, d.vendorId, d.productId)
    }

fun vibrateController(id: Int) {
    val device = InputDevice.getDevice(id) ?: return
    val effect = VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        device.vibratorManager.vibratorIds.forEach { device.vibratorManager.getVibrator(it).vibrate(effect) }
    } else {
        @Suppress("DEPRECATION") device.vibrator.vibrate(effect)
    }
}

/**
 * Connected controllers with battery and a rumble test. In a stream every controller becomes
 * its own gamepad on the host; player numbers follow the order of the first button press.
 */
/** Connected controllers, updated when one connects, disconnects or changes (battery). */
@Composable
fun rememberControllers(): List<ControllerInfo> {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val im = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { revision++ }
            override fun onInputDeviceRemoved(id: Int) { revision++ }
            override fun onInputDeviceChanged(id: Int) { revision++ }
        }
        im.registerInputDeviceListener(listener, null)
        onDispose { im.unregisterInputDeviceListener(listener) }
    }
    // Battery changes don't always fire a device change: refresh once a minute as well.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            revision++
        }
    }
    return remember(revision) { connectedControllers() }
}

@Composable
fun ControllersScreen() {
    val controllers = rememberControllers()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<ControllerInfo?>(null) }
    // Artemis' gamepad options (dead zone, rumble, motion sensors …) of the base stream settings;
    // they apply to all controllers and to every profile that doesn't override them.
    val store = remember { io.github.thecodingdad.moonlightlauncher.stream.ProfileStore(context, null) }
    val gamepadOptions = remember {
        io.github.thecodingdad.moonlightlauncher.stream.StreamSettingsSchema.load(context)
            .firstOrNull { c -> c.options.any { it.key == "seekbar_deadzone" } }?.options.orEmpty()
    }
    var revision by remember { mutableIntStateOf(0) }

    SettingsPage(stringResource(R.string.settings_controllers)) {
        Text(stringResource(R.string.controllers_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SectionHeader(stringResource(R.string.controllers_connected))
        if (controllers.isEmpty()) Text(stringResource(R.string.controllers_none), color = LauncherTheme.colors.onSurface)
        controllers.forEachIndexed { index, c ->
            NavRow(
                title = c.name,
                onClick = { selected = c },
                subtitle = listOfNotNull(
                    stringResource(if (c.external) R.string.controller_external else R.string.controller_builtin),
                    c.batteryPercent?.let { stringResource(R.string.controller_battery, it) },
                ).joinToString("  ·  "),
                icon = Icons.Filled.SportsEsports,
                initialFocus = index == 0,
            )
        }
        if (gamepadOptions.isNotEmpty()) {
            SectionHeader(stringResource(R.string.controllers_stream_settings))
            Text(stringResource(R.string.controllers_stream_settings_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            gamepadOptions.forEachIndexed { i, option ->
                io.github.thecodingdad.moonlightlauncher.ui.screens.profiles.OptionRow(
                    option, store, revision, initialFocus = controllers.isEmpty() && i == 0,
                ) { revision++ }
            }
        }
    }

    ControllerDetailDialog(selected, onDismiss = { selected = null })
}

@Composable
private fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) {
    io.github.thecodingdad.moonlightlauncher.ui.components.Modal(
        visible = controller != null, onDismiss = onDismiss, title = controller?.name,
        maxWidth = 580.dp,
    ) {
        val c = controller ?: return@Modal
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            InfoRow(
                stringResource(R.string.controller_info_type),
                stringResource(if (c.external) R.string.controller_external else R.string.controller_builtin),
            )
            c.batteryPercent?.let { InfoRow(stringResource(R.string.controller_info_battery), "$it %") }
            InfoRow(stringResource(R.string.controller_info_ids), "%04x:%04x".format(c.vendorId, c.productId))
            InfoRow(stringResource(R.string.controller_info_vibration), stringResource(if (c.canVibrate) R.string.yes else R.string.no))
            Text(
                stringResource(R.string.controller_info_player_order),
                style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
                modifier = androidx.compose.ui.Modifier.padding(top = 6.dp),
            )
            if (c.canVibrate) {
                io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
                    stringResource(R.string.controller_vibrate_test), onClick = { vibrateController(c.id) },
                    icon = androidx.compose.material.icons.Icons.Filled.Vibration, primary = true,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
