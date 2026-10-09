package io.github.thecodingdad.moonlightlauncher

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.thecodingdad.moonlightlauncher.data.settings.ButtonLayout
import io.github.thecodingdad.moonlightlauncher.system.LauncherMode
import io.github.thecodingdad.moonlightlauncher.ui.LauncherApp
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.input.InputDispatcher
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private val dispatcher = InputDispatcher()
    private lateinit var feedback: UiFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        feedback = UiFeedback(this).also { it.load() }
        setContent { LauncherApp(vm, dispatcher, feedback) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we are the home app: back to the start screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.homePressed.tryEmit(Unit)
    }

    override fun onStart() {
        super.onStart()
        container.hosts.startPolling()
    }

    override fun onStop() {
        container.hosts.stopPolling()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back from a game or stream: close the play session, undo a one-off profile.
        container.launcher.restoreProfile()
        lifecycleScope.launch {
            container.playtime.finishOpenSession()
            // If the user made us the home app (also via Android settings), launcher mode is on.
            val mode = LauncherMode(this@MainActivity)
            if (mode.isDefaultHome && !vm.settings.value.launcherMode) {
                container.settings.update { it.copy(launcherMode = true) }
            }
        }
    }

    // Lint wrongly flags ComponentActivity.dispatchKeyEvent as restricted (androidx.core override).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val e = if (vm.settings.value.buttonLayout == ButtonLayout.NINTENDO) {
            val swapped = InputDispatcher.swapForNintendo(event.keyCode)
            if (swapped != event.keyCode) {
                KeyEvent(
                    event.downTime, event.eventTime, event.action, swapped, event.repeatCount,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                )
            } else event
        } else event
        if (dispatcher.dispatch(e)) return true
        return super.dispatchKeyEvent(e)
    }
}
