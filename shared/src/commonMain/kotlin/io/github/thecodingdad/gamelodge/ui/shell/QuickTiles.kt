package io.github.thecodingdad.gamelodge.ui.shell

import io.github.thecodingdad.gamelodge.ui.components.handCursor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.remote.HostStatus
import io.github.thecodingdad.gamelodge.ui.components.AppIcon
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.vpn.VpnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

/** Tiles available in the quick menu; app shortcuts are stored as "app:<package>". */
object QuickTile {
    const val VPN = "vpn"
    const val WIFI = "wifi"
    const val BLUETOOTH = "bluetooth"
    const val HOTSPOT = "hotspot"
    const val AIRPLANE = "airplane"
    const val MOBILE_DATA = "mobile_data"
    const val FLASHLIGHT = "flashlight"
    const val ROTATION = "rotation"
    const val BATTERY_SAVER = "battery_saver"
    const val SOUND_MODE = "sound_mode"
    const val DND = "dnd"
    const val ANDROID = "android"
    const val APP_PREFIX = "app:"

    val defaults = listOf(VPN, WIFI, BLUETOOTH, AIRPLANE)

    /** Groups for the "add tile" picker. */
    val groups = listOf(
        Res.string.qt_group_launcher to listOf(VPN),
        Res.string.qt_group_network to listOf(WIFI, BLUETOOTH, HOTSPOT, AIRPLANE, MOBILE_DATA),
        Res.string.qt_group_device to listOf(FLASHLIGHT, ROTATION, BATTERY_SAVER),
        Res.string.qt_group_sound to listOf(SOUND_MODE, DND),
        Res.string.qt_group_other to listOf(ANDROID),
    )

    fun appId(packageName: String) = APP_PREFIX + packageName

    /** Whether the device has the hardware behind a tile (e.g. no flashlight without a flash). */
    fun isAvailable(id: String): Boolean = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.quickTileAvailable(id)
}

/** One rendered tile. [packageName] set = app icon instead of [icon]. */
data class TileModel(
    val key: String,
    val icon: ImageVector?,
    val label: String,
    val tint: Color,
    val onClick: () -> Unit,
    val packageName: String? = null,
)

@Composable
fun quickTileName(id: String): String = when {
    id.startsWith(QuickTile.APP_PREFIX) -> io.github.thecodingdad.gamelodge.ui.PlatformUi.current.appLabel(id.removePrefix(QuickTile.APP_PREFIX))
    else -> stringResource(
        when (id) {
            QuickTile.VPN -> Res.string.settings_vpn
            QuickTile.WIFI -> Res.string.qm_wifi
            QuickTile.BLUETOOTH -> Res.string.qm_bluetooth
            QuickTile.HOTSPOT -> Res.string.qt_hotspot
            QuickTile.AIRPLANE -> Res.string.qt_airplane
            QuickTile.MOBILE_DATA -> Res.string.qt_mobile_data
            QuickTile.FLASHLIGHT -> Res.string.qt_flashlight
            QuickTile.ROTATION -> Res.string.qt_rotation
            QuickTile.BATTERY_SAVER -> Res.string.qt_battery_saver
            QuickTile.SOUND_MODE -> Res.string.qt_sound_mode
            QuickTile.DND -> Res.string.qt_dnd
            else -> Res.string.qm_android
        },
    )
}

fun quickTileIcon(id: String): ImageVector? = when (id) {
    QuickTile.VPN -> Icons.Filled.VpnLock
    QuickTile.WIFI -> Icons.Filled.Wifi
    QuickTile.BLUETOOTH -> Icons.Filled.Bluetooth
    QuickTile.HOTSPOT -> Icons.Filled.WifiTethering
    QuickTile.AIRPLANE -> Icons.Filled.AirplanemodeActive
    QuickTile.MOBILE_DATA -> Icons.Filled.NetworkCell
    QuickTile.FLASHLIGHT -> Icons.Filled.FlashlightOn
    QuickTile.ROTATION -> Icons.Filled.ScreenRotation
    QuickTile.BATTERY_SAVER -> Icons.Filled.BatterySaver
    QuickTile.SOUND_MODE -> Icons.Filled.VolumeUp
    QuickTile.DND -> Icons.Filled.DoNotDisturbOn
    QuickTile.ANDROID -> Icons.Filled.Settings
    else -> null
}

/** Builds the configured tiles with live state (refreshed every 2 s while the menu is open). */
/** Builds the configured tiles with live state (refreshed every 2 s while the menu is open). */
@Composable
fun rememberQuickTiles(ids: List<String>): List<TileModel> {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val colors = LauncherTheme.colors
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val vpnState by c.vpn.state.collectAsState()
    val tunnels by c.vpn.tunnels.collectAsState()
    // Tiles of the device itself (Wi-Fi, Bluetooth …), from the platform; its fixed ones come last.
    val fixed = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.fixedQuickTiles
    val all = remember(ids, fixed) { ids.filter { it !in fixed } + fixed }
    val system = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.rememberSystemTiles(all, tick)

    val on = colors.success
    val off = colors.onSurfaceMuted
    val labelVpnOn = stringResource(Res.string.qm_vpn_on)
    val labelVpnOff = stringResource(Res.string.qm_vpn_off)

    // Tiles without hardware on this device (e.g. from a backup of another device) stay hidden.
    val usable = remember(all) { all.filter { it in fixed || QuickTile.isAvailable(it) } }
    return remember(usable, tick, vpnState, tunnels, system) {
        usable.flatMap { id ->
            when (id) {
                QuickTile.VPN -> if (tunnels.isEmpty() || !c.vpn.ready()) emptyList() else {
                    val up = vpnState.status == VpnStatus.UP
                    listOf(TileModel(id, Icons.Filled.VpnLock, if (up) labelVpnOn else labelVpnOff, if (up) on else off, { scope.launch { c.vpn.toggle() } }))
                }
                else -> system[id].orEmpty()
            }
        }
    }
}

/** Where a launcher tile is set up (long press / hold A opens it); system tiles have none. */
fun quickTileSettings(key: String): io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget? = when (key) {
    QuickTile.VPN -> io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Page(io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId.VPN)
    else -> null
}

