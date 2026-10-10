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
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
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

data class SystemStatus(val batteryPercent: Int, val charging: Boolean, val wifi: Boolean, val vpn: Boolean)

@Composable
fun rememberSystemStatus(): SystemStatus {
    val context = LocalContext.current
    var battery by remember { mutableStateOf(100 to false) }
    var network by remember { mutableStateOf(true to false) }

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
            network = (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) to
                (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
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
    return SystemStatus(battery.first, battery.second, network.first, network.second)
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
    profileName: String,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHostClick: (HostState) -> Unit = {},
) {
    val status = rememberSystemStatus()
    val colors = LauncherTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceRaised)
                .focusProperties { canFocus = false }
                .clickable(onClick = onProfileClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SportsEsports, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Text(" $profileName", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
        }
        hosts.forEach { host ->
            // Touch target for the host popup; like the other status icons not in D-pad navigation.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .focusProperties { canFocus = false }
                    .clickable { onHostClick(host) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.Computer, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
                StatusDot(
                    when {
                        host.isStreaming -> colors.accent
                        host.status == HostStatus.ONLINE -> colors.success
                        host.status == HostStatus.OFFLINE -> colors.danger
                        else -> colors.onSurfaceMuted
                    },
                )
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
        StatusIcon(if (status.wifi) Icons.Filled.Wifi else Icons.Filled.WifiOff)
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
        Clock()
    }
}

@Composable
private fun StatusIcon(icon: ImageVector) {
    Icon(icon, null, tint = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.size(20.dp))
}
