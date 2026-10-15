package io.github.thecodingdad.moonlightlauncher.stream

import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Measures round trip time and jitter to a host using its HTTP server-info endpoint. */
object NetworkCheck {
    suspend fun measure(hosts: io.github.thecodingdad.moonlightlauncher.data.remote.ArtemisHostRepository, host: HostState, probes: Int = 6): NetworkCheckResult? = withContext(Dispatchers.IO) {
        val http = runCatching { hosts.http(host) }.getOrNull() ?: return@withContext null
        // First request warms up TLS and DNS; it's not counted.
        runCatching { http.getServerInfo(true) }
        val samples = (1..probes).map {
            val start = System.nanoTime()
            runCatching { http.getServerInfo(true) }.map { (System.nanoTime() - start) / 1_000_000 }.getOrNull()
        }
        NetworkCheckResult.from(samples)
    }
}
