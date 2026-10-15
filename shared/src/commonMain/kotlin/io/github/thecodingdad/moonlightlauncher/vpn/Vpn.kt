package io.github.thecodingdad.moonlightlauncher.vpn

import kotlinx.coroutines.flow.StateFlow

enum class VpnStatus { DOWN, CONNECTING, UP, ERROR }

data class VpnState(val status: VpnStatus, val tunnel: String?, val error: String? = null)

/** The launcher's own VPN (WireGuard) as the quick menu and status bar see it. */
interface Vpn {
    val state: StateFlow<VpnState>

    /** Names of the imported tunnels. */
    val tunnels: StateFlow<List<String>>

    /** The VPN may be switched on without asking the user first (Android: consent given). */
    fun ready(): Boolean

    /** Connects the default tunnel or disconnects. */
    suspend fun toggle()
}

/** Platforms without a VPN of the launcher's own: no tunnels, so nothing VPN related shows. */
object NoVpn : Vpn {
    override val state: StateFlow<VpnState> = kotlinx.coroutines.flow.MutableStateFlow(VpnState(VpnStatus.DOWN, null))
    override val tunnels: StateFlow<List<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    override fun ready() = false
    override suspend fun toggle() = Unit
}
