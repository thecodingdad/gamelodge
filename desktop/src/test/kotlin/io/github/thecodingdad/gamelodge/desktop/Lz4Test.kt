package io.github.thecodingdad.gamelodge.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LZ4 as used for animation frames in memory: what goes in comes out. */
class Lz4Test {
    private fun roundTrip(data: ByteArray): ByteArray {
        val packed = Lz4.compress(data)
        val out = ByteArray(data.size)
        val n = Lz4.decompress(packed, out)
        assertArrayEquals(data, out.copyOf(n))
        return packed
    }

    @Test
    fun randomDataComesBackUnchanged() {
        val r = java.util.Random(7)
        listOf(0, 1, 5, 12, 13, 100, 70_000, 600_000).forEach { size -> roundTrip(ByteArray(size).also(r::nextBytes)) }
    }

    @Test
    fun repetitiveDataShrinks() {
        // A frame's difference to the one before: mostly zero, some changed pixels.
        val r = java.util.Random(3)
        val delta = ByteArray(305 * 458 * 4)
        repeat(4000) { delta[r.nextInt(delta.size)] = r.nextInt().toByte() }
        val packed = roundTrip(delta)
        assertTrue("${packed.size} of ${delta.size}", packed.size < delta.size / 10)
        // Long runs and overlapping matches (a repeated pattern).
        roundTrip(ByteArray(300_000) { (it % 3).toByte() })
        roundTrip(ByteArray(300_000) { 9 })
    }
}
