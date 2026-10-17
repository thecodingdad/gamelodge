package io.github.thecodingdad.gamelodge.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AccentColorTest {
    @Test
    fun vibrantColorOfACover() = runBlocking {
        // Mostly grey with a strong red area: the accent is the red.
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(200, 300)
        surface.canvas.clear(0xFF808080.toInt())
        surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, 0f, 200f, 150f), org.jetbrains.skia.Paint().apply { color = 0xFFE01010.toInt() })
        val file = File(Files.createTempDirectory("accent").toFile(), "cover.png")
        file.writeBytes(surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        val color = DesktopArtwork.accentColor(file)
        assertNotNull(color)
        assertTrue("red expected, was $color", color!!.red > 0.7f && color.green < 0.3f && color.blue < 0.3f)
    }
}
