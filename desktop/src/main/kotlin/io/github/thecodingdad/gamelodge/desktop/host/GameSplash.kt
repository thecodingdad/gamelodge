package io.github.thecodingdad.gamelodge.desktop.host

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.github.thecodingdad.gamelodge.desktop.SplashArt
import io.github.thecodingdad.gamelodge.desktop.games.GameWindow
import io.github.thecodingdad.gamelodge.desktop.isWindows
import io.github.thecodingdad.gamelodge.util.Log
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * The streaming host's screen behind a game the runner starts: the game's artwork over the whole
 * main display instead of the desktop, while it loads and when it ends, as long as the runner runs.
 * Never "always on top": the game, stores and launchers come in front of it. Should it get the
 * focus while the game has a window, the game is brought back to the front.
 */
class GameSplash private constructor(private val frame: JFrame, private val panel: SplashPanel) {
    @Volatile private var closed = false

    /** Follows the game's processes: [pids] gives them while the runner waits for the game. */
    fun watch(pids: () -> Set<Long>) {
        if (!isWindows) return
        thread(isDaemon = true, name = "game-splash") {
            val started = System.currentTimeMillis()
            var seenGame = false
            val own = runCatching { WinDef.HWND(Native.getComponentPointer(frame)) }.getOrNull() ?: return@thread
            val user32 = User32.INSTANCE
            while (!closed) {
                Thread.sleep(WATCH_MS)
                val game = GameWindow.mainWindow(pids())
                if (game != null && !seenGame) {
                    seenGame = true
                    SwingUtilities.invokeLater { panel.status = null; panel.repaint() }
                }
                if (user32.GetForegroundWindow() == own) {
                    when {
                        game != null -> GameWindow.focus(pids())
                        // No window of the game found for a long time: not in the way of what it shows.
                        !seenGame && System.currentTimeMillis() - started > GIVE_WAY_MS -> {
                            Log.i("Splash: no game window, moving behind")
                            user32.SetWindowPos(own, WinDef.HWND(com.sun.jna.Pointer.createConstant(1)), 0, 0, 0, 0, WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE)
                        }
                    }
                }
            }
        }
    }

    fun close() {
        closed = true
        SwingUtilities.invokeLater { frame.dispose() }
    }

    internal class SplashPanel(private val title: String, private val hero: BufferedImage?, private val logo: BufferedImage?) : JPanel() {
        @Volatile var status: String? = null

        init {
            background = Color.BLACK
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = width
            val h = height
            hero?.let { img ->
                // Cover the screen, cropped in the middle; darker towards the bottom for the logo.
                val scale = max(w.toDouble() / img.width, h.toDouble() / img.height)
                val dw = (img.width * scale).toInt()
                val dh = (img.height * scale).toInt()
                g2.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
                g2.composite = AlphaComposite.SrcOver
                g2.paint = GradientPaint(0f, h * 0.3f, Color(0, 0, 0, 90), 0f, h.toFloat(), Color(0, 0, 0, 225))
                g2.fillRect(0, 0, w, h)
            }
            val margin = (w * 0.06).toInt()
            var bottom = h - margin
            status?.let { text ->
                g2.font = Font(Font.SANS_SERIF, Font.PLAIN, max(18, h / 40))
                g2.color = Color(255, 255, 255, 190)
                g2.drawString(text, margin, bottom)
                bottom -= g2.fontMetrics.height + h / 60
            }
            if (logo != null) {
                val maxW = w * 0.36
                val maxH = h * 0.2
                val scale = min(maxW / logo.width, maxH / logo.height)
                val lw = (logo.width * scale).toInt()
                val lh = (logo.height * scale).toInt()
                g2.drawImage(logo, margin, bottom - lh, lw, lh, null)
            } else {
                g2.font = Font(Font.SANS_SERIF, Font.BOLD, max(28, h / 14))
                g2.color = Color.WHITE
                g2.drawString(title, margin, bottom)
            }
        }
    }

    companion object {
        private const val WATCH_MS = 1000L
        private const val GIVE_WAY_MS = 60_000L

        /** Shows the splash for [title] over the main display; null when there is no display. */
        fun show(title: String, art: SplashArt?, status: String): GameSplash? = runCatching {
            if (GraphicsEnvironment.isHeadless()) return null
            val hero = decode(art?.hero) ?: decode(art?.cover)
            val logo = decode(art?.logo)
            var splash: GameSplash? = null
            SwingUtilities.invokeAndWait {
                val panel = SplashPanel(art?.title ?: title, hero, logo).also { it.status = status }
                val frame = JFrame(art?.title ?: title).apply {
                    isUndecorated = true
                    iconImages = runnerIcons()
                    contentPane = panel
                    bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    isVisible = true
                    toFront()
                }
                splash = GameSplash(frame, panel)
            }
            splash
        }.onFailure { Log.w("Splash failed", it) }.getOrNull()

        /**
         * The app icon with a play badge in the corner: the splash's taskbar button looks like
         * GameLodge but can be told from the launcher's.
         */
        internal fun runnerIcons(): List<BufferedImage> {
            val base = runCatching { GameSplash::class.java.getResourceAsStream("/icon.png")?.use { javax.imageio.ImageIO.read(it) } }.getOrNull()
                ?: return emptyList()
            return listOf(16, 24, 32, 48, 64, 128, 256).map { size ->
                BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { image ->
                    val g = image.createGraphics()
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g.drawImage(base, 0, 0, size, size, null)
                    // Badge: a light ring around a dark disc with a play triangle, bottom right.
                    val d = size * 0.56f
                    val x = size - d
                    val y = size - d
                    g.color = java.awt.Color(0xF5, 0xF5, 0xF5)
                    g.fill(java.awt.geom.Ellipse2D.Float(x, y, d, d))
                    val ring = d * 0.1f
                    g.color = java.awt.Color(0x16, 0x8A, 0x4A)
                    g.fill(java.awt.geom.Ellipse2D.Float(x + ring, y + ring, d - 2 * ring, d - 2 * ring))
                    g.color = java.awt.Color.WHITE
                    val cx = x + d / 2
                    val cy = y + d / 2
                    val t = d * 0.22f
                    g.fill(java.awt.Polygon(
                        intArrayOf((cx - t * 0.7f).toInt(), (cx - t * 0.7f).toInt(), (cx + t * 1.1f).toInt()),
                        intArrayOf((cy - t).toInt(), (cy + t).toInt(), cy.toInt()), 3,
                    ))
                    g.dispose()
                }
            }
        }

        /** Any stored image (also WebP) via Skia, the first frame of animated ones. */
        internal fun decode(path: String?): BufferedImage? {
            val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
            return runCatching {
                val image = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
                val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                val info = bitmap.imageInfo
                val pixels = bitmap.readPixels(info.withColorType(org.jetbrains.skia.ColorType.BGRA_8888), info.width * 4, 0, 0) ?: return null
                BufferedImage(info.width, info.height, BufferedImage.TYPE_INT_ARGB).apply {
                    val ints = IntArray(info.width * info.height) { i ->
                        val b = pixels[i * 4].toInt() and 0xff
                        val gr = pixels[i * 4 + 1].toInt() and 0xff
                        val r = pixels[i * 4 + 2].toInt() and 0xff
                        val a = pixels[i * 4 + 3].toInt() and 0xff
                        (a shl 24) or (r shl 16) or (gr shl 8) or b
                    }
                    setRGB(0, 0, info.width, info.height, ints, 0, info.width)
                }
            }.getOrNull()
        }
    }
}
