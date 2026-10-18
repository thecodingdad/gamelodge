package io.github.thecodingdad.gamelodge.desktop

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import io.github.thecodingdad.gamelodge.resources.Res
import io.github.thecodingdad.gamelodge.resources.tray_open
import io.github.thecodingdad.gamelodge.resources.tray_quit
import io.github.thecodingdad.gamelodge.resources.tray_web
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.ui.LauncherApp
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.ArtworkPlatform
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiSound
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.util.Log
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
    TaskbarIdentity.applyToProcess()
    val dataDir = dataDirectory()
    DataReset.runIfRequested(dataDir)
    Log.init(File(dataDir, "logs/launcher.log"))
    // Class cache of a test version (no longer used).
    File(dataDir, "cache/cds").takeIf { it.isDirectory }?.deleteRecursively()
    Log.i("GameLodge ${DesktopPlatformInfo(dataDir).versionName} starting, data in $dataDir")

    // Games installed here are PC games (IGDB platform choice, suggestions).
    io.github.thecodingdad.gamelodge.data.library.PlatformResolver.localPlatform = io.github.thecodingdad.gamelodge.data.library.PlatformResolver.PC
    val container = DesktopContainer(dataDir)
    val startup = Startup(dataDir).also { it.start() }
    // Started with Windows and the tray on: no window until the user opens it.
    if (Startup.AUTOSTART_ARG in args && startup.settings.value.closeToTray) windowVisible.value = false
    ArtworkFiles.init(dataDir)
    ArtworkPlatform.current = DesktopArtwork
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
                // Animated GIF/WebP (Coil plays only the first frame on the desktop).
                add(AnimatedImageDecoder.Factory())
            }
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
    // Started beside the window's first composition (their first use loads classes and opens the database).
    container.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        container.library.start()
        container.servers.start()
        container.metadata.start()
        container.artwork.start()
    }
    // Streaming hosts are polled while the window is shown.
    container.scope.launch { windowVisible.collect { if (it) container.hosts.startPolling() else container.hosts.stopPolling() } }
    container.scope.launch { container.filters.ensureDefaults() }
    FilePickers.window = { mainWindow }
    container.scope.launch { container.backupStorage.scheduleAuto(container.settings.current().autoBackup) }
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
            gamepads.layoutOverrides = it.controllerLayouts.mapValues { (_, l) -> l == ButtonLayout.NINTENDO }
            val emulation = io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulation.decode(it.pcEmulation)
            val custom = io.github.thecodingdad.gamelodge.desktop.games.CustomGame.decode(it.pcCustomGames)
            val overrides = io.github.thecodingdad.gamelodge.desktop.games.LaunchOverride.decode(it.pcLaunchOverrides)
            if (it.disabledGameSources != container.disabledSources || emulation != container.pcEmulation || custom != container.customGames ||
                overrides != container.launchOverrides
            ) {
                container.disabledSources = it.disabledGameSources
                container.pcEmulation = emulation
                container.customGames = custom
                container.launchOverrides = overrides
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
        // Nothing focused (e.g. after touch on an empty spot or a closed popup): the next direction
        // key focuses the first element, so the controller always finds something to select.
        var windowFocusManager: androidx.compose.ui.focus.FocusManager? = null
        var contentHasFocus = false
        fun controllerInput() {
            val m = inputModeManager ?: return
            if (m.inputMode != androidx.compose.ui.input.InputMode.Keyboard) m.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        }
        gamepads.onInput = { vm.touch(); java.awt.EventQueue.invokeLater { controllerInput() } }
        // Starts maximized; sized like the work area already, so the first frames are not laid out
        // for a smaller window and stretched once Windows has maximized it.
        val workArea = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
        val state = rememberWindowState(
            placement = if (startup.settings.value.startFullscreen && !isWindows) WindowPlacement.Fullscreen else WindowPlacement.Maximized,
            width = workArea.width.dp, height = workArea.height.dp,
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
                tooltip = "GameLodge",
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
            title = "GameLodge",
            state = state,
            onPreviewKeyEvent = { e ->
                vm.touch()
                val isDirection = e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                if (e.type == KeyEventType.KeyDown) {
                    // Controller buttons and navigation keys show the focus; typing (letters, Shift …) doesn't.
                    if (gamepads.dispatching || isDirection || e.key == Key.Tab) controllerInput()
                    if (!gamepads.dispatching) gamepads.otherInput()
                }
                if (isDirection && e.type == KeyEventType.KeyDown && !contentHasFocus) {
                    windowFocusManager?.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
                    return@Window true
                }
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
                TaskbarIdentity.applyToWindow(window)
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
            androidx.compose.runtime.SideEffect { windowFocusManager = focusManager }
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize()
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    // Arrow keys (and the pad's D-pad / left stick, sent as arrows) move the focus like
                    // the D-pad on Android; Compose on the desktop only moves it with Tab.
                    .onKeyEvent { e -> arrowFocus(e, focusManager) }
                    .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) gamepads.otherInput()
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
