package io.github.thecodingdad.gamelodge.ui.shell

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.AppIcon
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalContext

/** The device's own quick tiles (Wi-Fi, Bluetooth, hotspot, flashlight …) with live state. */
@Composable
fun rememberAndroidSystemTiles(ids: List<String>, revision: Int): Map<String, List<TileModel>> {
    val context = LocalContext.current
    val colors = LauncherTheme.colors
    var tick by remember { mutableIntStateOf(0) }
    // Ask once for the permissions behind the Wi-Fi name and the Bluetooth device count.
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permissionRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRevision++ }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
        val missing = missingConnectivityPermissions(context)
        if (missing.isNotEmpty() && !prefs.getBoolean("connectivity_permissions_asked", false)) {
            prefs.edit().putBoolean("connectivity_permissions_asked", true).apply()
            permissionRequest.launch(missing.toTypedArray())
        }
    }
    val wifi = rememberWifiState(permissionRevision)
    val bluetooth = rememberBluetoothState(permissionRevision + tick + revision)
    val system = rememberSystemStatus()
    val torch = rememberTorch()

    val on = colors.success
    val off = colors.onSurfaceMuted
    val labelLan = stringResource(Res.string.qm_lan)
    val labelWifi = stringResource(Res.string.qm_wifi)
    val labelConnected = stringResource(Res.string.qm_connected)
    val labelBtOff = stringResource(Res.string.qm_bluetooth_off)
    val labelBt = stringResource(Res.string.qm_bluetooth)
    val btDevices = bluetooth.connectedDevices?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.qm_bluetooth_devices, it, it) }
    val ringer = remember(tick, revision) { context.getSystemService(AudioManager::class.java).ringerMode }
    val ringerLabel = stringResource(
        when (ringer) {
            AudioManager.RINGER_MODE_SILENT -> Res.string.qt_sound_silent
            AudioManager.RINGER_MODE_VIBRATE -> Res.string.qt_sound_vibrate
            else -> Res.string.qt_sound_normal
        },
    )
    val names = ids.associateWith { quickTileName(it) }

    val usable = remember(ids) { ids.filter { QuickTile.isAvailable(it) } }
    return remember(usable, tick, revision, wifi, bluetooth, system, torch.on, names) {
        usable.associateWith { id ->
            when (id) {
                QuickTile.WIFI -> listOf(
                    if (system.ethernet) TileModel(id, Icons.Outlined.Lan, labelLan, on, { context.open(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
                    else TileModel(
                        id, Icons.Filled.Wifi,
                        when { !wifi.connected -> labelWifi; wifi.ssid != null -> wifi.ssid; else -> labelConnected },
                        if (wifi.connected) on else off,
                        { context.open(Intent(Settings.Panel.ACTION_WIFI)) },
                    ),
                )
                QuickTile.BLUETOOTH -> listOf(
                    TileModel(
                        id, Icons.Filled.Bluetooth,
                        when { !bluetooth.enabled -> labelBtOff; btDevices != null -> btDevices; else -> labelBt },
                        if (btDevices != null) on else off,
                        { context.open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    ),
                )
                QuickTile.HOTSPOT -> listOf(
                    TileModel(id, Icons.Filled.WifiTethering, names.getValue(id), if (hotspotOn(context)) on else off, {
                        context.open(
                            if (hotspotSettingsResolvable(context)) Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                            else Intent(Settings.ACTION_WIRELESS_SETTINGS),
                        )
                    }),
                )
                QuickTile.AIRPLANE -> {
                    val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                    listOf(TileModel(id, Icons.Filled.AirplanemodeActive, names.getValue(id), if (enabled) on else off, { context.open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }))
                }
                QuickTile.MOBILE_DATA -> listOf(
                    TileModel(id, Icons.Filled.NetworkCell, names.getValue(id), off, { context.open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }),
                )
                QuickTile.FLASHLIGHT -> if (!torch.available) emptyList() else listOf(
                    TileModel(id, Icons.Filled.FlashlightOn, names.getValue(id), if (torch.on) on else off, torch.toggle),
                )
                QuickTile.ROTATION -> {
                    val auto = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
                    listOf(TileModel(id, Icons.Filled.ScreenRotation, names.getValue(id), if (!auto) on else off, {
                        if (Settings.System.canWrite(context)) {
                            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (auto) 0 else 1)
                            tick++
                        } else {
                            context.open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                    }))
                }
                QuickTile.BATTERY_SAVER -> {
                    val saver = context.getSystemService(PowerManager::class.java).isPowerSaveMode
                    listOf(TileModel(id, Icons.Filled.BatterySaver, names.getValue(id), if (saver) on else off, { context.open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }))
                }
                QuickTile.SOUND_MODE -> listOf(
                    TileModel(id, Icons.Filled.VolumeUp, ringerLabel, if (ringer == AudioManager.RINGER_MODE_NORMAL) on else off, {
                        val audio = context.getSystemService(AudioManager::class.java)
                        val next = when (audio.ringerMode) {
                            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                            else -> AudioManager.RINGER_MODE_NORMAL
                        }
                        // Silent needs "Do not disturb" access since Android 7.
                        if (runCatching { audio.ringerMode = next }.isFailure) {
                            context.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }
                        tick++
                    }),
                )
                QuickTile.DND -> {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    val dnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    listOf(TileModel(id, Icons.Filled.DoNotDisturbOn, names.getValue(id), if (dnd) on else off, {
                        if (nm.isNotificationPolicyAccessGranted) {
                            nm.setInterruptionFilter(if (dnd) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                            tick++
                        } else {
                            context.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }
                    }))
                }
                QuickTile.ANDROID -> listOf(
                    TileModel(id, Icons.Filled.Settings, names.getValue(id), colors.onSurface, { context.open(Intent(Settings.ACTION_SETTINGS)) }),
                )
                else -> if (id.startsWith(QuickTile.APP_PREFIX)) {
                    val pkg = id.removePrefix(QuickTile.APP_PREFIX)
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch == null) emptyList() else listOf(TileModel(id, null, names.getValue(id), colors.onSurface, { context.open(launch) }, packageName = pkg))
                } else emptyList()
            }
        }
    }
}

fun androidAppLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

private fun Context.open(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun hotspotOn(context: Context): Boolean = runCatching {
    val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
    wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
}.getOrDefault(false)

private fun hotspotSettingsResolvable(context: Context): Boolean =
    Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings").resolveActivity(context.packageManager) != null

class TorchState(val available: Boolean, val on: Boolean, val toggle: () -> Unit)

@Composable
private fun rememberTorch(): TorchState {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(CameraManager::class.java) }
    val cameraId = remember { torchCameraId(context) }
    var on by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(cameraId) {
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == cameraId) on = enabled }
        }
        if (cameraId != null) cm.registerTorchCallback(callback, null)
        onDispose { if (cameraId != null) cm.unregisterTorchCallback(callback) }
    }
    return TorchState(cameraId != null, on) { cameraId?.let { runCatching { cm.setTorchMode(it, !on) } } }
}

/** Whether the device has the hardware behind a tile (e.g. no flashlight without a flash). */
fun androidQuickTileAvailable(context: Context, id: String): Boolean {
    val pm = context.packageManager
    fun has(feature: String) = pm.hasSystemFeature(feature)
    return when (id) {
        QuickTile.BLUETOOTH -> has(android.content.pm.PackageManager.FEATURE_BLUETOOTH)
        QuickTile.MOBILE_DATA -> has(android.content.pm.PackageManager.FEATURE_TELEPHONY)
        QuickTile.WIFI, QuickTile.HOTSPOT -> has(android.content.pm.PackageManager.FEATURE_WIFI)
        // Some devices declare camera.flash without any camera having one: ask the cameras.
        QuickTile.FLASHLIGHT -> torchCameraId(context) != null
        QuickTile.ROTATION -> has(android.content.pm.PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        QuickTile.BATTERY_SAVER -> context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false) == true
        else -> true
    }
}

fun torchCameraId(context: Context): String? = runCatching {
    val cm = context.getSystemService(CameraManager::class.java)
    cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
}.getOrNull()
