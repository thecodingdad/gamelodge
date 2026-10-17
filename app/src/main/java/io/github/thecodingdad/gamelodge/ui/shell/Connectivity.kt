package io.github.thecodingdad.gamelodge.ui.shell

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Wi-Fi state; [ssid] needs the location permission (Android hides it otherwise). */
data class WifiState(val connected: Boolean, val ssid: String?)

/** Bluetooth state; [connectedDevices] is null without the "nearby devices" permission. */
data class BluetoothState(val enabled: Boolean, val connectedDevices: Int?)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** [revision]: bump after a permission was granted so the state is read again. */
@Composable
fun rememberWifiState(revision: Int = 0): WifiState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(WifiState(false, null)) }
    DisposableEffect(revision) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun update(caps: NetworkCapabilities?) {
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val ssid = if (wifi) (caps?.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } else null
            state = WifiState(wifi, ssid)
        }
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
                override fun onLost(network: Network) = update(null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
                override fun onLost(network: Network) = update(null)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return state
}

@Composable
fun rememberBluetoothState(revision: Int = 0): BluetoothState {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { tick++ }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return remember(tick, revision) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val enabled = adapter?.isEnabled == true
        val canRead = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        val count = if (!enabled) 0 else if (!canRead) null else runCatching {
            // No public API lists all connected devices across profiles; isConnected is hidden but stable.
            val isConnected = BluetoothDevice::class.java.getMethod("isConnected")
            @Suppress("MissingPermission")
            adapter!!.bondedDevices.count { isConnected.invoke(it) as? Boolean == true }
        }.getOrNull()
        BluetoothState(enabled, count)
    }
}

/** Permissions the quick menu would like for Wi-Fi name and Bluetooth devices. */
fun missingConnectivityPermissions(context: Context): List<String> = buildList {
    if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
