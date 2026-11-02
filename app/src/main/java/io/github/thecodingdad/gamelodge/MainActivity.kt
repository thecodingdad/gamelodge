package io.github.thecodingdad.gamelodge


import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.system.TvSession
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.input.ButtonCombos
import io.github.thecodingdad.gamelodge.input.handle
import io.github.thecodingdad.gamelodge.input.ComboAccessibilityService
import io.github.thecodingdad.gamelodge.input.ComboAction
import io.github.thecodingdad.gamelodge.system.LauncherMode
import io.github.thecodingdad.gamelodge.ui.LauncherApp
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.dispatch
import io.github.thecodingdad.gamelodge.ui.input.dispatchMotion
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels {
        androidx.lifecycle.viewmodel.viewModelFactory { initializer { LauncherViewModel(container) } }
    }
    private val dispatcher = InputDispatcher()
    private lateinit var feedback: UiFeedback

    // Android < 13: the launcher's own language on this activity's resources.
    override fun attachBaseContext(newBase: android.content.Context) {
        val tag = newBase.getSharedPreferences(io.github.thecodingdad.gamelodge.util.AppLanguage.PREFS, android.content.Context.MODE_PRIVATE).getString(io.github.thecodingdad.gamelodge.util.AppLanguage.KEY, "system") ?: "system"
        super.attachBaseContext(io.github.thecodingdad.gamelodge.util.AppLanguage.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One launcher at a time. Started another way (Home while it ran in a normal task, an
        // explicit start, the TV), the older one goes: both would draw the process' popups, and
        // the visible one could end up behind the other's popup backdrop (black screen).
        current?.get()?.takeIf { it !== this && !it.isFinishing && !it.isDestroyed }?.let { old ->
            if (old.taskId != taskId) old.finishAndRemoveTask() else old.finish()
        }
        current = java.lang.ref.WeakReference(this)
        // Nobody looks at the hosts while the screensaver shows: no polling meanwhile, right away again after it.
        lifecycleScope.launch {
            io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.active.collect { idle ->
                if (idle) container.hosts.stopPolling() else if (started) container.hosts.startPolling()
            }
        }
        enableEdgeToEdge()
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
        // Player LEDs (Joy-Cons): re-apply when controllers connect/disconnect or their settings change.
        getSystemService(android.hardware.input.InputManager::class.java)?.registerInputDeviceListener(
            object : android.hardware.input.InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(id: Int) = controllerLights.refresh()
                override fun onInputDeviceRemoved(id: Int) = controllerLights.refresh()
                override fun onInputDeviceChanged(id: Int) = controllerLights.refresh()
            },
            null,
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.controllerSettings.all.collect { controllerLights.refresh() }
            }
        }
        feedback = UiFeedback(cacheDir, io.github.thecodingdad.gamelodge.ui.feedback.AndroidFeedbackOutput(this)).also { it.load() }
        handleQuickMenuExtra(intent)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalContainer provides container) { LauncherApp(vm, dispatcher, feedback) }
        }
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
        val home = intent.hasCategory(Intent.CATEGORY_HOME) || intent.getBooleanExtra(EXTRA_HOME, false)
        intent.removeExtra(EXTRA_HOME)
        if (home && !returningFromBackground) vm.homePressed.tryEmit(Unit)
        handleQuickMenuExtra(intent)
    }

    /** Quick menu combination pressed in another app (accessibility service). */
    private fun handleQuickMenuExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_QUICK_MENU, false) == true) {
            intent.removeExtra(EXTRA_QUICK_MENU)
            vm.quickMenuRequests.tryEmit(true)
        }
    }

    /** Between onStart and onStop. */
    private var started = false

    override fun onStart() {
        super.onStart()
        started = true
        if (!io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.active.value) container.hosts.startPolling()
        container.ha.client.start()
    }

    /** Stopped (another app, stream) and not resumed since: Home only returns to the launcher. */
    private var returningFromBackground = false

    /** Player-LED driver for controllers that have them (Joy-Cons). */
    private val controllerLights by lazy { io.github.thecodingdad.gamelodge.stream.ControllerLights(this, container.controllerSettings) }

    override fun onDestroy() {
        controllerLights.releaseAll()
        super.onDestroy()
    }

    override fun onStop() {
        returningFromBackground = true
        started = false
        // An older instance stopping after the new one started must not stop the new one's polling.
        if (current?.get() === this) {
            container.hosts.stopPolling()
            container.ha.client.stop()
        }
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.touch()
    }

    override fun onPause() {
        resumed = false
        io.github.thecodingdad.gamelodge.system.EdgeHandle.refresh(this)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        returningFromBackground = false
        resumed = true
        // Back in the launcher the host no longer owns the player LEDs: set them to our patterns again.
        controllerLights.refresh()
        container.emulation.rescanIfStale()
        // Also picks up a just granted overlay permission.
        io.github.thecodingdad.gamelodge.system.EdgeHandle.refresh(this)
        vm.touch()
        // Back from a game or stream: close the play session, undo a one-off profile, run the end actions.
        container.launcher.restoreProfile()
        container.launcher.ended()
        lifecycleScope.launch {
            val closed = container.playtime.finishOpenSession()
            container.usage.sync()
            // A game or stream ended: its session goes to the host right away (also brings the others' news).
            if (closed != null) launch { container.library.syncPlayData() }
            if (closed != null && closed.targetId.startsWith("remote:")) {
                val target = container.database.libraryDao().getTarget(closed.targetId)
                target?.hostUuid?.let { container.autoSleep.scheduleAfterStream(it) }
                container.launcher.streamEnded()
            }
            // If the user made us the home app (also via Android settings), launcher mode is on.
            val mode = LauncherMode(this@MainActivity)
            if (mode.isDefaultHome && !vm.settings.value.launcherMode) {
                container.settings.update { it.copy(launcherMode = true) }
            }
        }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) io.github.thecodingdad.gamelodge.input.StarterPad.onPointer()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        dispatcher.dispatchMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }

    // Lint wrongly flags ComponentActivity.dispatchKeyEvent as restricted (androidx.core override).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        io.github.thecodingdad.gamelodge.input.StarterPad.onKey(event)
        // Button combinations; the accessibility service handles them already when enabled.
        if (ComboAccessibilityService.instance == null || ButtonCombos.isRecording) {
            val consumed = ButtonCombos.handle(event) { action, controller ->
                when (action) {
                    ComboAction.HOME -> vm.homePressed.tryEmit(Unit)
                    ComboAction.QUICK_MENU -> vm.quickMenuRequests.tryEmit(false)
                    else -> io.github.thecodingdad.gamelodge.input.AndroidCombos.run(this, action, controller)
                }
            }
            if (consumed) return true
        }
        val e = io.github.thecodingdad.gamelodge.ui.input.AndroidPad.inLayout(event, vm.settings.value)
        // Button test: controller buttons only light up there, nothing else happens.
        if (dispatcher.buttonTest != null) {
            io.github.thecodingdad.gamelodge.ui.input.AndroidPad.testName(e)?.let { name ->
                if (e.repeatCount == 0) dispatcher.reportTest(name, e.action == KeyEvent.ACTION_DOWN)
                return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            feedback.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.BACK)
        }
        // Confirm (A) and back (B) otherwise reach the focus system through a fallback Android
        // derives from the ORIGINAL button, before our layout swap — so, unlike X / Y, the swap
        // never affected them. Translate the swapped event ourselves (A -> centre click, B -> back)
        // and consume the original, so the controller layout applies to confirm and back as well.
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_A || e.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            val focusKey = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_A) KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_BACK
            super.dispatchKeyEvent(
                KeyEvent(e.downTime, e.eventTime, e.action, focusKey, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source),
            )
            return true
        }
        if (dispatcher.dispatch(e)) return true
        // Unhandled, Android swaps X / Y for a fallback key (Y = confirm on some pads): they stay without effect.
        return super.dispatchKeyEvent(e) || io.github.thecodingdad.gamelodge.ui.input.AndroidPad.noFallback(e.keyCode)
    }

    companion object {
        const val EXTRA_QUICK_MENU = "io.github.thecodingdad.gamelodge.QUICK_MENU"

        /** Combination "back to GameLodge": like Home while we are the home app. */
        const val EXTRA_HOME = "io.github.thecodingdad.gamelodge.HOME"

        /** Launcher in the foreground: combinations act inside it instead of as an overlay. */
        @Volatile var resumed = false
            private set

        /** The newest instance (see onCreate). */
        private var current: java.lang.ref.WeakReference<MainActivity>? = null
    }
}
