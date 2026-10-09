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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

data class ControllerInfo(val id: Int, val name: String, val external: Boolean, val batteryPercent: Int?, val canVibrate: Boolean)

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
        ControllerInfo(d.id, d.name, d.isExternal, battery, vibrate)
    }

private fun vibrate(id: Int) {
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
@Composable
fun ControllersScreen() {
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
    val controllers = remember(revision) { connectedControllers() }

    SettingsPage(stringResource(R.string.settings_controllers)) {
        Text(stringResource(R.string.controllers_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        if (controllers.isEmpty()) Text(stringResource(R.string.controllers_none), color = LauncherTheme.colors.onSurface)
        controllers.forEachIndexed { index, c ->
            NavRow(
                title = c.name,
                onClick = { if (c.canVibrate) vibrate(c.id) },
                subtitle = listOfNotNull(
                    stringResource(if (c.external) R.string.controller_external else R.string.controller_builtin),
                    c.batteryPercent?.let { stringResource(R.string.controller_battery, it) },
                    if (c.canVibrate) stringResource(R.string.controller_vibrate_test) else null,
                ).joinToString("  ·  "),
                icon = Icons.Filled.SportsEsports,
                initialFocus = index == 0,
            )
        }
    }
}
