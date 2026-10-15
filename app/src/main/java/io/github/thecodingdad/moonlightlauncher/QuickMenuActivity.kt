package io.github.thecodingdad.moonlightlauncher


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.thecodingdad.moonlightlauncher.data.settings.ButtonLayout
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.components.ControllerKeyboardHost
import io.github.thecodingdad.moonlightlauncher.ui.components.KeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalButtonLayout
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalShowHintBar
import io.github.thecodingdad.moonlightlauncher.ui.components.OverlayOutlet
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedbackConfig
import io.github.thecodingdad.moonlightlauncher.ui.input.InputDispatcher
import io.github.thecodingdad.moonlightlauncher.ui.input.dispatch
import io.github.thecodingdad.moonlightlauncher.ui.input.dispatchMotion
import io.github.thecodingdad.moonlightlauncher.ui.input.LocalInputDispatcher
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers
import io.github.thecodingdad.moonlightlauncher.ui.shell.ControllerPopup
import io.github.thecodingdad.moonlightlauncher.ui.shell.HintHolder
import io.github.thecodingdad.moonlightlauncher.ui.shell.HostInfoPopup
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalHintHolder
import io.github.thecodingdad.moonlightlauncher.ui.shell.QuickMenu
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

/**
 * The quick menu on top of another app or the stream: a transparent activity in its own task,
 * so the app behind stays visible (it is only paused, the stream keeps running).
 */
class QuickMenuActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels {
        androidx.lifecycle.viewmodel.viewModelFactory { initializer { LauncherViewModel(container) } }
    }
    private val dispatcher = InputDispatcher()
    private lateinit var feedback: UiFeedback
    private var menuOpen by mutableStateOf(true)

    // Android < 13: the launcher's own language on this activity's resources.
    override fun attachBaseContext(newBase: android.content.Context) {
        val tag = newBase.getSharedPreferences(io.github.thecodingdad.moonlightlauncher.util.AppLanguage.PREFS, android.content.Context.MODE_PRIVATE).getString(io.github.thecodingdad.moonlightlauncher.util.AppLanguage.KEY, "system") ?: "system"
        super.attachBaseContext(io.github.thecodingdad.moonlightlauncher.util.AppLanguage.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        feedback = UiFeedback(cacheDir, io.github.thecodingdad.moonlightlauncher.ui.feedback.AndroidFeedbackOutput(this)).also { it.load() }
        setContent {
            CompositionLocalProvider(LocalContainer provides container) { Content() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The combination again while open: close.
        if (intent.getBooleanExtra(EXTRA_TOGGLE, false)) menuOpen = false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        io.github.thecodingdad.moonlightlauncher.system.EdgeHandle.refresh(this)
    }

    override fun onPause() {
        resumed = false
        io.github.thecodingdad.moonlightlauncher.system.EdgeHandle.refresh(this)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        container.ha.client.start()
    }

    override fun onStop() {
        container.ha.client.stop()
        super.onStop()
        // Leaving for another app (tile, Android settings): the menu is done.
        if (!isFinishing) finish()
    }

    /** Opens the launcher itself (settings links in the menu and its popups). */
    private fun openLauncher() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @androidx.compose.runtime.Composable
    private fun Content() {
        val settings by vm.settings.collectAsState()
        val keyboard = remember { KeyboardController() }
        val hints = remember { HintHolder() }
        LaunchedEffect(settings) { feedback.configure(settings.feedbackConfig()) }
        // Opened with a controller combination: show the focus right away (not when opened by touch).
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { if (!intent.getBooleanExtra(EXTRA_TOUCH, false)) inputMode.requestInputMode(InputMode.Keyboard) }
        LaunchedEffect(menuOpen) {
            if (!menuOpen) {
                delay(250) // let the panel slide out
                finish()
            }
        }
        val baseDensity = LocalDensity.current
        val external by vm.externalDisplay.collectAsState()
        val uiScale = (if (settings.tvScaleAuto && external != null) settings.tvScale else settings.uiScale) / 100f
        // Same size as the launcher, also on small handhelds.
        val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val density = remember(baseDensity, uiScale, window, settings.cardSize) {
            Density(io.github.thecodingdad.moonlightlauncher.ui.theme.fittedDensity(baseDensity.density * uiScale, window.width, window.height, settings.cardSize), baseDensity.fontScale)
        }
        LauncherTheme(settings) {
            CompositionLocalProvider(
                LocalDensity provides density,
                LocalInputDispatcher provides dispatcher,
                LocalUiFeedback provides feedback,
                LocalKeyboardController provides keyboard,
                LocalHintHolder provides hints,
                LocalButtonLayout provides settings.buttonLayout,
            ) {
                io.github.thecodingdad.moonlightlauncher.ui.input.RightStickScrollDriver()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val controllers = rememberControllers()
                    CompositionLocalProvider(
                        LocalCompactWidth provides (maxWidth < 720.dp),
                        LocalShowHintBar provides (settings.showHintBar && controllers.isNotEmpty()),
                    ) {
                        val profiles by vm.profiles.collectAsState()
                        val activeProfile by vm.activeProfile.collectAsState()
                        val hosts by vm.hosts.collectAsState()
                        val running by vm.runningSessions.collectAsState()
                        var hostInfoFor by remember { mutableStateOf<String?>(null) }
                        var controllerPopup by remember { mutableStateOf(false) }
                        PadAction(PadButton.Y) { menuOpen = false }
                        PadAction(PadButton.SELECT_HOLD) { menuOpen = false }
                        QuickMenu(
                            visible = menuOpen,
                            onDismiss = { menuOpen = false },
                            profiles = profiles,
                            activeProfile = activeProfile,
                            onProfile = vm::setActiveProfile,
                            onHost = { hostInfoFor = it },
                            onControllers = { controllerPopup = true },
                            openHaSettings = ::openLauncher,
                        )
                        ControllerPopup(
                            visible = controllerPopup, controllers = controllers,
                            onOpenSettings = ::openLauncher,
                            onDismiss = { controllerPopup = false },
                        )
                        io.github.thecodingdad.moonlightlauncher.ui.shell.HostListPopup(
                            visible = hostInfoFor == io.github.thecodingdad.moonlightlauncher.ui.shell.ALL_HOSTS,
                            hosts = hosts.values,
                            runningGame = { uuid -> running.firstOrNull { it.host.uuid == uuid }?.appName },
                            onSelect = { hostInfoFor = it },
                            onDismiss = { hostInfoFor = null },
                        )
                        HostInfoPopup(
                            host = hostInfoFor?.let { hosts[it] },
                            runningGame = running.firstOrNull { it.host.uuid == hostInfoFor }?.appName,
                            profiles = profiles,
                            activeProfile = activeProfile,
                            onProfile = vm::setActiveProfile,
                            onEditProfiles = ::openLauncher,
                            onDismiss = { hostInfoFor = null },
                            openSettings = { openLauncher() },
                        )
                        ControllerKeyboardHost(keyboard)
                        OverlayOutlet()
                    }
                }
            }
        }
    }

    // Lint wrongly flags ComponentActivity.dispatchKeyEvent as restricted (androidx.core override).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val e = if (vm.settings.value.buttonLayout == ButtonLayout.NINTENDO) {
            val swapped = io.github.thecodingdad.moonlightlauncher.ui.input.AndroidPad.swapForNintendo(event.keyCode)
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

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        dispatcher.dispatchMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }

    companion object {
        private const val EXTRA_TOGGLE = "toggle"
        private const val EXTRA_TOUCH = "touch"

        @Volatile var resumed = false
            private set

        /** Opens the overlay, or closes it when it is already showing. */
        fun toggle(context: Context, touch: Boolean = false) {
            context.startActivity(intent(context, touch))
        }

        fun intent(context: Context, touch: Boolean): Intent =
            Intent(context, QuickMenuActivity::class.java)
                .putExtra(EXTRA_TOGGLE, resumed)
                .putExtra(EXTRA_TOUCH, touch)
                // No user-leave hint: the stream must not switch to picture-in-picture.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
