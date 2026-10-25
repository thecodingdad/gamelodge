package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CPU backdrop blur captures the content and draws it softened (no GPU blur filter). */
class SnapshotBlurTest {
    @Test
    fun blursTheCapturedContent() {
        val scene = ImageComposeScene(200, 100) {
            Box(Modifier.fillMaxSize().then(snapshotBlur(active = true, radius = 14.dp, full = 14.dp, background = Color.Black))) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(100.dp).fillMaxHeight().background(Color.White))
                    Box(Modifier.width(100.dp).fillMaxHeight().background(Color.Black))
                }
            }
        }
        // The blur runs off the UI thread: render until the softened edge shows up.
        var edge = 0
        for (frame in 0 until 100) {
            val bitmap = Bitmap.makeFromImage(scene.render(frame * 16_000_000L))
            edge = bitmap.getColor(100, 50) and 0xff
            if (edge in 40..215) break
            Thread.sleep(20)
        }
        scene.close()
        assertTrue("edge is mixed grey, was $edge", edge in 40..215)
    }
}
