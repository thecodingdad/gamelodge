package io.github.thecodingdad.gamelodge.ui.shell

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.limelight.Game
import com.limelight.StreamControl
import io.github.thecodingdad.gamelodge.AppContainer
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.stream.ConnectionSample
import io.github.thecodingdad.gamelodge.stream.StreamQuality
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme

/**
 * The connection warning over a stream: Artemis reports the connection once a second, a small
 * window at the top of the stream shows the warning while there is one. It never takes focus or
 * touches (the game keeps both) and goes with the stream.
 */
object ConnectionWarningOverlay {
    private var dialog: ComponentDialog? = null
    private var owner: Game? = null

    fun install(container: AppContainer) {
        StreamQuality.follow(container.settings, container.scope)
        // Called on the main thread.
        StreamControl.qualityListener = StreamControl.QualityListener { rtt, variance, frameLoss, audioDrops, perfOverlay ->
            StreamQuality.sample(ConnectionSample(rtt, variance, frameLoss, audioDrops, perfOverlay))
            update(container)
        }
    }

    private fun update(container: AppContainer) {
        val game = Game.instance
        if (StreamQuality.warning.value == null || game == null || game.isFinishing) {
            hide()
            return
        }
        if (owner !== game || dialog?.isShowing != true) {
            hide()
            show(game, container)
        }
    }

    /** Main thread; also when the stream's activity goes. */
    fun hide() {
        dialog?.let { runCatching { it.dismiss() } }
        dialog = null
        owner = null
    }

    private fun show(game: Game, container: AppContainer) {
        val d = ComponentDialog(game, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(
            ComposeView(game).apply {
                setContent {
                    val settings by container.settings.settings.collectAsState(LauncherSettings())
                    val warning by StreamQuality.warning.collectAsState()
                    LauncherTheme(settings) {
                        warning?.let { ConnectionWarningPill(it, Modifier.padding(top = 12.dp)) }
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        d.window?.apply {
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // The game keeps focus and input (also its full screen and hidden system bars).
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        runCatching { d.show() }.onSuccess {
            dialog = d
            owner = game
        }
    }
}
