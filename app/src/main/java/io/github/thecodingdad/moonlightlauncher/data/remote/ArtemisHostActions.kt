package io.github.thecodingdad.moonlightlauncher.data.remote

import android.net.Uri
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Host management through Artemis (pairing, adding, waking, removing). */
class ArtemisHostActions(
    private val hosts: ArtemisHostRepository,
) : HostActions {
    override fun generatePin(): String = PairingManager.generatePinString()

    override suspend fun pair(host: HostState, pin: String): PairResult = withContext(Dispatchers.IO) {
        if (host.status != HostStatus.ONLINE || hosts.details(host.uuid)?.activeAddress == null) return@withContext PairResult.OFFLINE
        val binder = hosts.awaitBinder()
        // Polling while pairing makes concurrent requests the host rejects.
        binder.stopPolling()
        try {
            val http = hosts.http(host)
            if (http.pairState == PairingManager.PairState.PAIRED) return@withContext PairResult.PAIRED
            val pm = http.pairingManager
            when (pm.pair(http.getServerInfo(true), pin, null)) {
                PairingManager.PairState.PAIRED -> {
                    binder.getComputer(host.uuid)?.serverCert = pm.pairedCert
                    binder.invalidateStateForComputer(host.uuid)
                    PairResult.PAIRED
                }
                PairingManager.PairState.PIN_WRONG -> PairResult.WRONG_PIN
                PairingManager.PairState.ALREADY_IN_PROGRESS -> PairResult.ALREADY_IN_PROGRESS
                else -> if (host.isStreaming) PairResult.IN_GAME else PairResult.FAILED
            }
        } catch (e: Exception) {
            Log.w("Pairing with ${host.name} failed", e)
            PairResult.FAILED
        } finally {
            hosts.startPolling()
        }
    }

    override suspend fun addHost(rawAddress: String): AddHostResult = withContext(Dispatchers.IO) {
        val input = rawAddress.trim()
        if (input.isEmpty()) return@withContext AddHostResult.INVALID_ADDRESS
        // IPv6 literals need brackets for URI parsing.
        val withScheme = if (input.count { it == ':' } > 1 && !input.startsWith("[")) "moonlight://[$input]" else "moonlight://$input"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotEmpty() } ?: return@withContext AddHostResult.INVALID_ADDRESS
        val port = uri.port.takeIf { it > 0 } ?: NvHTTP.DEFAULT_HTTP_PORT
        val details = ComputerDetails().apply { manualAddress = ComputerDetails.AddressTuple(host, port) }
        val added = runCatching { hosts.awaitBinder().addComputerBlocking(details) }.getOrDefault(false)
        if (added) AddHostResult.ADDED else AddHostResult.UNREACHABLE
    }

    override suspend fun wakeOnLan(host: HostState): Boolean = withContext(Dispatchers.IO) {
        runCatching { WakeOnLanSender.sendWolPacket(hosts.details(host.uuid) ?: error("unknown host")); true }
            .onFailure { Log.w("WoL for ${host.name} failed", it) }
            .getOrDefault(false)
    }

    override suspend fun unpair(host: HostState) = withContext(Dispatchers.IO) {
        runCatching { hosts.http(host).unpair() }
        hosts.invalidate(host.uuid)
    }

    override suspend fun remove(host: HostState) {
        hosts.removeHost(host)
    }

    override suspend fun quitApp(host: HostState): Boolean = withContext(Dispatchers.IO) {
        runCatching { hosts.http(host).quitApp() }.getOrDefault(false)
    }
}
