package io.github.thecodingdad.moonlightlauncher.ui.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import io.github.thecodingdad.moonlightlauncher.vpn.VpnStatus
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.data.remote.HostStatus
import io.github.thecodingdad.moonlightlauncher.ui.components.StatusDot
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import java.util.Date

data class SystemStatus(
    val batteryPercent: Int,
    val charging: Boolean,
    val wifi: Boolean,
    val vpn: Boolean,
    /** Wired network (e.g. a dock with a LAN port); preferred over Wi-Fi when both are up. */
    val ethernet: Boolean = false,
)

@Composable
fun rememberSystemStatus(): SystemStatus {
    val context = LocalContext.current
    var battery by remember { mutableStateOf(100 to false) }
    var network by remember { mutableStateOf(true to false) }
    var ethernet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                battery = (level * 100 / scale) to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun refresh() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            // With a VPN up the active network is the VPN: look at all networks for the transports.
            @Suppress("DEPRECATION")
            val all = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
                .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            network = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } to
                (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            ethernet = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = refresh()
            override fun onLost(n: Network) = refresh()
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = refresh()
        }
        refresh()
        cm.registerDefaultNetworkCallback(callback)
        onDispose {
            context.unregisterReceiver(receiver)
            cm.unregisterNetworkCallback(callback)
        }
    }
    return SystemStatus(battery.first, battery.second, network.first, network.second, ethernet)
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    Text(
        DateFormat.getTimeFormat(context).format(Date(now)),
        style = MaterialTheme.typography.titleMedium,
        color = LauncherTheme.colors.onSurface,
        modifier = modifier,
    )
}

/** Right side of the top bar: profile chip, host status, VPN, Wi-Fi, battery, clock. */
@Composable
fun StatusCluster(
    hosts: List<HostState>,
    modifier: Modifier = Modifier,
    onHostClick: (HostState) -> Unit = {},
    /** Combined host icon (two or more hosts): show the list of them. */
    onHostsClick: () -> Unit = {},
    controllerCount: Int = 0,
    onControllerClick: () -> Unit = {},
    onClockClick: (() -> Unit)? = null,
    /** Camera hole in the middle: status icons on the left, battery and clock on the right. */
    split: Boolean = false,
    /** Only battery and clock (no room left of a centre camera hole). */
    batteryAndClockOnly: Boolean = false,
) {
    val status = rememberSystemStatus()
    val colors = LauncherTheme.colors
    Row(
        // Always as high as the full row (the host/controller icons have a padded touch area), so
        // the bar keeps its height and the icons their position when only battery and clock show.
        (if (split) modifier.fillMaxWidth() else modifier).heightIn(min = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!batteryAndClockOnly) {
        // Controllers: red when none is connected, green otherwise; tapping opens the overview.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .focusProperties { canFocus = false }
                .clickable(onClick = onControllerClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Filled.SportsEsports, null,
                tint = if (controllerCount == 0) colors.danger else colors.success,
                modifier = Modifier.size(20.dp),
            )
            if (controllerCount > 1) {
                Text(" $controllerCount", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
            }
        }
        // One icon per host; from two hosts on a single one with their number (tap: list of all).
        val combined = showHostsCombined(hosts)
        (if (combined) listOf(hosts) else hosts.map { listOf(it) }).forEach { group ->
            // Touch target for the host popup; like the other status icons not in D-pad navigation.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .focusProperties { canFocus = false }
                    .clickable { if (combined) onHostsClick() else onHostClick(group.first()) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                // The icon itself carries the state: accent = streaming, green = online, red = offline.
                Icon(
                    Icons.Filled.Computer, group.singleOrNull()?.name,
                    tint = hostTint(group, colors.accent, colors.success, colors.danger, colors.onSurfaceMuted),
                    modifier = Modifier.size(22.dp),
                )
                if (combined) Text("${group.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
            }
        }
        val context = LocalContext.current
        val vpn = context.container.vpn
        val tunnels by vpn.tunnels.collectAsState()
        val vpnState by vpn.state.collectAsState()
        val scope = rememberCoroutineScope()
        if (tunnels.isNotEmpty()) {
            Icon(
                Icons.Filled.VpnLock, null,
                tint = if (vpnState.status == VpnStatus.UP) colors.success else colors.onSurfaceMuted,
                modifier = Modifier.size(22.dp).focusProperties { canFocus = false }.clickable {
                    if (vpn.consentIntent() == null) scope.launch { vpn.toggle() }
                },
            )
        } else if (status.vpn) {
            StatusIcon(Icons.Filled.VpnLock)
        }
        // The connection in use: LAN (dock) wins over Wi-Fi.
        Icon(
            when {
                status.ethernet -> Icons.Filled.SettingsEthernet
                status.wifi -> Icons.Filled.Wifi
                else -> Icons.Filled.WifiOff
            },
            null,
            tint = if (status.ethernet || status.wifi) colors.success else colors.onSurfaceMuted, modifier = Modifier.size(20.dp),
        )
        if (split) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(
                when {
                    status.charging -> Icons.Filled.BatteryChargingFull
                    status.batteryPercent > 70 -> Icons.Filled.BatteryFull
                    status.batteryPercent > 25 -> Icons.Filled.Battery3Bar
                    else -> Icons.Filled.Battery1Bar
                },
            )
            Text("${status.batteryPercent}%", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
        }
        // Touch only (controllers open the quick menu with Y / Select).
        val clockTap by androidx.compose.runtime.rememberUpdatedState(onClockClick)
        Clock(
            if (onClockClick == null) Modifier
            else Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures { clockTap?.invoke() } }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusIcon(icon: ImageVector) {
    Icon(icon, null, tint = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.size(20.dp))
}

/** Where a camera notch/hole sits on the top edge, and how far it reaches in from the right. */
data class TopCutout(val position: Position, val rightInset: androidx.compose.ui.unit.Dp) {
    enum class Position { NONE, LEFT, CENTER, RIGHT }
}

/**
 * The cutout on the window's top edge (the status bar is hidden, so the launcher draws there and
 * has to keep its icons out of the camera hole).
 */
@Composable
fun rememberTopCutout(): TopCutout {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Read so the result updates once the insets arrive or change (rotation).
    val cutoutTop = androidx.compose.foundation.layout.WindowInsets.displayCutout.getTop(density)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return remember(cutoutTop, configuration, density) {
        val rect = view.rootWindowInsets?.displayCutout?.boundingRectTop
        val width = view.rootView.width
        if (rect == null || rect.isEmpty || width <= 0) return@remember TopCutout(TopCutout.Position.NONE, 0.dp)
        val center = rect.exactCenterX() / width
        val position = when {
            center < 0.35f -> TopCutout.Position.LEFT
            center > 0.65f -> TopCutout.Position.RIGHT
            else -> TopCutout.Position.CENTER
        }
        TopCutout(position, with(density) { (width - rect.left).toDp() })
    }
}
