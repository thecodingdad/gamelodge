package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import io.github.thecodingdad.moonlightlauncher.container
import androidx.compose.runtime.collectAsState
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
    val descriptor: String = "",
    val hasTouchpad: Boolean = false,
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
        ControllerInfo(
            d.id, d.name, d.isExternal, battery, vibrate, d.vendorId, d.productId, d.descriptor,
            hasTouchpad = (d.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD ||
                d.vendorId == 0x054c, // Sony pads report their touchpad as a separate device
        )
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
fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) {
    io.github.thecodingdad.moonlightlauncher.ui.components.Modal(
        visible = controller != null, onDismiss = onDismiss, title = controller?.name,
        maxWidth = 680.dp,
    ) {
        val c = controller ?: return@Modal
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.heightIn(max = 560.dp).controllerScroll(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            InfoRow(
                stringResource(R.string.controller_info_type),
                stringResource(if (c.external) R.string.controller_external else R.string.controller_builtin),
            )
            c.batteryPercent?.let { InfoRow(stringResource(R.string.controller_info_battery), "$it %") }
            InfoRow(stringResource(R.string.controller_info_ids), "%04x:%04x".format(c.vendorId, c.productId))
            InfoRow(stringResource(R.string.controller_info_vibration), stringResource(if (c.canVibrate) R.string.yes else R.string.no))
            ControllerOverrideRows(c)
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

/** Settings of this controller that differ from the global ones; "global" keeps Artemis' value. */
@Composable
private fun ControllerOverrideRows(c: ControllerInfo) {
    if (c.descriptor.isEmpty()) return
    val store = LocalContext.current.container.controllerSettings
    val all by store.all.collectAsState()
    val s = all[c.descriptor] ?: io.github.thecodingdad.moonlightlauncher.stream.ControllerSettings()
    fun save(t: (io.github.thecodingdad.moonlightlauncher.stream.ControllerSettings) -> io.github.thecodingdad.moonlightlauncher.stream.ControllerSettings) =
        store.set(c.descriptor, t(s))
    val global = stringResource(R.string.controller_override_global)
    val on = stringResource(R.string.ha_on)
    val off = stringResource(R.string.ha_off)
    val tri = listOf<Boolean?>(null, true, false)
    val triLabel: @Composable (Boolean?) -> String = { v -> when (v) { null -> global; true -> on; false -> off } }

    SectionHeader(stringResource(R.string.controller_overrides))
    Text(stringResource(R.string.controller_overrides_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    ChoiceRow(
        stringResource(R.string.controller_player), listOf<Int?>(null, 0, 1, 2, 3), s.playerSlot,
        label = { v -> v?.let { stringResource(R.string.controller_player_n, it + 1) } ?: stringResource(R.string.controller_player_auto) },
        onSelect = { v -> save { it.copy(playerSlot = v) } },
    )
    ChoiceRow(stringResource(R.string.controller_flip), tri, s.flipFaceButtons, label = triLabel, onSelect = { v -> save { it.copy(flipFaceButtons = v) } })
    ChoiceRow(
        stringResource(R.string.controller_deadzone), listOf<Int?>(null, 0, 3, 5, 8, 10, 15, 20), s.deadzonePercent,
        label = { v -> v?.let { "$it %" } ?: global },
        onSelect = { v -> save { it.copy(deadzonePercent = v) } },
    )
    if (c.canVibrate) {
        ChoiceRow(
            stringResource(R.string.controller_rumble), listOf<Int?>(null, 0, 25, 50, 75, 100), s.rumblePercent,
            label = { v -> when (v) { null -> global; 0 -> off; else -> "$v %" } },
            onSelect = { v -> save { it.copy(rumblePercent = v) } },
        )
    }
    ChoiceRow(stringResource(R.string.controller_motion), tri, s.motionSensors, label = triLabel, onSelect = { v -> save { it.copy(motionSensors = v) } })
    if (c.hasTouchpad) {
        ChoiceRow(stringResource(R.string.controller_touchpad_mouse), tri, s.touchpadAsMouse, label = triLabel, onSelect = { v -> save { it.copy(touchpadAsMouse = v) } })
    }
}
