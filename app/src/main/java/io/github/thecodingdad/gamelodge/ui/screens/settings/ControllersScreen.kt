package io.github.thecodingdad.gamelodge.ui.screens.settings

import androidx.compose.material.icons.filled.Gamepad
import io.github.thecodingdad.gamelodge.ui.components.popupHeightIn
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import android.hardware.input.InputManager
import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import io.github.thecodingdad.gamelodge.container
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

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
        val odin = io.github.thecodingdad.gamelodge.system.OdinMapping
        ControllerInfo(
            // AYN's built-in controls report themselves as external.
            d.id, d.name, d.isExternal && !odin.isBuiltIn(d), battery, vibrate, d.vendorId, d.productId, d.descriptor,
            odinCopy = odin.isOdinCopy(d),
            hasTouchpad = (d.sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD ||
                d.vendorId == 0x054c, // Sony pads report their touchpad as a separate device
        )
    }

fun vibrateAndroidController(id: Int) {
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
fun rememberAndroidControllers(): List<ControllerInfo> {
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
    // The user's name and colour for each controller (kept per device descriptor).
    val overrides by context.container.controllerSettings.all.collectAsState()
    return remember(revision, overrides) {
        connectedControllers().map { c ->
            val s = overrides[c.descriptor]
            if (s?.name != null || s?.colorArgb != null || s?.iconId != null) c.copy(customName = s.name, colorArgb = s.colorArgb, iconId = s.iconId) else c
        }
    }
}

@Composable
fun ControllersScreen() {
    val controllers = rememberAndroidControllers()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<ControllerInfo?>(null) }

    SettingsPage(stringResource(Res.string.settings_controllers)) {
        Text(stringResource(Res.string.controllers_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SectionHeader(stringResource(Res.string.controllers_connected))
        if (controllers.isEmpty()) Text(stringResource(Res.string.controllers_none), color = LauncherTheme.colors.onSurface)
        controllers.forEachIndexed { index, c ->
            NavRow(
                title = c.displayName,
                onClick = { selected = c },
                subtitle = listOfNotNull(
                    stringResource(if (c.external) Res.string.controller_external else Res.string.controller_builtin),
                    if (c.odinCopy) stringResource(Res.string.controller_odin_copy) else null,
                    c.batteryPercent?.let { stringResource(Res.string.controller_battery, it) },
                ).joinToString("  ·  "),
                icon = c.displayIcon,
                initialFocus = index == 0,
                iconTint = c.colorArgb?.let { androidx.compose.ui.graphics.Color(it) },
            )
        }
        // Launcher controls every controller uses unless its own settings say otherwise.
        SectionHeader(stringResource(Res.string.controllers_defaults))
        Text(stringResource(Res.string.controllers_defaults_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        ControllerSectionRows(ANDROID_CONTROLLER_SECTIONS)
        SeeAlso(
            SeeAlsoLink(stringResource(Res.string.settings_sounds), SettingsTarget.Page(SettingsPageId.SOUNDS)),
            SeeAlsoLink(stringResource(Res.string.settings_profiles), SettingsTarget.Page(SettingsPageId.PROFILES)),
        )
    }

    AndroidControllerDetailDialog(selected, onDismiss = { selected = null })
}

@Composable
fun AndroidControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) {
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = controller != null, onDismiss = onDismiss, title = controller?.displayName,
        maxWidth = 680.dp,
    ) {
        val c = controller ?: return@Modal
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            ControllerOverrideRows(c)
            Text(
                stringResource(Res.string.controller_info_player_order),
                style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
                modifier = androidx.compose.ui.Modifier.padding(top = 6.dp),
            )
            if (c.external) OdinMappingRow(c)
            SectionHeader(stringResource(Res.string.controller_device_info))
            InfoRow(
                stringResource(Res.string.controller_info_type),
                listOfNotNull(
                    stringResource(if (c.external) Res.string.controller_external else Res.string.controller_builtin),
                    if (c.odinCopy) stringResource(Res.string.controller_odin_copy) else null,
                ).joinToString(" · "),
            )
            c.batteryPercent?.let { InfoRow(stringResource(Res.string.controller_info_battery), "$it %") }
            InfoRow(stringResource(Res.string.controller_info_ids), "%04x:%04x".format(c.vendorId, c.productId))
            InfoRow(stringResource(Res.string.controller_info_vibration), stringResource(if (c.canVibrate) Res.string.yes else Res.string.no))
            if (c.canVibrate) {
                io.github.thecodingdad.gamelodge.ui.components.LauncherButton(
                    stringResource(Res.string.controller_vibrate_test), onClick = { vibrateController(c.id) },
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
    val s = all[c.descriptor] ?: io.github.thecodingdad.gamelodge.stream.ControllerSettings()
    fun save(t: (io.github.thecodingdad.gamelodge.stream.ControllerSettings) -> io.github.thecodingdad.gamelodge.stream.ControllerSettings) =
        store.set(c.descriptor, t(s))
    val global = stringResource(Res.string.controller_override_global)
    val on = stringResource(Res.string.ha_on)
    val off = stringResource(Res.string.ha_off)
    val tri = listOf<Boolean?>(null, true, false)
    val triLabel: @Composable (Boolean?) -> String = { v -> when (v) { null -> global; true -> on; false -> off } }

    // Name and highlight colour, so this controller is easy to tell apart everywhere it appears.
    SectionHeader(stringResource(Res.string.controller_identity_section))
    ControllerIdentityRows(
        deviceName = c.name,
        customName = s.name,
        colorArgb = s.colorArgb,
        iconId = s.iconId,
        vendorId = c.vendorId,
        onName = { v -> save { it.copy(name = v) } },
        onColor = { v -> save { it.copy(colorArgb = v) } },
        onIcon = { v -> save { it.copy(iconId = v) } },
    )

    val launcherSettings by LocalContext.current.container.settings.settings.collectAsState(null)
    // In the launcher: layout and button combinations of this controller.
    val settingsRepo = LocalContext.current.container.settings
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SectionHeader(stringResource(Res.string.controller_launcher_section))
    launcherSettings?.let { ls ->
        ControllerLayoutOverrideRow(c, ls) { t -> scope.launch { settingsRepo.update(t) } }
    }
    // Button combinations of this controller (e.g. Guide on an Xbox pad, Select + Start elsewhere).
    launcherSettings?.let { ls ->
        ControllerComboRows(
            global = ls,
            combo = { a -> s.combo(a)?.toSet() },
            forward = s.comboForward,
            descriptor = c.descriptor,
            onCombo = { a, keys -> save { it.withCombo(a, keys?.toList()) } },
            onForward = { v -> save { it.copy(comboForward = v) } },
        )
    }

    SectionHeader(stringResource(Res.string.controller_stream_section))
    Text(stringResource(Res.string.controller_overrides_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    ChoiceRow(
        stringResource(Res.string.controller_auto_attach), listOf<Boolean?>(null, false), s.autoAttach?.takeIf { !it },
        label = { v -> if (v == false) stringResource(Res.string.controller_auto_attach_off) else stringResource(Res.string.controller_auto_attach_auto) },
        onSelect = { v -> save { it.copy(autoAttach = v) } },
    )
    ChoiceRow(
        stringResource(Res.string.controller_deadzone), listOf<Int?>(null, 0, 3, 5, 8, 10, 15, 20), s.deadzonePercent,
        label = { v -> v?.let { "$it %" } ?: global },
        onSelect = { v -> save { it.copy(deadzonePercent = v) } },
    )
    if (c.canVibrate) {
        ChoiceRow(
            stringResource(Res.string.controller_rumble), listOf<Int?>(null, 0, 25, 50, 75, 100), s.rumblePercent,
            label = { v -> when (v) { null -> global; 0 -> off; else -> "$v %" } },
            onSelect = { v -> save { it.copy(rumblePercent = v) } },
        )
    }
    ChoiceRow(stringResource(Res.string.controller_motion), tri, s.motionSensors, label = triLabel, onSelect = { v -> save { it.copy(motionSensors = v) } })
    if (c.hasTouchpad) {
        ChoiceRow(stringResource(Res.string.controller_touchpad_mouse), tri, s.touchpadAsMouse, label = triLabel, onSelect = { v -> save { it.copy(touchpadAsMouse = v) } })
    }

}

/**
 * AYN Odin only: keep Odin Mapping from taking over this controller (it otherwise replaces the
 * first external pad with a copy without battery and rumble). Matched by name by Odin.
 */
@Composable
private fun OdinMappingRow(c: ControllerInfo) {
    val context = LocalContext.current
    val odin = remember { io.github.thecodingdad.gamelodge.system.OdinMapping(context.applicationContext) }
    if (!remember { odin.isAvailable }) return
    var blacklist by remember { mutableStateOf<List<String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { blacklist = odin.blacklist() }
    val list = blacklist ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SectionHeader(stringResource(Res.string.odin_mapping_section))
    SwitchRow(
        stringResource(Res.string.odin_mapping_keep),
        c.name in list,
        { on ->
            scope.launch {
                odin.setBlacklisted(c.name, on)
                blacklist = odin.blacklist()
            }
        },
        subtitle = stringResource(Res.string.odin_mapping_keep_sub, c.name),
    )
}

/** AYN Odin only: keep all external controllers away from Odin Mapping, plus its name list. */
@Composable
private fun OdinMappingSection() {
    val context = LocalContext.current
    val odin = remember { io.github.thecodingdad.gamelodge.system.OdinMapping(context.applicationContext) }
    if (!remember { odin.isAvailable }) return
    val repo = context.container.settings
    val settings by repo.settings.collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var blacklist by remember { mutableStateOf<List<String>?>(null) }
    // Re-read when controllers change: the watcher may just have added a name.
    val controllers = rememberAndroidControllers()
    androidx.compose.runtime.LaunchedEffect(controllers, settings?.odinKeepControllers) {
        kotlinx.coroutines.delay(500)
        blacklist = odin.blacklist()
    }
    val s = settings ?: return
    SectionHeader(stringResource(Res.string.odin_mapping_section))
    SwitchRow(
        stringResource(Res.string.odin_mapping_all), s.odinKeepControllers,
        { on -> scope.launch { repo.update { it.copy(odinKeepControllers = on) } } },
        subtitle = stringResource(Res.string.odin_mapping_all_sub),
    )
    blacklist?.forEach { name ->
        RemovableRow(
            name,
            stringResource(if (s.odinKeepControllers) Res.string.odin_mapping_remove_auto else Res.string.odin_mapping_remove),
            androidx.compose.material.icons.Icons.Filled.Gamepad,
            onRemove = {
                scope.launch {
                    odin.setBlacklisted(name, false)
                    blacklist = odin.blacklist()
                }
            },
        )
    }
}

/** The controller settings' pages on Android. */
val ANDROID_CONTROLLER_SECTIONS = listOf(ControllerSection.LAYOUT, ControllerSection.COMBOS, ControllerSection.MOUSE, ControllerSection.STREAM)

/** One page of the controller settings (Settings › Controllers › …). */
@Composable
fun ControllerSectionScreen(section: ControllerSection) {
    val context = LocalContext.current
    val settingsRepo = context.container.settings
    val settings by settingsRepo.settings.collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val update: ((io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> Unit =
        { t -> scope.launch { settingsRepo.update(t) } }
    val title = when (section) {
        ControllerSection.LAYOUT -> Res.string.controller_section_layout
        ControllerSection.COMBOS -> Res.string.combo_section
        ControllerSection.MOUSE -> Res.string.controller_section_mouse
        ControllerSection.STREAM -> Res.string.controller_section_stream
    }
    SettingsPage(stringResource(title)) {
        when (section) {
            ControllerSection.LAYOUT -> {
                ControllerLayoutDefaultRow(settings, update, initialFocus = true)
                HintBarRow(settings, update)
            }
            ControllerSection.COMBOS -> ButtonComboRows(settings, update)
            ControllerSection.MOUSE -> MouseSettingsRows(settings, update)
            ControllerSection.STREAM -> {
                // The stream's controller options, the same for every profile (single controllers and
                // single games can still differ in their own settings).
                ControllerAttachRow(settings, update)
                Text(stringResource(Res.string.controllers_stream_defaults_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
                val editing = remember { io.github.thecodingdad.gamelodge.stream.AndroidProfileEditing(context) }
                val base = remember { io.github.thecodingdad.gamelodge.stream.ProfileStore(context, null) }
                val options = remember { io.github.thecodingdad.gamelodge.stream.StreamSettingsSchema.gamepadOptions(context) }
                var revision by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                options.forEachIndexed { i, option ->
                    io.github.thecodingdad.gamelodge.ui.screens.profiles.OptionRow(option, base, revision, i == 0, editing) { revision++ }
                }
                OdinMappingSection()
            }
        }
    }
}
