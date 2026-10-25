package io.github.thecodingdad.gamelodge


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
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.components.ControllerKeyboardHost
import io.github.thecodingdad.gamelodge.ui.components.KeyboardController
import io.github.thecodingdad.gamelodge.ui.components.LocalButtonLayout
import io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.LocalShowHintBar
import io.github.thecodingdad.gamelodge.ui.components.OverlayOutlet
import io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.feedbackConfig
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.dispatch
import io.github.thecodingdad.gamelodge.ui.input.dispatchMotion
import io.github.thecodingdad.gamelodge.ui.input.LocalInputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers
import io.github.thecodingdad.gamelodge.ui.shell.HintHolder
import io.github.thecodingdad.gamelodge.ui.shell.LocalHintHolder
import io.github.thecodingdad.gamelodge.ui.shell.QuickMenu
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
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
        val tag = newBase.getSharedPreferences(io.github.thecodingdad.gamelodge.util.AppLanguage.PREFS, android.content.Context.MODE_PRIVATE).getString(io.github.thecodingdad.gamelodge.util.AppLanguage.KEY, "system") ?: "system"
        super.attachBaseContext(io.github.thecodingdad.gamelodge.util.AppLanguage.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        feedback = UiFeedback(cacheDir, io.github.thecodingdad.gamelodge.ui.feedback.AndroidFeedbackOutput(this)).also { it.load() }
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
        io.github.thecodingdad.gamelodge.system.EdgeHandle.refresh(this)
    }

    override fun onPause() {
        resumed = false
        io.github.thecodingdad.gamelodge.system.EdgeHandle.refresh(this)
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
        val density = remember(baseDensity, uiScale, window) {
            Density(io.github.thecodingdad.gamelodge.ui.theme.fittedDensity(baseDensity.density * uiScale, window.width, window.height, io.github.thecodingdad.gamelodge.data.settings.CardSize.MEDIUM), baseDensity.fontScale)
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
                io.github.thecodingdad.gamelodge.ui.input.RightStickScrollDriver()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val controllers = rememberControllers()
                    CompositionLocalProvider(
                        LocalCompactWidth provides (maxWidth < 720.dp),
                        LocalShowHintBar provides (settings.showHintBar && controllers.isNotEmpty()),
                    ) {
                        val running by vm.runningSessions.collectAsState()
                        QuickMenu(
                            visible = menuOpen,
                            onDismiss = { menuOpen = false },
                            running = running,
                            onResume = { session ->
                                val entry = session.entry
                                val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                                if (entry != null && target != null) vm.launch(entry, target)
                            },
                            openHostSettings = { openLauncher() },
                            openControllerSettings = ::openLauncher,
                            openHaSettings = ::openLauncher,
                        )
                        ControllerKeyboardHost(keyboard)
                        OverlayOutlet()
                        io.github.thecodingdad.gamelodge.ui.shell.NoticeHost()
                    }
                }
            }
        }
    }

    // Lint wrongly flags ComponentActivity.dispatchKeyEvent as restricted (androidx.core override).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val e = io.github.thecodingdad.gamelodge.ui.input.AndroidPad.inLayout(event, vm.settings.value)
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BACK)
        ) {
            feedback.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.BACK)
        }
        if (dispatcher.dispatch(e)) return true
        // Unhandled, Android swaps X / Y for a fallback key (Y = confirm on some pads): they stay without effect.
        return super.dispatchKeyEvent(e) || io.github.thecodingdad.gamelodge.ui.input.AndroidPad.noFallback(e.keyCode)
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
