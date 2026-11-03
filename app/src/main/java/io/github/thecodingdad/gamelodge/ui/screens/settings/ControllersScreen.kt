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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
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
    // Enumerate the devices off the main thread: querying a controller's battery goes over Bluetooth
    // and can block for seconds (Joy-Cons), which would hang the UI. Re-run only when devices change;
    // the user's name/colour/icon are re-applied cheaply below without re-querying every device.
    val base by androidx.compose.runtime.produceState(emptyList<ControllerInfo>(), revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { connectedControllers() }
    }
    val overrides by context.container.controllerSettings.all.collectAsState()
    return remember(base, overrides) {
        base.map { c ->
            val s = overrides[c.descriptor]
            if (s?.name != null || s?.colorArgb != null || s?.iconId != null) c.copy(customName = s.name, colorArgb = s.colorArgb, iconId = s.iconId) else c
        }
    }
}

/** The controller in use (the last one pressed), else the first connected one; null without any. */
@Composable
fun rememberActiveController(): ControllerInfo? {
    val controllers = rememberAndroidControllers().filter { it.descriptor.isNotEmpty() }
    return controllers.firstOrNull { it.key == ActivePad.key } ?: controllers.firstOrNull()
}

/**
 * The button combinations of the controller in use (quick menu settings), titled with its name;
 * without a controller only a hint (combinations belong to a controller).
 */
@Composable
fun ActiveControllerComboRows(actions: List<io.github.thecodingdad.gamelodge.input.ComboAction>, firstFocus: Boolean = false) {
    val pad = rememberActiveController()
    if (pad == null) {
        Text(stringResource(Res.string.controller_combos_none), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        return
    }
    val store = LocalContext.current.container.controllerSettings
    val all by store.all.collectAsState()
    val s = all[pad.descriptor] ?: io.github.thecodingdad.gamelodge.stream.ControllerSettings()
    SectionHeader(stringResource(Res.string.controller_combos_of, pad.displayName))
    ControllerComboRows(
        combo = { a -> s.combo(a)?.toSet() },
        forward = s.comboForward,
        descriptor = pad.descriptor,
        onCombo = { a, keys -> store.set(pad.descriptor, s.withCombo(a, keys?.toList())) },
        onForward = { v -> store.set(pad.descriptor, s.copy(comboForward = v)) },
        actions = actions,
        firstFocus = firstFocus,
        subtitles = true,
    )
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
        // What applies to every controller (layout, combinations … are each controller's own).
        SectionHeader(stringResource(Res.string.controllers_defaults))
        Text(stringResource(Res.string.controllers_defaults_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
        val settingsRepo = context.container.settings
        val settings by settingsRepo.settings.collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val update: ((io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> io.github.thecodingdad.gamelodge.data.settings.LauncherSettings) -> Unit =
            { t -> scope.launch { settingsRepo.update(t) } }
        HintBarRow(settings, update)
        ComboServiceRows()
        ControllerAttachRow(settings, update)
        // Artemis' remaining gamepad option (its own USB driver for Xbox pads), the same for every profile.
        val editing = remember { io.github.thecodingdad.gamelodge.stream.AndroidProfileEditing(context) }
        val base = remember { io.github.thecodingdad.gamelodge.stream.ProfileStore(context, null) }
        val options = remember { io.github.thecodingdad.gamelodge.stream.StreamSettingsSchema.gamepadOptions(context) }
        var revision by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        options.forEach { option ->
            io.github.thecodingdad.gamelodge.ui.screens.profiles.OptionRow(option, base, revision, false, editing) { revision++ }
        }
        OdinMappingRows()
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

/**
 * All settings of this controller. Every controller setting is per controller; "Standard" is the
 * default for this kind of controller ([controllerDefaults]).
 */
@Composable
private fun ControllerOverrideRows(c: ControllerInfo) {
    if (c.descriptor.isEmpty()) return
    val store = LocalContext.current.container.controllerSettings
    val all by store.all.collectAsState()
    val s = all[c.descriptor] ?: io.github.thecodingdad.gamelodge.stream.ControllerSettings()
    fun save(t: (io.github.thecodingdad.gamelodge.stream.ControllerSettings) -> io.github.thecodingdad.gamelodge.stream.ControllerSettings) =
        store.set(c.descriptor, t(s))
    val d = remember(c.vendorId, c.productId) { c.defaults }
    val default: @Composable (String) -> String = { stringResource(Res.string.controller_default_value, it) }
    val on = stringResource(Res.string.ha_on)
    val off = stringResource(Res.string.ha_off)
    val tri = listOf<Boolean?>(null, true, false)

    Text(stringResource(Res.string.controller_overrides_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    // Name, icon and its colour, so this controller is easy to tell apart everywhere it appears.
    SectionHeader(stringResource(Res.string.controller_identity_section))
    ControllerIdentityRows(
        deviceName = c.name,
        customName = s.name,
        colorArgb = s.colorArgb,
        iconId = s.iconId,
        vendorId = c.vendorId,
        productId = c.productId,
        onName = { v -> save { it.copy(name = v) } },
        onColor = { v -> save { it.copy(colorArgb = v) } },
        onIcon = { v -> save { it.copy(iconId = v) } },
    )

    // Player LEDs (controllers that have them, e.g. Joy-Cons): automatic (the player number) or by hand.
    val ledContext = LocalContext.current
    val lights = remember { io.github.thecodingdad.gamelodge.stream.ControllerLights(ledContext, ledContext.container.controllerSettings) }
    if (remember(c.id) { lights.hasPlayerLights(c.id) }) {
        SectionHeader(stringResource(Res.string.controller_led_section))
        val ledAuto = s.ledPattern == null
        SwitchRow(
            stringResource(Res.string.controller_led_auto), ledAuto,
            { on ->
                if (on) {
                    save { it.copy(ledPattern = null) }
                } else {
                    val slot = io.github.thecodingdad.gamelodge.stream.ControllerOrderStore(ledContext).assign(null) { true }.slots[c.descriptor] ?: 0
                    save { it.copy(ledPattern = if (slot in 0..3) 1 shl slot else 1) }
                }
            },
            subtitle = stringResource(Res.string.controller_led_auto_sub),
        )
        if (!ledAuto) {
            ControllerLedRow(s.ledPattern ?: 0) { p -> save { it.copy(ledPattern = p) } }
        }
        if (remember(c.id) { lights.hasHomeLight(c.id) }) {
            SwitchRow(
                stringResource(Res.string.controller_led_home), s.homeLed,
                { on -> save { it.copy(homeLed = on) } },
                subtitle = stringResource(Res.string.controller_led_home_sub),
            )
        }
    }

    val launcherSettings by LocalContext.current.container.settings.settings.collectAsState(null)
    val settingsRepo = LocalContext.current.container.settings
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Buttons: layout (also swaps them for the host) and this controller's combinations.
    SectionHeader(stringResource(Res.string.controller_section_buttons))
    launcherSettings?.let { ls ->
        ControllerLayoutOverrideRow(c, ls, update = { t -> scope.launch { settingsRepo.update(t) } })
        ControllerComboRows(
            combo = { a -> s.combo(a)?.toSet() },
            forward = s.comboForward,
            descriptor = c.descriptor,
            onCombo = { a, keys -> save { it.withCombo(a, keys?.toList()) } },
            onForward = { v -> save { it.copy(comboForward = v) } },
        )
    }

    // Sticks (and the touchpad of controllers that have one).
    SectionHeader(stringResource(if (c.hasTouchpad) Res.string.controller_section_sticks_touchpad else Res.string.controller_section_sticks))
    ChoiceRow(
        stringResource(Res.string.controller_deadzone), listOf<Int?>(null, 0, 3, 5, 8, 10, 15, 20), s.deadzonePercent,
        label = { v -> v?.let { "$it %" } ?: default("${d.deadzonePercent} %") },
        onSelect = { v -> save { it.copy(deadzonePercent = v) } },
    )
    if (c.hasTouchpad) {
        ChoiceRow(
            stringResource(Res.string.controller_touchpad_mouse), tri, s.touchpadAsMouse,
            label = { v -> when (v) { null -> default(if (d.touchpadAsMouse) on else off); true -> on; false -> off } },
            onSelect = { v -> save { it.copy(touchpadAsMouse = v) } },
        )
    }

    // Where the rumble goes and how strong it is (the controller's motors and the device's), and motion.
    SectionHeader(stringResource(Res.string.controller_section_feedback))
    ChoiceRow(
        stringResource(Res.string.controller_rumble), listOf<VibrationMode?>(null) + VibrationMode.entries, s.vibration,
        label = { v -> v?.let { vibrationLabel(it) } ?: default(vibrationLabel(d.vibration)) },
        onSelect = { v -> save { it.copy(vibration = v) } },
    )
    if ((s.vibration ?: d.vibration) != VibrationMode.OFF) {
        ChoiceRow(
            stringResource(Res.string.controller_vibration_strength), listOf<Int?>(null, 25, 50, 75, 100), s.rumblePercent,
            label = { v -> v?.let { "$it %" } ?: default("${d.vibrationStrength} %") },
            onSelect = { v -> save { it.copy(rumblePercent = v) } },
        )
    }
    ChoiceRow(
        stringResource(Res.string.controller_motion), listOf<MotionMode?>(null) + MotionMode.entries, s.motion,
        label = { v -> v?.let { motionLabel(it) } ?: default(motionLabel(d.motion)) },
        onSelect = { v -> save { it.copy(motion = v) } },
    )

    // Connection to the host: when it attaches, battery report, player order, Odin Mapping.
    SectionHeader(stringResource(Res.string.controller_section_connection))
    ChoiceRow(
        stringResource(Res.string.controller_auto_attach), listOf<Boolean?>(null, false), s.autoAttach?.takeIf { !it },
        label = { v -> if (v == false) stringResource(Res.string.controller_auto_attach_off) else stringResource(Res.string.controller_auto_attach_auto) },
        onSelect = { v -> save { it.copy(autoAttach = v) } },
    )
    ChoiceRow(
        stringResource(Res.string.controller_battery_report), tri, s.batteryReport,
        label = { v -> when (v) { null -> default(if (d.batteryReport) on else off); true -> on; false -> off } },
        onSelect = { v -> save { it.copy(batteryReport = v) } },
        subtitle = stringResource(Res.string.controller_battery_report_sub),
    )
    if (c.external) OdinMappingRow(c)
    Text(
        stringResource(Res.string.controller_info_player_order),
        style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
        modifier = androidx.compose.ui.Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun vibrationLabel(m: VibrationMode) = stringResource(
    when (m) {
        VibrationMode.OFF -> Res.string.controller_source_off
        VibrationMode.CONTROLLER -> Res.string.controller_source_controller
        VibrationMode.CONTROLLER_ELSE_DEVICE -> Res.string.controller_source_controller_else_device
        VibrationMode.DEVICE -> Res.string.controller_source_device
    },
)

@Composable
private fun motionLabel(m: MotionMode) = stringResource(
    when (m) {
        MotionMode.OFF -> Res.string.controller_source_off
        MotionMode.CONTROLLER -> Res.string.controller_source_controller
        MotionMode.CONTROLLER_ELSE_DEVICE -> Res.string.controller_source_controller_else_device
    },
)

/** One row with four tappable squares, one per player LED; filled = on. */
@Composable
private fun ControllerLedRow(pattern: Int, onChange: (Int) -> Unit) {
    val colors = LauncherTheme.colors
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 68.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(LauncherTheme.dimens.cornerRadius))
            .background(io.github.thecodingdad.gamelodge.ui.components.LocalRowSurface.current ?: colors.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.controller_led_lights), style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface, modifier = androidx.compose.ui.Modifier.weight(1f),
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            (0..3).forEach { i ->
                val on = (pattern shr i) and 1 == 1
                io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                    onClick = { onChange(pattern xor (1 shl i)) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
                    color = if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent,
                    focusScale = 1.12f,
                    modifier = androidx.compose.ui.Modifier.size(36.dp)
                        .border(2.dp, if (on) colors.accent else colors.onSurfaceMuted, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                ) {
                    Text(
                        "${i + 1}", style = MaterialTheme.typography.labelMedium,
                        color = if (on) colors.onAccent else colors.onSurfaceMuted,
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center),
                    )
                }
            }
        }
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

/**
 * AYN Odin only: keep all external controllers away from Odin Mapping. The row's switch turns it
 * on; the row opens the list of excluded names (each removable).
 */
@Composable
private fun OdinMappingRows() {
    val context = LocalContext.current
    val odin = remember { io.github.thecodingdad.gamelodge.system.OdinMapping(context.applicationContext) }
    if (!remember { odin.isAvailable }) return
    val repo = context.container.settings
    val settings by repo.settings.collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var blacklist by remember { mutableStateOf<List<String>?>(null) }
    var listShown by remember { mutableStateOf(false) }
    // Re-read when controllers change: the watcher may just have added a name.
    val controllers = rememberAndroidControllers()
    androidx.compose.runtime.LaunchedEffect(controllers, settings?.odinKeepControllers, listShown) {
        kotlinx.coroutines.delay(500)
        blacklist = odin.blacklist()
    }
    val s = settings ?: return
    SwitchNavRow(
        stringResource(Res.string.odin_mapping_all), s.odinKeepControllers,
        { on -> scope.launch { repo.update { it.copy(odinKeepControllers = on) } } },
        onClick = { listShown = true },
        subtitle = stringResource(Res.string.odin_mapping_all_row_sub),
    )
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = listShown, onDismiss = { listShown = false }, title = stringResource(Res.string.odin_mapping_list_title), maxWidth = 760.dp,
    ) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.weight(1f, fill = false).popupHeightIn(560.dp).controllerScroll(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.odin_mapping_all_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            val names = blacklist.orEmpty()
            if (names.isEmpty()) Text(stringResource(Res.string.odin_mapping_list_empty), color = LauncherTheme.colors.onSurface)
            names.forEach { name ->
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
    }
}

/** The controller settings' pages on Android. */
val ANDROID_CONTROLLER_SECTIONS = listOf(ControllerSection.MOUSE)

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
        ControllerSection.MOUSE -> Res.string.controller_section_mouse
    }
    SettingsPage(stringResource(title)) {
        when (section) {
            ControllerSection.MOUSE -> MouseSettingsRows(settings, update)
        }
    }
}
