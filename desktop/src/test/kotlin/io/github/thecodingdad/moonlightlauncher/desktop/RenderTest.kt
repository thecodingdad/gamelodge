package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.thecodingdad.moonlightlauncher.LocalContainer
import io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkFiles
import io.github.thecodingdad.moonlightlauncher.ui.LauncherApp
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.PlatformUi
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkPlatform
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.input.InputDispatcher
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize

/** The whole launcher UI comes up on the desktop JVM (database, settings, resources, navigation). */
class RenderTest {
    @Test
    fun launcherRendersOnTheDesktop() {
        val dir = Files.createTempDirectory("launcher-render").toFile()
        val container = DesktopContainer(dir)
        ArtworkFiles.init(dir)
        ArtworkPlatform.current = DesktopArtwork
        val dispatcher = InputDispatcher()
        val gamepads = Gamepads(dispatcher)
        PlatformUi.current = DesktopPlatformUi(gamepads, { null }, { null })
        val feedback = UiFeedback(File(dir, "cache"), DesktopFeedbackOutput { null })

        // Compose and the view model live on the UI thread (Swing's event thread on the desktop).
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val vm = ui { LauncherViewModel(container) }
        val scene = ui {
            ImageComposeScene(1600, 900, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    LauncherApp(vm, dispatcher, feedback)
                }
            }
        }
        ui { scene.render(0) }
        // Data arrives from the database and settings in the background.
        Thread.sleep(1500)
        ui { scene.render(500_000_000L) }
        Thread.sleep(300)
        val image = ui { scene.render(2_000_000_000L).also { scene.close() } }
        val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        File("build/render").apply { mkdirs() }.resolve("launcher.png").writeBytes(png)
        val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val distinct = (0 until 1600 step 40).flatMap { x -> (0 until 900 step 40).map { y -> pixels.getColor(x, y) } }.toSet()
        assertTrue("UI drew something", distinct.size > 3)
    }

    @Test
    fun gameSourcesPageRenders() {
        val dir = Files.createTempDirectory("launcher-sources").toFile()
        val container = DesktopContainer(dir)
        val gamepads = Gamepads(InputDispatcher())
        PlatformUi.current = DesktopPlatformUi(gamepads, { null }, { container.pcLibrary })
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(1600, 900, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme(io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme.colors.background)) {
                            GameSourcesPage(container.pcLibrary, io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings(disabledGameSources = setOf("amazon"))) {}
                        }
                    }
                }
            }
        }
        ui { scene.render(0) }
        Thread.sleep(800)
        val image = ui { scene.render(1_000_000_000L).also { scene.close() } }
        File("build/render").apply { mkdirs() }.resolve("game-sources.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun hostRolePageRenders() {
        val dir = Files.createTempDirectory("launcher-host").toFile()
        val container = DesktopContainer(dir)
        val role = container.hostRole
        role.update { it.copy(serverEnabled = true, webEnabled = true) }
        role.pairing.open()
        role.devices.add("Odin 2 Portal", "AB".repeat(32), io.github.thecodingdad.moonlightlauncher.desktop.host.Permissions.all, "192.168.1.20")
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null }) { role }
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(1600, 1400, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme(io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme.colors.background)) {
                            HostRolePage(role)
                        }
                    }
                }
            }
        }
        try {
            ui { scene.render(0) }
            Thread.sleep(800)
            val image = ui { scene.render(1_000_000_000L).also { scene.close() } }
            File("build/render").apply { mkdirs() }.resolve("host-role.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            role.stop()
        }
    }
}
