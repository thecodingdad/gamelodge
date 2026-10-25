package io.github.thecodingdad.gamelodge.ui.shell

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.stream.PowerAction
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import io.github.thecodingdad.gamelodge.ui.RunningSession
import io.github.thecodingdad.gamelodge.ui.components.HoldToConfirmButton
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.SidePanel
import io.github.thecodingdad.gamelodge.ui.components.controllerScroll
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SliderRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalContext

/** Volume and screen brightness of the device (brightness needs "modify system settings"). */
@Composable
fun AndroidSystemControls(visible: Boolean) {
    val context = LocalContext.current
    val settings by io.github.thecodingdad.gamelodge.LocalContainer.current.settings.settings
        .collectAsState(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings())
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember(visible) { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val canWriteSettings = remember(visible) { Settings.System.canWrite(context) }
    fun readBrightness() = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
    var brightness by remember(visible) { mutableIntStateOf(readBrightness()) }
    // Changed elsewhere while the menu is open (volume keys, the system's own slider): follow along.
    androidx.compose.runtime.DisposableEffect(visible) {
        if (!visible) return@DisposableEffect onDispose { }
        val volumeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: Intent) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, volumeReceiver, android.content.IntentFilter(VOLUME_CHANGED_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val brightnessObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { brightness = readBrightness() }
        }
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver)
        onDispose {
            runCatching { context.unregisterReceiver(volumeReceiver) }
            context.contentResolver.unregisterContentObserver(brightnessObserver)
        }
    }
        if (settings.quickShowBrightness && !canWriteSettings) {
            LauncherButton(stringResource(Res.string.qm_brightness_permission), icon = Icons.Filled.BrightnessMedium, onClick = {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            })
        }

        if (settings.quickShowVolume) SliderRow(
            stringResource(Res.string.qm_volume), volume, 0..maxVolume, 1,
            onChange = { v -> volume = v; audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) },
            valueLabel = { "${it * 100 / maxVolume.coerceAtLeast(1)} %" }, icon = Icons.Filled.VolumeUp,
        )
        if (settings.quickShowBrightness && canWriteSettings) {
            SliderRow(
                stringResource(Res.string.qm_brightness), brightness, 1..255, 8,
                onChange = { v ->
                    brightness = v
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                },
                valueLabel = { "${it * 100 / 255} %" }, icon = Icons.Filled.BrightnessMedium,
            )
        }
}

/** Sent by the system on volume changes (not part of the public API constants). */
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
