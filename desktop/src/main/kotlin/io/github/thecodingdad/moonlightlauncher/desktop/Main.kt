package io.github.thecodingdad.moonlightlauncher.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import io.github.thecodingdad.moonlightlauncher.resources.Res
import io.github.thecodingdad.moonlightlauncher.resources.tray_open
import io.github.thecodingdad.moonlightlauncher.resources.tray_quit
import io.github.thecodingdad.moonlightlauncher.resources.tray_web
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.thecodingdad.moonlightlauncher.LocalContainer
import io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkFiles
import io.github.thecodingdad.moonlightlauncher.data.settings.ButtonLayout
import io.github.thecodingdad.moonlightlauncher.ui.LauncherApp
import io.github.thecodingdad.moonlightlauncher.ui.LauncherViewModel
import io.github.thecodingdad.moonlightlauncher.ui.PlatformUi
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkPlatform
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound
import io.github.thecodingdad.moonlightlauncher.ui.input.InputDispatcher
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File

fun main(args: Array<String>) {
    // The streaming host starts synced games through the launcher: no window then.
    if (args.firstOrNull() == Runner.ARG) kotlin.system.exitProcess(Runner.run(args.getOrNull(1)))
    // Started again while running (e.g. from the tray): the running one shows its window.
    val windowVisible = kotlinx.coroutines.flow.MutableStateFlow(true)
    var bringToFront: () -> Unit = {}
    if (!SingleInstance.claim { windowVisible.value = true; bringToFront() }) return
    val dataDir = dataDirectory()
    Log.init(File(dataDir, "logs/launcher.log"))
    Log.i("Moonlight Launcher ${DesktopPlatformInfo(dataDir).versionName} starting, data in $dataDir")

    // Games installed here are PC games (IGDB platform choice, suggestions).
    io.github.thecodingdad.moonlightlauncher.data.library.PlatformResolver.localPlatform = io.github.thecodingdad.moonlightlauncher.data.library.PlatformResolver.PC
    val container = DesktopContainer(dataDir)
    val startup = Startup(dataDir).also { it.start() }
    // Started with Windows and the tray on: no window until the user opens it.
    if (Startup.AUTOSTART_ARG in args && startup.settings.value.closeToTray) windowVisible.value = false
    ArtworkFiles.init(dataDir)
    ArtworkPlatform.current = DesktopArtwork
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .crossfade(true)
            .build()
    }

    val dispatcher = InputDispatcher()
    val gamepads = Gamepads(dispatcher)
    var mainWindow: java.awt.Frame? = null
    val windowControls = WindowControls()
    val hostServices by lazy { HostServices(container.hostRole, container.hostSync, container.secrets) }
    val platformUi = DesktopPlatformUi(gamepads, { mainWindow }, { container.pcLibrary }, { hostServices }, startup, container.profiles, windowControls)
    bringToFront = { platformUi.bringToFront() }
    PlatformUi.current = platformUi
    val feedback = UiFeedback(File(dataDir, "cache"), DesktopFeedbackOutput { gamepads }).also { it.load() }

    container.onGameEnded = { platformUi.bringToFront() }
    container.library.start()
    // Streaming hosts are polled while the window is shown.
    container.scope.launch { windowVisible.collect { if (it) container.hosts.startPolling() else container.hosts.stopPolling() } }
    container.scope.launch { container.filters.ensureDefaults() }
    container.servers.start()
    container.metadata.start()
    container.artwork.start()
    // Servers for other launchers (only what the user switched on); the key is created on first use.
    container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        container.hostRole.hostGames = container.hostGames
        container.hostRole.start()
    }
    // The streaming host follows the library (scans, renames, hidden games) and the sync settings.
    container.scope.launch {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        kotlinx.coroutines.flow.combine(
            container.pcLibrary.counts, container.database.libraryDao().observeAll(), container.hostRole.settings,
        ) { _, _, settings -> settings }
            .debounce(20_000)
            .collect { if (it.syncEnabled) container.hostSync.run() }
    }
    container.scope.launch {
        container.settings.settings.collect {
            gamepads.nintendoLayout = it.buttonLayout == ButtonLayout.NINTENDO
            if (it.disabledGameSources != container.disabledSources) {
                container.disabledSources = it.disabledGameSources
                container.pcLibrary.rescan()
            }
            DesktopPlatformUi.applyLocale(it.language)
        }
    }

    application {
        val vm = remember { LauncherViewModel(container) }
        gamepads.onBack = { feedback.play(UiSound.BACK) }
        // Like Android: touch or a mouse click hides the focus ring, the next key or controller
        // input shows it again (and continues from the touched element).
        var inputModeManager: androidx.compose.ui.input.InputModeManager? = null
        fun controllerInput() {
            val m = inputModeManager ?: return
            if (m.inputMode != androidx.compose.ui.input.InputMode.Keyboard) m.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        }
        gamepads.onInput = { vm.touch(); java.awt.EventQueue.invokeLater { controllerInput() } }
        val state = rememberWindowState(
            placement = if (startup.settings.value.startFullscreen && !isWindows) WindowPlacement.Fullscreen else WindowPlacement.Maximized,
            width = 1600.dp, height = 900.dp,
        )
        LaunchedEffect(state) {
            windowControls.toggleFullscreen = {
                val frame = mainWindow
                if (isWindows && frame != null) {
                    // Windows: borderless over the whole monitor (Compose keeps the title bar).
                    if (BorderlessFullscreen.active) BorderlessFullscreen.exit(frame) else BorderlessFullscreen.enter(frame)
                    windowControls.fullscreen.value = BorderlessFullscreen.active
                } else {
                    state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized else WindowPlacement.Fullscreen
                }
            }
            windowControls.exit = ::exitApplication
            if (!isWindows) {
                androidx.compose.runtime.snapshotFlow { state.placement }.collect { windowControls.fullscreen.value = it == WindowPlacement.Fullscreen }
            }
        }
        val startupSettings by startup.settings.collectAsState()
        val visible by windowVisible.collectAsState()
        val hostStatus by container.hostRole.status.collectAsState()
        val icon = remember { BitmapPainter(DesktopContainer::class.java.getResourceAsStream("/icon.png")!!.use { loadImageBitmap(it) }) }
        // Tray icon while the launcher may run without its window.
        if (startupSettings.closeToTray) {
            Tray(
                icon = icon,
                tooltip = "Moonlight Launcher",
                onAction = { windowVisible.value = true; platformUi.bringToFront() },
                menu = {
                    Item(stringResource(Res.string.tray_open), onClick = { windowVisible.value = true; platformUi.bringToFront() })
                    if (hostStatus.webRunning) {
                        Item(stringResource(Res.string.tray_web), onClick = { container.hostRole.localLoginUrl()?.let(BrowserOpener::open) })
                    }
                    Item(stringResource(Res.string.tray_quit), onClick = ::exitApplication)
                },
            )
        }
        Window(
            onCloseRequest = { if (startup.settings.value.closeToTray) windowVisible.value = false else exitApplication() },
            visible = visible,
            icon = icon,
            title = "Moonlight Launcher",
            state = state,
            onPreviewKeyEvent = { e ->
                vm.touch()
                if (e.type == KeyEventType.KeyDown) controllerInput()
                when {
                    // F11: full screen like a console.
                    e.key == Key.F11 && e.type == KeyEventType.KeyDown -> {
                        windowControls.toggleFullscreen()
                        true
                    }
                    e.key == Key.Escape && e.type == KeyEventType.KeyDown -> {
                        feedback.play(UiSound.BACK)
                        false
                    }
                    else -> keyboardButton(e.key)?.let { button ->
                        val phase = if (e.type == KeyEventType.KeyDown) InputDispatcher.KeyPhase.DOWN else InputDispatcher.KeyPhase.UP
                        dispatcher.dispatchButton(button, phase, 0, System.currentTimeMillis())
                    } ?: false
                }
            },
        ) {
            LaunchedEffect(window) {
                mainWindow = window
                gamepads.start(window)
                if (isWindows && startup.settings.value.startFullscreen) {
                    BorderlessFullscreen.enter(window)
                    windowControls.fullscreen.value = true
                }
            }
            // A language change rebuilds the content (the texts are read once); the navigation
            // lives outside, so the settings page stays open.
            val nav = androidx.navigation.compose.rememberNavController()
            val revision by platformUi.languageRevision
            val modeManager = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.SideEffect { inputModeManager = modeManager }
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize()
                    // Arrow keys (and the pad's D-pad / left stick, sent as arrows) move the focus like
                    // the D-pad on Android; Compose on the desktop only moves it with Tab.
                    .onKeyEvent { e -> arrowFocus(e, focusManager) }
                    .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
                                modeManager.inputMode != androidx.compose.ui.input.InputMode.Touch
                            ) modeManager.requestInputMode(androidx.compose.ui.input.InputMode.Touch)
                        }
                    }
                },
            ) {
                key(revision) {
                    CompositionLocalProvider(LocalContainer provides container) {
                        val settings by vm.settings.collectAsState()
                        ApplyStoredLanguage(settings.language, platformUi)
                        LauncherApp(vm, dispatcher, feedback, nav)
                    }
                }
            }
        }
    }
}

/** Unhandled arrow keys move the focus in their direction. */
internal fun arrowFocus(e: androidx.compose.ui.input.key.KeyEvent, focusManager: androidx.compose.ui.focus.FocusManager): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val direction = when (e.key) {
        Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
        Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
        Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
        Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
        else -> return false
    }
    return focusManager.moveFocus(direction)
}

/** Keyboard equivalents of the pad buttons, as on Android (handy without a controller). */
private fun keyboardButton(key: Key): PadButton? = when (key) {
    Key.PageUp -> PadButton.L1
    Key.PageDown -> PadButton.R1
    Key.F1 -> PadButton.Y
    Key.F2 -> PadButton.X
    Key.F3 -> PadButton.START
    else -> null
}
