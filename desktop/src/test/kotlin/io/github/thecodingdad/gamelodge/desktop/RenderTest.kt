package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import io.github.thecodingdad.gamelodge.ui.LauncherApp
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.ArtworkPlatform
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.thecodingdad.gamelodge.desktop.stream.DesktopProfiles
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
    fun runningTasksShowInTheStatusBarAndPopup() {
        val dir = Files.createTempDirectory("launcher-tasks").toFile()
        val container = DesktopContainer(dir)
        ArtworkFiles.init(dir)
        ArtworkPlatform.current = DesktopArtwork
        val dispatcher = InputDispatcher()
        PlatformUi.current = DesktopPlatformUi(Gamepads(dispatcher), { null }, { null })
        val feedback = UiFeedback(File(dir, "cache"), DesktopFeedbackOutput { null })
        val tasks = io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks
        tasks.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask("a", Res.string.task_box_art, listOf("Gaming-PC"), 12, 40))
        tasks.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask("b", Res.string.task_pc_scan, done = 5, total = 8, detail = "Steam, Epic Games"))
        tasks.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask("c", Res.string.task_host_sync))
        tasks.popupOpen = true
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        try {
            val vm = ui { LauncherViewModel(container) }
            val scene = ui {
                ImageComposeScene(1600, 900, Density(1.5f)) {
                    CompositionLocalProvider(LocalContainer provides container) { LauncherApp(vm, dispatcher, feedback) }
                }
            }
            ui { scene.render(0) }
            Thread.sleep(1500)
            ui { scene.render(500_000_000L) }
            Thread.sleep(300)
            val image = ui { scene.render(2_000_000_000L).also { scene.close() } }
            File("build/render").apply { mkdirs() }.resolve("tasks.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            listOf("a", "b", "c").forEach(tasks::remove)
            tasks.popupOpen = false
        }
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
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            GameSourcesPage(container.pcLibrary, io.github.thecodingdad.gamelodge.data.settings.LauncherSettings(disabledGameSources = setOf("amazon"))) {}
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
    fun aboutPageRenders() {
        val dir = Files.createTempDirectory("launcher-about").toFile()
        val container = DesktopContainer(dir)
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null })
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val settings = io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()
        val scene = ui {
            ImageComposeScene(1600, 1400, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(settings) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            io.github.thecodingdad.gamelodge.ui.screens.settings.AboutSettings {}
                        }
                    }
                }
            }
        }
        ui { scene.render(0) }
        Thread.sleep(1000)
        val image = ui { scene.render(500_000_000L).also { scene.close() } }
        File("build/render").apply { mkdirs() }.resolve("about.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun dataPageRenders() {
        val dir = Files.createTempDirectory("launcher-data").toFile()
        val container = DesktopContainer(dir)
        ArtworkFiles.init(dir)
        File(dir, "artwork/a.png").writeBytes(ByteArray(3_000_000))
        File(dir, "own-artwork").apply { mkdirs() }.resolve("old.png").apply { writeBytes(ByteArray(200_000)); setLastModified(0) }
        File(dir, "logs").apply { mkdirs() }.resolve("launcher.log").writeBytes(ByteArray(400_000))
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null })
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val vm = ui { LauncherViewModel(container) }
        val settings = io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()
        val scene = ui {
            ImageComposeScene(1600, 3400, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(settings) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            io.github.thecodingdad.gamelodge.ui.screens.settings.DataSettings(settings, {}, vm)
                        }
                    }
                }
            }
        }
        ui { scene.render(0) }
        Thread.sleep(1500)
        ui { scene.render(500_000_000L) }
        Thread.sleep(300)
        val image = ui { scene.render(1_000_000_000L).also { scene.close() } }
        File("build/render").apply { mkdirs() }.resolve("data.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun emulatorsPageRenders() {
        val dir = Files.createTempDirectory("launcher-emu").toFile()
        val container = DesktopContainer(dir)
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { container.pcLibrary })
        val emulation = io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulation(
            folders = listOf(io.github.thecodingdad.gamelodge.desktop.emulation.PcRomFolder("Y:\\ROMs"), io.github.thecodingdad.gamelodge.desktop.emulation.PcRomFolder("Y:\\Switch", "Nintendo Switch")),
            emulators = listOf(
                io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulator("d", "Dolphin", "Y:\\Emu\\Dolphin.exe", "-b -e \"{rom}\"", listOf(io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulatorPlatform("Nintendo GameCube"), io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulatorPlatform("Wii"))),
                io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulator("e", "Eden", "Y:\\Emu\\eden.exe", "-f -g \"{rom}\"", listOf(io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulatorPlatform("Nintendo Switch"))),
                io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulator("r", "Ryujinx", "Y:\\Emu\\Ryujinx.exe", "\"{rom}\"", listOf(io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulatorPlatform("Nintendo Switch"))),
            ),
        )
        val settings = io.github.thecodingdad.gamelodge.data.settings.LauncherSettings(pcEmulation = emulation.encode())
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(1600, 1800, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(settings) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            io.github.thecodingdad.gamelodge.desktop.emulation.EmulatorsPage(container.pcLibrary, settings, {}, File(dir, "covers"))
                        }
                    }
                }
            }
        }
        ui { scene.render(0) }
        Thread.sleep(800)
        val image = ui { scene.render(1_000_000_000L).also { scene.close() } }
        File("build/render").apply { mkdirs() }.resolve("emulators.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun hostRolePageRenders() {
        val dir = Files.createTempDirectory("launcher-host").toFile()
        val container = DesktopContainer(dir)
        val role = container.hostRole
        role.update { it.copy(serverEnabled = true, webEnabled = true) }
        role.pairing.open()
        role.devices.add("Odin 2 Portal", "AB".repeat(32), io.github.thecodingdad.gamelodge.host.Permissions.all, "192.168.1.20")
        val services = HostServices(role, container.hostSync, container.secrets)
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null }, { services })
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(1600, 1500, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            HostRolePage(services)
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

    @Test
    fun streamingHostPageRenders() {
        val dir = Files.createTempDirectory("launcher-streaming-host").toFile()
        val container = DesktopContainer(dir)
        val role = container.hostRole
        role.update { it.copy(serverEnabled = true, webEnabled = true) }
        role.pairing.open()
        role.devices.add("Odin 2 Portal", "AB".repeat(32), io.github.thecodingdad.gamelodge.host.Permissions.all, "192.168.1.20")
        val services = HostServices(role, container.hostSync, container.secrets)
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null }, { services })
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(1600, 1500, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            StreamingHostPage(role, container.hostSync, container.secrets)
                        }
                    }
                }
            }
        }
        try {
            ui { scene.render(0) }
            Thread.sleep(1500)
            ui { scene.render(500_000_000L) }
            Thread.sleep(300)
            val image = ui { scene.render(1_000_000_000L).also { scene.close() } }
            File("build/render").apply { mkdirs() }.resolve("streaming-host.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            role.stop()
        }
    }

    @Test
    fun profileEditorRenders() {
        val dir = Files.createTempDirectory("launcher-profiles").toFile()
        val container = DesktopContainer(dir)
        val uuid = container.profiles.create("Handheld")
        container.profiles.values(uuid).set("list_resolution", "1920x1080")
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null }, profiles = container.profiles)
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(1600, 3000, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            io.github.thecodingdad.gamelodge.ui.screens.profiles.ProfileEditor(container.profiles, uuid, "Handheld") {}
                        }
                    }
                }
            }
        }
        ui { scene.render(0) }
        Thread.sleep(800)
        val image = ui { scene.render(1_000_000_000L).also { scene.close() } }
        File("build/render").apply { mkdirs() }.resolve("profile-editor.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        // Narrow (portrait): controls move below their texts.
        val narrow = ui {
            ImageComposeScene(700, 2400, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
                            io.github.thecodingdad.gamelodge.ui.screens.profiles.ProfileEditor(container.profiles, uuid, "Handheld") {}
                        }
                    }
                }
            }
        }
        ui { narrow.render(0) }
        Thread.sleep(500)
        val narrowImage = ui { narrow.render(1_000_000_000L).also { narrow.close() } }
        File("build/render/profile-editor-narrow.png").writeBytes(narrowImage.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        org.junit.Assert.assertTrue(DesktopProfiles.options(container.profiles.values(uuid)).containsAll(listOf("--resolution", "1920x1080")))
    }

    @Test
    fun quickTilesHaveWindowControls() {
        val dir = Files.createTempDirectory("launcher-tiles").toFile()
        val container = DesktopContainer(dir)
        val controls = WindowControls()
        PlatformUi.current = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null }, windowControls = controls)
        var labels = emptyList<String>()
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        val scene = ui {
            ImageComposeScene(800, 600, Density(1f)) {
                CompositionLocalProvider(LocalContainer provides container) {
                    io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme(io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()) {
                        labels = io.github.thecodingdad.gamelodge.ui.shell.rememberQuickTiles(listOf("controllers", "wifi"), {}, {}).map { it.key }
                    }
                }
            }
        }
        ui { scene.render(0); scene.close() }
        org.junit.Assert.assertEquals(listOf("controllers", "window_mode", "exit"), labels)
    }
}
