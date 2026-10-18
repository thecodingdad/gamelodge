package io.github.thecodingdad.gamelodge.ui.shell

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
    const val CONTROLLERS = "controllers"
    const val HOSTS = "hosts"
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

    val defaults = listOf(CONTROLLERS, HOSTS, VPN, WIFI, BLUETOOTH, AIRPLANE)

    /** Groups for the "add tile" picker. */
    val groups = listOf(
        Res.string.qt_group_launcher to listOf(CONTROLLERS, HOSTS, VPN),
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
            QuickTile.CONTROLLERS -> Res.string.settings_controllers
            QuickTile.HOSTS -> Res.string.settings_hosts
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
    QuickTile.CONTROLLERS -> Icons.Filled.SportsEsports
    QuickTile.HOSTS -> Icons.Filled.Computer
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
fun rememberQuickTiles(ids: List<String>, onHost: (String) -> Unit, onControllers: () -> Unit): List<TileModel> {
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
    // Only paired hosts get tiles (found ones are paired in the settings).
    val allHosts by c.hosts.hosts.collectAsState()
    val hosts = remember(allHosts) { allHosts.filterValues { it.paired } }
    val vpnState by c.vpn.state.collectAsState()
    val tunnels by c.vpn.tunnels.collectAsState()
    val controllers = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers()
    // Tiles of the device itself (Wi-Fi, Bluetooth …), from the platform; its fixed ones come last.
    val fixed = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.fixedQuickTiles
    val all = remember(ids, fixed) { ids.filter { it !in fixed } + fixed }
    val system = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.rememberSystemTiles(all, tick)

    val on = colors.success
    val off = colors.onSurfaceMuted
    val labelControllersNone = stringResource(Res.string.qm_no_controller)
    val labelVpnOn = stringResource(Res.string.qm_vpn_on)
    val labelVpnOff = stringResource(Res.string.qm_vpn_off)
    val labelHosts = stringResource(Res.string.qt_hosts_count)
    val controllersLabel = if (controllers.isEmpty()) labelControllersNone else stringResource(Res.string.qm_controllers, controllers.size)

    // Tiles without hardware on this device (e.g. from a backup of another device) stay hidden.
    val usable = remember(all) { all.filter { it in fixed || QuickTile.isAvailable(it) } }
    return remember(usable, tick, hosts, vpnState, tunnels, controllers, system) {
        usable.flatMap { id ->
            when (id) {
                QuickTile.CONTROLLERS -> listOf(
                    TileModel(id, Icons.Filled.SportsEsports, controllersLabel, if (controllers.isEmpty()) colors.danger else on, onControllers),
                )
                // From two hosts on one tile for all of them (tap: list of hosts).
                QuickTile.HOSTS -> if (showHostsCombined(hosts.values)) listOf(
                    TileModel(
                        id, Icons.Filled.Computer, labelHosts.format(hosts.size),
                        hostTint(hosts.values, HostStreamingColor, on, colors.danger, off),
                        { onHost(ALL_HOSTS) },
                    ),
                ) else hosts.values.map { host ->
                    TileModel(
                        "$id:${host.uuid}", Icons.Filled.Computer, host.name,
                        hostTint(listOf(host), HostStreamingColor, on, colors.danger, off),
                        { onHost(host.uuid) },
                    )
                }
                QuickTile.VPN -> if (tunnels.isEmpty() || !c.vpn.ready()) emptyList() else {
                    val up = vpnState.status == VpnStatus.UP
                    listOf(TileModel(id, Icons.Filled.VpnLock, if (up) labelVpnOn else labelVpnOff, if (up) on else off, { scope.launch { c.vpn.toggle() } }))
                }
                else -> system[id].orEmpty()
            }
        }
    }
}

/**
 * Quick tiles in pages of up to two rows of three. More than six tiles: page dots below, and
 * both swiping and the D-pad move a whole page at a time.
 */
@Composable
fun QuickTileGrid(tiles: List<TileModel>) {
    val columns = 3
    val pages = tiles.chunked(columns * 2).ifEmpty { listOf(emptyList()) }
    val pager = androidx.compose.foundation.pager.rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    // The pager clips: extend it by a margin on every side (laid out beyond the panel's
    // padding) so the focus ring and the focus scale of edge tiles stay visible.
    val margin = 8.dp
    Column {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.layout { measurable, constraints ->
                val m = margin.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + 2 * m,
                        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * m else constraints.maxWidth,
                    ),
                )
                layout(placeable.width - 2 * m, placeable.height - 2 * m) { placeable.place(-m, -m) }
            },
        ) { page ->
            Column(Modifier.fillMaxWidth().padding(margin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pages[page].chunked(columns).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { i, tile ->
                            QuickTileView(
                                tile,
                                initialFocus = page == 0 && r == 0 && i == 0,
                                // D-pad onto a tile of another page: show that whole page.
                                onFocused = { if (pager.currentPage != page) scope.launch { pager.animateScrollToPage(page) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Keep the tile width on a half-filled last row.
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (pages.size > 1) PageDots(pages.size, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
    }
}

/** Subtle page dots under the tiles (tap a dot to jump to its page). */
@Composable
private fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { page ->
            val active = page == current
            val width by androidx.compose.animation.core.animateDpAsState(if (active) 14.dp else 6.dp, label = "pageDot")
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (active) LauncherTheme.colors.accent else LauncherTheme.colors.onSurfaceMuted.copy(alpha = 0.4f))
                    .clickable { onSelect(page) },
            )
        }
    }
}

@Composable
private fun QuickTileView(tile: TileModel, initialFocus: Boolean, onFocused: () -> Unit, modifier: Modifier) {
    FocusSurface(
        onClick = tile.onClick,
        onFocused = onFocused,
        modifier = modifier.then(if (initialFocus) Modifier.restoreFocusIf(true) else Modifier),
        color = LauncherTheme.colors.surfaceRaised,
        focusScale = 1.05f,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (tile.packageName != null) {
                AppIcon(tile.packageName, Modifier.size(30.dp))
            } else if (tile.icon != null) {
                androidx.compose.material3.Icon(tile.icon, null, tint = tile.tint, modifier = Modifier.size(30.dp))
            }
            Text(
                tile.label, style = MaterialTheme.typography.labelMedium, color = LauncherTheme.colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
