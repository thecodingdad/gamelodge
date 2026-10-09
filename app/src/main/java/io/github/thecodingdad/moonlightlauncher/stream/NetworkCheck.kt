package io.github.thecodingdad.moonlightlauncher.stream

import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.data.remote.HostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class NetworkCheckResult(val rttMs: Int, val jitterMs: Int, val failedProbes: Int) {
    /** Thresholds where game streaming starts to feel bad. */
    val isGood: Boolean get() = rttMs <= 60 && jitterMs <= 20 && failedProbes == 0

    companion object {
        fun from(samples: List<Long?>): NetworkCheckResult? {
            val ok = samples.filterNotNull()
            if (ok.isEmpty()) return null
            val mean = ok.average()
            val std = sqrt(ok.map { (it - mean) * (it - mean) }.average())
            return NetworkCheckResult(mean.toInt(), std.toInt(), samples.size - ok.size)
        }
    }
}

/** Measures round trip time and jitter to a host using its HTTP server-info endpoint. */
object NetworkCheck {
    suspend fun measure(hosts: HostRepository, host: HostState, probes: Int = 6): NetworkCheckResult? = withContext(Dispatchers.IO) {
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
