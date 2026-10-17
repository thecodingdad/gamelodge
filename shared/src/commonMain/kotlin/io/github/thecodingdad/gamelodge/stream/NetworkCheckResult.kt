package io.github.thecodingdad.gamelodge.stream

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
