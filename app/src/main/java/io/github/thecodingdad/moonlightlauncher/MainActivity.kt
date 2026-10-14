package io.github.thecodingdad.moonlightlauncher

import io.github.thecodingdad.moonlightlauncher.system.avoidDisplayCutout

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.system.TvSession
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
import io.github.thecodingdad.moonlightlauncher.input.ButtonCombos
import io.github.thecodingdad.moonlightlauncher.input.ComboAccessibilityService
import io.github.thecodingdad.moonlightlauncher.input.ComboAction
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
        avoidDisplayCutout(window)
        applySystemBars()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.settings.map { it.hideNavigationBar }, vm.externalDisplay) { a, b -> a to b }
                    .distinctUntilChanged().collect { applySystemBars() }
            }
        }
        // TV attached: run on the TV itself (native resolution) when the setting allows it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(vm.externalDisplay, vm.settings.map { it.tvLauncher }) { tv, on -> tv to on }
                    .distinctUntilChanged().collect { (tv, on) -> TvSession.update(this@MainActivity, tv, on) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.settings.map { it.tvModeId }.distinctUntilChanged().collect { checkTv() }
            }
        }
        feedback = UiFeedback(this).also { it.load() }
        handleQuickMenuExtra(intent)
        setContent { LauncherApp(vm, dispatcher, feedback) }
    }

    /** Status bar always hidden; navigation bar (gesture handle) optionally. Swipe shows both briefly. */
    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            // On a TV the gesture handle is always hidden (nobody swipes on a TV).
            if (vm.settings.value.hideNavigationBar || vm.externalDisplay.value != null) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** On the TV display: full screen, chosen mode, handheld side; back on the handheld: release. */
    private fun checkTv() {
        if (TvSession.isOnTv(this)) TvSession.onShownOnTv(this, vm.settings.value.tvModeId) else TvSession.onLeftTv(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Moving between displays arrives here (screen size changes).
        checkTv()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from a stream or another app can bring the bars back.
        if (hasFocus) applySystemBars() else ButtonCombos.reset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we are the home app: back to the start screen.
        // Home while the launcher is shown: to the start tab. Coming back from another app or a
        // stream, the first Home just brings back what was shown before.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !returningFromBackground) vm.homePressed.tryEmit(Unit)
        handleQuickMenuExtra(intent)
    }

    /** Quick menu combination pressed in another app (accessibility service). */
    private fun handleQuickMenuExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_MENU, false) == true) {
            intent.removeExtra(EXTRA_QUICK_MENU)
            vm.quickMenuRequests.tryEmit(true)
        }
    }

    override fun onStart() {
        super.onStart()
        container.hosts.startPolling()
        container.ha.client.start()
    }

    /** Stopped (another app, stream) and not resumed since: Home only returns to the launcher. */
    private var returningFromBackground = false

    override fun onStop() {
        returningFromBackground = true
        container.hosts.stopPolling()
        container.ha.client.stop()
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.touch()
    }

    override fun onPause() {
        resumed = false
        io.github.thecodingdad.moonlightlauncher.system.EdgeHandle.refresh(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        returningFromBackground = false
        resumed = true
        // Also picks up a just granted overlay permission.
        io.github.thecodingdad.moonlightlauncher.system.EdgeHandle.refresh(this)
        vm.touch()
        // Back from a game or stream: close the play session, undo a one-off profile.
        container.launcher.restoreProfile()
        lifecycleScope.launch {
            val closed = container.playtime.finishOpenSession()
            container.usage.sync()
            if (closed != null && closed.targetId.startsWith("remote:")) {
                val title = container.database.libraryDao().getItem(closed.itemId)?.title
                val target = container.database.libraryDao().getTarget(closed.targetId)
                val host = target?.hostName
                target?.hostUuid?.let { container.autoSleep.scheduleAfterStream(it) }
                container.ha.fireLauncherEvent(
                    "stream_stopped",
                    mapOf("game" to title, "host" to host, "duration_seconds" to (((closed.endedAt ?: 0) - closed.startedAt) / 1000).toString()),
                )
            }
            // If the user made us the home app (also via Android settings), launcher mode is on.
            val mode = LauncherMode(this@MainActivity)
            if (mode.isDefaultHome && !vm.settings.value.launcherMode) {
                container.settings.update { it.copy(launcherMode = true) }
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        dispatcher.dispatchMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }

    // Lint wrongly flags ComponentActivity.dispatchKeyEvent as restricted (androidx.core override).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Button combinations; the accessibility service handles them already when enabled.
        if (ComboAccessibilityService.instance == null || ButtonCombos.isRecording) {
            val consumed = ButtonCombos.handle(event) { action ->
                when (action) {
                    ComboAction.HOME -> vm.homePressed.tryEmit(Unit)
                    ComboAction.QUICK_MENU -> vm.quickMenuRequests.tryEmit(false)
                }
            }
            if (consumed) return true
        }
        val e = if (vm.settings.value.buttonLayout == ButtonLayout.NINTENDO) {
            val swapped = InputDispatcher.swapForNintendo(event.keyCode)
            if (swapped != event.keyCode) {
                KeyEvent(
                    event.downTime, event.eventTime, event.action, swapped, event.repeatCount,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                )
            } else event
        } else event
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            feedback.play(io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound.BACK)
        }
        if (dispatcher.dispatch(e)) return true
        return super.dispatchKeyEvent(e)
    }

    companion object {
        const val EXTRA_QUICK_MENU = "io.github.thecodingdad.moonlightlauncher.QUICK_MENU"

        /** Launcher in the foreground: combinations act inside it instead of as an overlay. */
        @Volatile var resumed = false
            private set
    }
}
