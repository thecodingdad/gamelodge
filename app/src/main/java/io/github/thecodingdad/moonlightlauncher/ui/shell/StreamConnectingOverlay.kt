package io.github.thecodingdad.moonlightlauncher.ui.shell

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import org.jetbrains.compose.resources.stringResource
import com.limelight.Game
import com.limelight.utils.SpinnerDialog
import io.github.thecodingdad.moonlightlauncher.AppContainer
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import io.github.thecodingdad.moonlightlauncher.resources.*

/**
 * Replaces Artemis' small "connecting" progress dialog in the stream activity with the
 * launcher's full-screen start screen (game artwork, step, cancel with B).
 */
object StreamConnectingOverlay {
    fun install(container: AppContainer) {
        SpinnerDialog.setOverlayFactory { activity, _, message, cancelable, onCancel ->
            val entry = container.launcher.streamingEntry
            if (activity !is Game || entry == null) null else create(activity, container, entry, message, cancelable, onCancel)
        }
    }

    private fun create(
        activity: Activity,
        container: AppContainer,
        entry: io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry,
        message: String,
        cancelable: Boolean,
        onCancel: Runnable,
    ): SpinnerDialog.Overlay {
        val detail = mutableStateOf(message)
        val dialog = ComponentDialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(cancelable)
        dialog.setOnCancelListener { onCancel.run() }
        dialog.setContentView(
            ComposeView(activity).apply {
                setContent {
                    val settings by container.settings.settings.collectAsState(LauncherSettings())
                    LauncherTheme(settings) {
                        StreamProgressContent(
                            entry = entry,
                            step = stringResource(Res.string.step_connecting_stream),
                            detail = detail.value.takeIf { it.isNotBlank() },
                            timerKey = Unit,
                            showTimer = true,
                            onCancel = if (cancelable) ({ dialog.cancel() }) else null,
                        )
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        return object : SpinnerDialog.Overlay {
            override fun show() = dialog.show()
            override fun setMessage(message: String) { detail.value = message }
            override fun dismiss() = dialog.dismiss()
            override fun isShowing() = dialog.isShowing
        }
    }
}
