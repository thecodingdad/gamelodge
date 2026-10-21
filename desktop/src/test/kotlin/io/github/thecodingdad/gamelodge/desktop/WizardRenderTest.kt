package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import io.github.thecodingdad.gamelodge.ui.LauncherApp
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.ArtworkPlatform
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Every step of the setup wizard comes up on a fresh install (the wizard starts by itself), in
 * landscape and in portrait; the pictures go to build/render/wizard-*.png for a look.
 */
class WizardRenderTest {
    private fun <T> ui(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun render(step: WizardStep, width: Int, height: Int, name: String) {
        val dir = Files.createTempDirectory("wizard-${step.name.lowercase()}").toFile()
        val container = DesktopContainer(dir)
        ArtworkFiles.init(dir)
        ArtworkPlatform.current = DesktopArtwork
        val dispatcher = InputDispatcher()
        val startup = Startup(dir)
        PlatformUi.current = DesktopPlatformUi(
            Gamepads(dispatcher), { null }, { container.pcLibrary },
            { HostServices(container.hostRole, null, container.secrets) }, startup,
        )
        // A fresh install standing at this step (as after a restart in the middle of the wizard).
        // The restore step only exists on the backup path.
        val restore = if (step == WizardStep.RESTORE) File(dir, "gamelodge-backup.json").path else ""
        runBlocking { container.settings.update { it.copy(setupDone = false, setupStep = step.name, setupRestore = restore) } }
        val feedback = UiFeedback(File(dir, "cache"), DesktopFeedbackOutput { null })
        val vm = ui { LauncherViewModel(container) }
        val scene = ui {
            ImageComposeScene(width, height, Density(1.5f)) {
                CompositionLocalProvider(LocalContainer provides container) { LauncherApp(vm, dispatcher, feedback) }
            }
        }
        ui { scene.render(0) }
        Thread.sleep(1500)
        ui { scene.render(500_000_000L) }
        Thread.sleep(500)
        ui { scene.render(1_000_000_000L) }
        Thread.sleep(300)
        val image = ui { scene.render(2_000_000_000L).also { scene.close() } }
        File("build/render").apply { mkdirs() }.resolve("$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val distinct = (0 until width step 40).flatMap { x -> (0 until height step 40).map { y -> pixels.getColor(x, y) } }.toSet()
        assertTrue("$name drew something", distinct.size > 3)
    }

    @Test
    fun everyStepRendersInLandscapeAndPortrait() {
        val steps = DesktopPlatformUi(Gamepads(InputDispatcher()), { null }, { null }).wizardSteps + WizardStep.RESTORE
        steps.forEach { step ->
            render(step, 1600, 900, "wizard-${step.name.lowercase()}-landscape")
            render(step, 900, 1600, "wizard-${step.name.lowercase()}-portrait")
        }
    }
}
