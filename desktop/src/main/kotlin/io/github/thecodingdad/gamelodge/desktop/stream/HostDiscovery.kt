package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * Finds streaming hosts in the local networks: they announce "_nvstream._tcp" over mDNS (as for
 * Moonlight). One listener per network interface, running only while [start]ed.
 */
class HostDiscovery(private val found: (address: String, port: Int) -> Unit) {
    private val instances = mutableListOf<JmDNS>()

    @Synchronized
    fun start() {
        if (instances.isNotEmpty()) return
        // JmDNS takes a moment per interface: off the caller's thread.
        thread(isDaemon = true, name = "host-discovery") {
            val addresses = runCatching {
                NetworkInterface.networkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
            }.getOrDefault(emptyList())
            for (address in addresses) {
                val jmdns = runCatching { JmDNS.create(address) }.getOrNull() ?: continue
                jmdns.addServiceListener(SERVICE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) = event.dns.requestServiceInfo(event.type, event.name, 1000)
                    override fun serviceRemoved(event: ServiceEvent) = Unit
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        info.inet4Addresses.firstOrNull()?.hostAddress?.let { found(it, info.port.takeIf { p -> p > 0 } ?: 47989) }
                    }
                })
                synchronized(this) { instances += jmdns }
            }
            Log.d("Host discovery on ${addresses.size} network(s)")
        }
    }

    @Synchronized
    fun stop() {
        val running = instances.toList()
        instances.clear()
        if (running.isNotEmpty()) thread(isDaemon = true) { running.forEach { runCatching { it.close() } } }
    }

    private companion object {
        const val SERVICE = "_nvstream._tcp.local."
    }
}
