package io.github.thecodingdad.gamelodge.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionQualityMonitorTest {
    private fun s(rtt: Int = 4, variance: Int = 1, loss: Boolean = false, drops: Int = 0) = ConnectionSample(rtt, variance, loss, drops, false)

    private fun ConnectionQualityMonitor.feed(n: Int, sample: ConnectionSample): ConnectionIssue? {
        var last: ConnectionIssue? = null
        repeat(n) { last = add(sample) }
        return last
    }

    @Test
    fun goodConnectionNeverWarns() {
        val m = ConnectionQualityMonitor()
        assertNull(m.feed(30, s()))
    }

    @Test
    fun connectingSecondsDontCount() {
        val m = ConnectionQualityMonitor()
        assertNull(m.feed(ConnectionQualityMonitor.WARMUP, s(loss = true)))
        assertEquals(ConnectionIssue.FRAME_LOSS, m.add(s(loss = true)))
    }

    @Test
    fun jitterNeedsTwoOfThreeSeconds() {
        val m = ConnectionQualityMonitor()
        m.feed(10, s())
        assertNull(m.add(s(variance = 20)))
        assertEquals(ConnectionIssue.JITTER, m.add(s(variance = 20)))
    }

    @Test
    fun audioDropsAddUp() {
        val m = ConnectionQualityMonitor()
        m.feed(10, s())
        assertNull(m.add(s(drops = 2)))
        assertEquals(ConnectionIssue.AUDIO_DROPS, m.add(s(drops = 3)))
    }

    @Test
    fun highLatencyOnlyWhenItLasts() {
        val m = ConnectionQualityMonitor()
        m.feed(10, s())
        assertNull(m.feed(ConnectionQualityMonitor.WINDOW - 1, s(rtt = 80)))
        assertEquals(ConnectionIssue.LATENCY, m.add(s(rtt = 80)))
    }

    @Test
    fun mostSevereWins() {
        val m = ConnectionQualityMonitor()
        m.feed(10, s())
        m.add(s(variance = 20, drops = 5))
        assertEquals(ConnectionIssue.AUDIO_DROPS, m.add(s(variance = 20, drops = 5)))
        assertEquals(ConnectionIssue.FRAME_LOSS, m.add(s(variance = 20, loss = true)))
    }

    @Test
    fun warningStaysUntilFineForAWhile() {
        val m = ConnectionQualityMonitor()
        m.feed(10, s())
        m.add(s(loss = true))
        assertEquals(ConnectionIssue.FRAME_LOSS, m.feed(ConnectionQualityMonitor.CLEAR_AFTER - 1, s()))
        assertNull(m.add(s()))
    }
}
