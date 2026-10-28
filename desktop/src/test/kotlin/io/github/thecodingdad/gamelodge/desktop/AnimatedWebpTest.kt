package io.github.thecodingdad.gamelodge.desktop

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Animations converted for playback: muxed from still frames, thinned to 30 fps, scaled in steps. */
class AnimatedWebpTest {
    private fun still(width: Int, height: Int, color: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(color)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.WEBP, 90)!!.bytes
    }

    private fun animation(width: Int, height: Int, durations: List<Int>): ByteArray =
        AnimatedWebp.mux(width, height, durations.mapIndexed { i, d -> AnimatedWebp.Frame(still(width, height, if (i % 2 == 0) 0xFF2050A0.toInt() else 0xFFA05020.toInt()), d) })

    @Test
    fun muxedFramesDecodeWithTheirDurations() {
        val codec = Codec.makeFromData(Data.makeFromBytes(animation(120, 80, listOf(100, 50, 250))))
        assertEquals(3, codec.frameCount)
        assertEquals(120, codec.width)
        assertEquals(80, codec.height)
        assertEquals(listOf(100, 50, 250), codec.framesInfo.map { it.duration })
    }

    @Test
    fun conversionKeepsAtMostThirtyFramesASecondAtTheGivenSize() {
        // Six frames of 20 ms (50 fps), 120 ms in all.
        val source = animation(400, 600, List(6) { 20 })
        val codec = Codec.makeFromData(Data.makeFromBytes(AnimationCache.transcode(source, 200, 300)))
        assertEquals(200, codec.width)
        assertEquals(300, codec.height)
        val durations = codec.framesInfo.map { it.duration }
        assertEquals(120, durations.sum())
        assertTrue("frames of $durations", durations.all { it >= 1000 / 30 })
    }

    @Test
    fun widthsComeInSteps() {
        assertEquals(1000, AnimationCache.width(1200, 1000)) // never larger than the original
        assertEquals(AnimationCache.width(300, 2000), AnimationCache.width(290, 2000)) // close sizes share a file
        assertTrue(AnimationCache.width(300, 2000) >= 300)
        assertTrue(AnimationCache.width(300, 2000) < 300 * 1.25 + 1)
    }

    @Test
    fun onlyTheChangedRectangleIsStored() {
        val w = 40; val h = 30
        val before = ByteArray(w * h * 4) { 50 }
        val after = before.copyOf()
        // Pixel (13, 7) changes clearly, (2, 2) only by compression noise.
        after[(7 * w + 13) * 4] = 120
        after[(2 * w + 2) * 4] = 52
        val rect = AnimationCache.changedRect(before, after, w, h)!!
        assertEquals(listOf(12, 6, 2, 2), rect.toList()) // x and y even, as WebP wants them
        assertEquals(null, AnimationCache.changedRect(before, before.copyOf(), w, h))
    }

    @Test
    fun aStillAnimationKeepsOneFrameForItsWholeTime() {
        val source = AnimatedWebp.mux(120, 80, List(5) { AnimatedWebp.Frame(still(120, 80, 0xFF2050A0.toInt()), 100) })
        val codec = Codec.makeFromData(Data.makeFromBytes(AnimationCache.transcode(source, 120, 80)))
        assertEquals(listOf(500), codec.framesInfo.map { it.duration })
    }
}
