package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SliderRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** What the PC offers right now (null = no such control). */
private data class DeviceState(
    val volume: Int?,
    val brightness: Int?,
    val outputs: List<WindowsDeviceControls.AudioOutput>,
    val output: String?,
)

private suspend fun readDeviceState() = withContext(Dispatchers.IO) {
    DeviceState(
        WindowsDeviceControls.volume()?.let { (it * 100).toInt() },
        WindowsDeviceControls.brightness(),
        WindowsDeviceControls.outputs(),
        WindowsDeviceControls.defaultOutput(),
    )
}

/** Volume, brightness and audio output of the PC in the quick access tab; follows changes made elsewhere. */
@Composable
fun DesktopSystemControls(visible: Boolean) {
    val settings by LocalContainer.current.settings.settings.collectAsState(LauncherSettings())
    var state by remember { mutableStateOf<DeviceState?>(null) }
    var volume by remember { mutableStateOf<Int?>(null) }
    var brightness by remember { mutableStateOf<Int?>(null) }
    // A value just set by hand isn't overwritten by a read that started before it took effect.
    var changedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(visible) {
        while (visible) {
            val read = readDeviceState()
            state = read
            if (System.currentTimeMillis() - changedAt > 2_000) {
                volume = read.volume
                brightness = read.brightness
            }
            delay(2_000)
        }
    }
    // Slider steps faster than Windows takes them: only the latest value is sent.
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) { snapshotFlow { volume }.filterNotNull().conflate().collect { WindowsDeviceControls.setVolume(it / 100f) } }
        launch(Dispatchers.IO) { snapshotFlow { brightness }.filterNotNull().conflate().collect { WindowsDeviceControls.setBrightness(it) } }
    }
    val current = state ?: return

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        volume?.takeIf { settings.quickShowVolume }?.let { v ->
            SliderRow(
                stringResource(Res.string.qm_volume), v, 0..100, 2,
                onChange = { volume = it; changedAt = System.currentTimeMillis() },
                valueLabel = { "$it %" }, icon = Icons.Filled.VolumeUp,
            )
        }
        brightness?.takeIf { settings.quickShowBrightness }?.let { b ->
            SliderRow(
                stringResource(Res.string.qm_brightness), b, 0..100, 5,
                onChange = { brightness = it; changedAt = System.currentTimeMillis() },
                valueLabel = { "$it %" }, icon = Icons.Filled.BrightnessMedium,
            )
        }
        if (settings.quickShowAudioOutput && current.outputs.size > 1) {
            val ids = current.outputs.map { it.id }
            val names = current.outputs.associate { it.id to it.name }
            ChoiceRow(
                stringResource(Res.string.qm_audio_output), ids, current.output ?: ids.first(),
                label = { names[it] ?: it },
                onSelect = { id ->
                    state = current.copy(output = id)
                    kotlin.concurrent.thread(name = "audio-output") { WindowsDeviceControls.setDefaultOutput(id) }
                },
                icon = Icons.Filled.Speaker,
            )
        }
    }
}

/** Which device controls the quick access tab shows (the PC has no tiles to arrange). */
@Composable
fun DesktopQuickAccessEditor(visible: Boolean, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by c.settings.settings.collectAsState(LauncherSettings())
    fun update(transform: (LauncherSettings) -> LauncherSettings) { scope.launch { c.settings.update(transform) } }
    var canDim by remember { mutableStateOf(true) }
    LaunchedEffect(visible) { if (visible) canDim = withContext(Dispatchers.IO) { WindowsDeviceControls.brightness() != null } }
    Modal(visible = visible, onDismiss = onDismiss, title = stringResource(Res.string.qm_edit)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.qm_show_section))
            SwitchRow(
                stringResource(Res.string.qm_volume), settings.quickShowVolume, { on -> update { it.copy(quickShowVolume = on) } },
                icon = Icons.Filled.VolumeUp, initialFocus = true,
            )
            SwitchRow(
                stringResource(Res.string.qm_brightness), settings.quickShowBrightness, { on -> update { it.copy(quickShowBrightness = on) } },
                subtitle = if (canDim) null else stringResource(Res.string.qm_brightness_unsupported), icon = Icons.Filled.BrightnessMedium,
            )
            SwitchRow(
                stringResource(Res.string.qm_audio_output), settings.quickShowAudioOutput, { on -> update { it.copy(quickShowAudioOutput = on) } },
                icon = Icons.Filled.Speaker,
            )
        }
    }
}
