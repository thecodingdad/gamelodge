package io.github.thecodingdad.gamelodge.ui.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.displayCutout

@Composable
fun rememberAndroidSystemStatus(): SystemStatus {
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
fun rememberAndroidTopCutout(): TopCutout {
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
