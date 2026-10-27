package io.github.thecodingdad.gamelodge.desktop

import org.jetbrains.skia.ImageInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An animation loop kept in memory as packed differences plays back exactly what was decoded. */
class KeptFramesTest {
    @Test
    fun framesComeBackExactlyStartingMidLoop() {
        val info = ImageInfo.makeN32Premul(40, 30)
        val r = java.util.Random(5)
        val frames = List(6) { ByteArray(40 * 30 * 4).also(r::nextBytes) }
        val kept = KeptFrames(info, frames.size)
        // The first loop starts where playing was (frame 3), as when the converted file arrives mid-loop.
        val order = (3 until 6) + (0 until 3)
        order.forEach { assertTrue(kept.add(it, frames[it].copyOf())) }
        assertTrue(kept.complete)
        // Played on in order from the frame after the last one kept, several rounds.
        repeat(3) { order.forEach { i -> assertArrayEquals("frame $i", frames[i], kept.frame(i)) } }
        kept.release()
    }
}
