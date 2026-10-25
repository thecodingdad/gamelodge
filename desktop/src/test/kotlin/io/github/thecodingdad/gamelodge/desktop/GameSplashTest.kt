package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.desktop.host.GameSplash
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The runner's splash draws the artwork, and without any the game's title (build/render/splash-*.png). */
class GameSplashTest {
    private fun render(panel: GameSplash.SplashPanel, name: String): BufferedImage {
        panel.setSize(1280, 720)
        val image = BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        panel.paint(g)
        g.dispose()
        File("build/render").apply { mkdirs() }.resolve("$name.png").let { ImageIO.write(image, "png", it) }
        return image
    }

    private fun litPixels(image: BufferedImage) = (0 until image.width step 8).sumOf { x ->
        (0 until image.height step 8).count { y -> (image.getRGB(x, y) and 0xffffff) != 0 }
    }

    @Test
    fun titleOnlyWithoutArtwork() {
        val panel = GameSplash.SplashPanel("Captain Toad Treasure Tracker", null, null).also { it.status = "Starting…" }
        assertTrue("the title is drawn", litPixels(render(panel, "splash-title")) > 20)
    }

    @Test
    fun heroAndLogo() {
        val hero = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(40, 90, 160), 640f, 360f, java.awt.Color(200, 120, 40))
            g.fillRect(0, 0, 640, 360)
            g.dispose()
        }
        val logo = BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRoundRect(0, 0, 300, 100, 30, 30)
            g.dispose()
        }
        val panel = GameSplash.SplashPanel("Game", hero, logo).also { it.status = "Starting…" }
        assertTrue("the artwork fills the screen", litPixels(render(panel, "splash-art")) > 10_000)
    }

    @Test
    fun runnerIconHasBadge() {
        val icons = GameSplash.runnerIcons()
        assertTrue("icons in several sizes", icons.size >= 5)
        val big = icons.last()
        File("build/render").apply { mkdirs() }.resolve("runner-icon.png").let { ImageIO.write(big, "png", it) }
        // The badge's white play triangle sits in the bottom right quarter.
        val c = big.width * 0.72
        assertTrue("badge drawn", (big.getRGB(c.toInt(), c.toInt()) and 0xffffff) == 0xffffff)
    }
}
