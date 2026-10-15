package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
import android.hardware.input.InputManager
import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
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
import io.github.thecodingdad.moonlightlauncher.container
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
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import io.github.thecodingdad.moonlightlauncher.resources.*

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
        val odin = io.github.thecodingdad.moonlightlauncher.system.OdinMapping
        ControllerInfo(
            // AYN's built-in controls report themselves as external.
            d.id, d.name, d.isExternal && !odin.isBuiltIn(d), battery, vibrate, d.vendorId, d.productId, d.descriptor,
            odinCopy = odin.isOdinCopy(d),
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

    SettingsPage(stringResource(Res.string.settings_controllers)) {
        Text(stringResource(Res.string.controllers_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        SectionHeader(stringResource(Res.string.controllers_connected))
        if (controllers.isEmpty()) Text(stringResource(Res.string.controllers_none), color = LauncherTheme.colors.onSurface)
        controllers.forEachIndexed { index, c ->
            NavRow(
                title = c.name,
                onClick = { selected = c },
                subtitle = listOfNotNull(
                    stringResource(if (c.external) Res.string.controller_external else Res.string.controller_builtin),
                    if (c.odinCopy) stringResource(Res.string.controller_odin_copy) else null,
                    c.batteryPercent?.let { stringResource(Res.string.controller_battery, it) },
                ).joinToString("  ·  "),
                icon = Icons.Filled.SportsEsports,
                initialFocus = index == 0,
            )
        }
        OdinMappingSection()
        if (gamepadOptions.isNotEmpty()) {
            SectionHeader(stringResource(Res.string.controllers_stream_settings))
            Text(stringResource(Res.string.controllers_stream_settings_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
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
                io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
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
    val s = all[c.descriptor] ?: io.github.thecodingdad.moonlightlauncher.stream.ControllerSettings()
    fun save(t: (io.github.thecodingdad.moonlightlauncher.stream.ControllerSettings) -> io.github.thecodingdad.moonlightlauncher.stream.ControllerSettings) =
        store.set(c.descriptor, t(s))
    val global = stringResource(Res.string.controller_override_global)
    val on = stringResource(Res.string.ha_on)
    val off = stringResource(Res.string.ha_off)
    val tri = listOf<Boolean?>(null, true, false)
    val triLabel: @Composable (Boolean?) -> String = { v -> when (v) { null -> global; true -> on; false -> off } }

    SectionHeader(stringResource(Res.string.controller_overrides))
    Text(stringResource(Res.string.controller_overrides_sub), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
    ChoiceRow(
        stringResource(Res.string.controller_player), listOf<Int?>(null, 0, 1, 2, 3), s.playerSlot,
        label = { v -> v?.let { stringResource(Res.string.controller_player_n, it + 1) } ?: stringResource(Res.string.controller_player_auto) },
        onSelect = { v -> save { it.copy(playerSlot = v) } },
    )
    ChoiceRow(stringResource(Res.string.controller_flip), tri, s.flipFaceButtons, label = triLabel, onSelect = { v -> save { it.copy(flipFaceButtons = v) } })
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

    // Button combinations of this controller (e.g. Guide on an Xbox pad, Select + Start elsewhere).
    val launcher by LocalContext.current.container.settings.settings.collectAsState(null)
    val globalHome = launcher?.comboHome.orEmpty()
    val globalMenu = launcher?.comboQuickMenu.orEmpty()
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.thecodingdad.moonlightlauncher.input.ComboAction?>(null) }
    val home = s.comboHome?.toSet()
    val menu = s.comboQuickMenu?.toSet()
    NavRow(
        stringResource(Res.string.combo_home), { editing = io.github.thecodingdad.moonlightlauncher.input.ComboAction.HOME },
        trailing = if (home == null) comboLabel(globalHome, global = true) else comboLabel(home),
    )
    NavRow(
        stringResource(Res.string.combo_quick_menu), { editing = io.github.thecodingdad.moonlightlauncher.input.ComboAction.QUICK_MENU },
        trailing = if (menu == null) comboLabel(globalMenu, global = true) else comboLabel(menu),
    )
    val action = editing
    val isHome = action == io.github.thecodingdad.moonlightlauncher.input.ComboAction.HOME
    ComboDialog(
        action = action,
        current = if (isHome) home else menu,
        other = (if (isHome) menu ?: globalMenu else home ?: globalHome),
        global = if (isHome) globalHome else globalMenu,
        descriptor = c.descriptor,
        onSave = { keys ->
            val list = keys?.toList()
            save { if (isHome) it.copy(comboHome = list) else it.copy(comboQuickMenu = list) }
        },
        onDismiss = { editing = null },
    )
}

/**
 * AYN Odin only: keep Odin Mapping from taking over this controller (it otherwise replaces the
 * first external pad with a copy without battery and rumble). Matched by name by Odin.
 */
@Composable
private fun OdinMappingRow(c: ControllerInfo) {
    val context = LocalContext.current
    val odin = remember { io.github.thecodingdad.moonlightlauncher.system.OdinMapping(context.applicationContext) }
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
    val odin = remember { io.github.thecodingdad.moonlightlauncher.system.OdinMapping(context.applicationContext) }
    if (!remember { odin.isAvailable }) return
    val repo = context.container.settings
    val settings by repo.settings.collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var blacklist by remember { mutableStateOf<List<String>?>(null) }
    // Re-read when controllers change: the watcher may just have added a name.
    val controllers = rememberControllers()
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
        NavRow(
            name, {
                scope.launch {
                    odin.setBlacklisted(name, false)
                    blacklist = odin.blacklist()
                }
            },
            subtitle = stringResource(if (s.odinKeepControllers) Res.string.odin_mapping_remove_auto else Res.string.odin_mapping_remove),
            icon = androidx.compose.material.icons.Icons.Filled.Delete,
        )
    }
}
