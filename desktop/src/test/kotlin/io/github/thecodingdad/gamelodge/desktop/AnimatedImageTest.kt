package io.github.thecodingdad.gamelodge.desktop

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimatedImageTest {
    /** 1×1 GIF, two frames of 100 ms: red, then blue (hand-made LZW for one pixel). */
    private val gif = hex(
        "474946383961 0100 0100 80 00 00" + "FF0000 0000FF" +
            "21FF0B 4E45545343415045322E30 03 01 0000 00" +
            "21F904 00 0A00 00 00" + "2C 0000 0000 0100 0100 00" + "02 02 4401 00" +
            "21F904 00 0A00 00 00" + "2C 0000 0000 0100 0100 00" + "02 02 4C01 00" +
            "3B",
    )

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun fetch(bytes: ByteArray) = SourceFetchResult(
        ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), "image/gif", DataSource.DISK,
    )

    private fun pixel(image: coil3.Image): Int {
        val surface = Surface.makeRasterN32Premul(1, 1)
        image.draw(surface.canvas)
        return surface.makeImageSnapshot().toComposeBitmapPixel()
    }

    private fun org.jetbrains.skia.Image.toComposeBitmapPixel(): Int {
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(1, 1) }
        readPixels(bitmap)
        return bitmap.getColor(0, 0)
    }

    @Test
    fun framesAdvanceWithTime() = runBlocking {
        val loader = coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE).build()
        val decoder = AnimatedImageDecoder.Factory().create(fetch(gif), Options(coil3.PlatformContext.INSTANCE), loader)!!
        val image = decoder.decode()!!.image
        assertEquals(1, image.width)
        val first = pixel(image)
        // Zero channels come lifted by one level (Intel Direct3D workaround in AnimatedImage).
        assertEquals(0xFFFF0101.toInt(), first)
        // The next frame is decoded in the background after the first one's 100 ms.
        var second = first
        val until = System.currentTimeMillis() + 2_000
        while (second == first && System.currentTimeMillis() < until) {
            Thread.sleep(30)
            second = pixel(image)
        }
        assertEquals(0xFF0101FF.toInt(), second)
        assertNotEquals(first, second)
    }

    @Test
    fun stillImagesAreLeftToCoil() {
        val png = hex("89504E470D0A1A0A")
        val loader = coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE).build()
        assertNull(AnimatedImageDecoder.Factory().create(fetch(png), Options(coil3.PlatformContext.INSTANCE), loader))
    }
}
