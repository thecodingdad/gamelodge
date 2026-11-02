package io.github.thecodingdad.gamelodge.desktop

import androidx.compose.ui.input.pointer.pointerHoverIcon
import io.github.thecodingdad.gamelodge.data.settings.combo
import io.github.thecodingdad.gamelodge.data.settings.hasControllerGlobals
import kotlinx.coroutines.flow.first
import io.github.thecodingdad.gamelodge.data.settings.withControllerGlobalsMoved
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    if (args.firstOrNull() == Runner.ARG) {
        TaskbarIdentity.applyToProcess(TaskbarIdentity.RUNNER_ID)
        kotlin.system.exitProcess(Runner.run(args.getOrNull(1)))
    }
    // Diagnostics: the running launcher logs its threads (e.g. while its window hangs).
    if (args.firstOrNull() == "--threads") kotlin.system.exitProcess(if (SingleInstance.requestThreadDump()) 0 else 1)
    // Started again while running (e.g. from the tray): the running one shows its window.
    val windowVisible = kotlinx.coroutines.flow.MutableStateFlow(true)
    var bringToFront: () -> Unit = {}
    if (!SingleInstance.claim { windowVisible.value = true; bringToFront() }) return
    TaskbarIdentity.applyToProcess()
    val dataDir = dataDirectory()
    DataReset.runIfRequested(dataDir)
    Log.init(File(dataDir, "logs/launcher.log"))
    Diagnostics.routeSkikoLog()
    Diagnostics.logUncaught()
    // Class cache of a test version (no longer used).
    File(dataDir, "cache/cds").takeIf { it.isDirectory }?.deleteRecursively()
    // Converted animations of a test version (now kept next to the artwork).
    File(dataDir, "cache/animated").takeIf { it.isDirectory }?.deleteRecursively()
    // Converted animations in an older format (converted again).
    File(dataDir, "artwork-derived").listFiles { f -> f.name.endsWith(AnimationCache.OLD_SUFFIX) }?.forEach { it.delete() }
    Log.i("GameLodge ${DesktopPlatformInfo(dataDir).versionName} starting, data in $dataDir")
    // Guide is the launcher's button while it runs: not the Game Bar's (restored on exit).
    GameBarButton(File(dataDir, "gamebar-button.restore")).takeOver()

    // Games installed here are PC games (IGDB platform choice, suggestions).
    io.github.thecodingdad.gamelodge.data.library.PlatformResolver.localPlatform = io.github.thecodingdad.gamelodge.data.library.PlatformResolver.PC
    val container = DesktopContainer(dataDir)
    // Games another device starts through this PC as streaming host: their actions, when switched on.
    val hostGameActions = io.github.thecodingdad.gamelodge.ha.GameActionRunner(
        { container.ha.runAction(it) }, { io.github.thecodingdad.gamelodge.ha.GameActionSettings.decode(container.settings.current().gameActions) }, container.scope,
    )
    // The game the streaming host runs through a runner right now (its window comes to the front
    // when a paired launcher resumes the stream).
    var hostGame: String? = null
    io.github.thecodingdad.gamelodge.desktop.host.HostStreams.onResume = {
        val runners = Runner.runners()
        val entry = hostGame?.let { container.pcLibrary.entry(it) }
        val matcher = entry?.let { io.github.thecodingdad.gamelodge.desktop.games.GameMatcher(it.processDir ?: it.installDir, it.processNames) }
        val pids = runners.flatMap { r -> r.descendants().use { d -> d.map { it.pid() }.toList() } } +
            (matcher?.takeIf { it.hasCriteria }?.let { m -> io.github.thecodingdad.gamelodge.desktop.games.SystemProcesses.snapshot().filter(m::matches).map { it.id } } ?: emptyList())
        if (!io.github.thecodingdad.gamelodge.desktop.games.GameWindow.focus(pids.toSet())) Log.i("Resume: no game window to bring to the front")
    }
    // Streams of clients without GameLodge (Moonlight on a TV, a console …): counted here, as no
    // launcher reports them. A GameLodge stream tells the host before its game starts.
    val foreignStarts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    SingleInstance.onHostGame = { entryId, started ->
        hostGame = if (started) entryId else hostGame.takeIf { it != entryId }
        if (started) {
            if (!io.github.thecodingdad.gamelodge.desktop.host.HostStreams.active) foreignStarts[entryId] = System.currentTimeMillis() else foreignStarts.remove(entryId)
        } else {
            val start = foreignStarts.remove(entryId)
            val seconds = start?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
            if (start != null && seconds >= io.github.thecodingdad.gamelodge.data.sync.PlayDataSync.MIN_SESSION_SECONDS && container.hostRole.settings.value.serverEnabled) {
                val session = io.github.thecodingdad.gamelodge.data.companion.PlaySessionData(
                    java.util.UUID.randomUUID().toString(), entryId, start,
                    seconds.coerceAtMost(io.github.thecodingdad.gamelodge.data.sync.PlayDataHub.MAX_SESSION_SECONDS),
                )
                container.playDataHub.apply(io.github.thecodingdad.gamelodge.data.sync.PlayDataSync.OTHER_CLIENTS, "", io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges(sessions = listOf(session)))
                container.library.syncPlayData()
            }
        }
        if (io.github.thecodingdad.gamelodge.ha.GameActionSettings.decode(container.settings.current().gameActions).hostStarts) {
            val itemId = container.database.libraryDao().getTarget(io.github.thecodingdad.gamelodge.data.library.SourceEntry.localId(entryId))?.itemId
            if (started) hostGameActions.beforeStart(itemId) else hostGameActions.ended()
        }
    }
    // The runner's splash on the streaming host: the game's artwork files, if stored here.
    // The runner of a host stream: the start action the streaming launcher picked, if any.
    SingleInstance.onLaunch = { entryId ->
        io.github.thecodingdad.gamelodge.desktop.host.HostStreams.takeAction(io.github.thecodingdad.gamelodge.desktop.host.sync.AppBuilder.uuid(entryId))
            ?.let { action -> container.pcLibrary.entryFor(entryId, action) }
            ?.let { io.github.thecodingdad.gamelodge.desktop.host.sync.LaunchSpec.of(it).encode() }
    }
    fun splashArt(item: io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity): SplashArt {
        fun file(url: String?) = (ArtworkFiles.model(url) as? File)?.takeIf { it.isFile }?.path
        return SplashArt(item.title, hero = file(item.heroUrl), logo = file(item.logoUrl), cover = file(item.coverUrl))
    }
    container.hostRole.keys = object : io.github.thecodingdad.gamelodge.host.KeySource {
        override fun keys() = container.keySync.local()
        override fun merge(keys: io.github.thecodingdad.gamelodge.data.companion.SharedKeys, deviceName: String) {
            if (container.keySync.merge(keys)) Log.i("Metadata keys changed by $deviceName")
        }
    }
    container.hostRole.playData = object : io.github.thecodingdad.gamelodge.host.PlayDataSource {
        override fun changes(after: Long) = kotlinx.coroutines.runBlocking { container.playDataHub.changesAfter(after) }
        override fun apply(deviceId: String, deviceName: String, changes: io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges) =
            kotlinx.coroutines.runBlocking { container.playDataHub.apply(deviceId, deviceName, changes) }.also { ok ->
                // What a launcher sent shows here too.
                if (ok) container.scope.launch { container.library.syncPlayData() }
            }
    }
    SingleInstance.onArtwork = { entryId ->
        val target = container.database.libraryDao().getTarget(io.github.thecodingdad.gamelodge.data.library.SourceEntry.localId(entryId))
        target?.let { container.database.libraryDao().getItem(it.itemId) }?.let { item ->
            kotlinx.serialization.json.Json.encodeToString(SplashArt.serializer(), splashArt(item))
        }
    }
    // The same for runners that find no launcher running: kept as a file whenever the library changes.
    container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        container.database.libraryDao().observeAll().debounce(5_000).collect { entries ->
            ArtworkIndex.write(
                entries.flatMap { e ->
                    e.targets.filter { it.type == io.github.thecodingdad.gamelodge.data.db.TargetType.LOCAL && it.packageName != null }
                        .map { it.packageName!! to splashArt(e.item) }
                }.toMap(),
            )
        }
    }
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
    // The controller that starts the stream is player 1.
    io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.firstPad = { gamepads.starter() }
    var mainWindow: java.awt.Frame? = null
    val windowControls = WindowControls()
    val hostServices by lazy { HostServices(container.hostRole, container.hostSync, container.secrets) }
    val platformUi = DesktopPlatformUi(gamepads, { mainWindow }, { container.pcLibrary }, { hostServices }, startup, container.profiles, windowControls)
    platformUi.onShow = { windowVisible.value = true }
    bringToFront = { platformUi.bringToFront() }
    PlatformUi.current = platformUi
    val feedback = UiFeedback(File(dataDir, "cache"), DesktopFeedbackOutput { gamepads }).also { it.load() }

    container.onGameEnded = { platformUi.bringToFront() }
    // Started beside the window's first composition (their first use loads classes and opens the database).
    container.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        // One after the other (StartupSequence), artwork first: missing covers are what one notices.
        container.artwork.start()
        container.library.start()
        container.servers.start()
        container.metadata.start()
        container.mqtt.start()
        io.github.thecodingdad.gamelodge.stream.StreamQuality.follow(container.settings, container.scope)
    }
    // Streaming hosts are polled while the window is shown (not while the screensaver shows).
    container.scope.launch {
        kotlinx.coroutines.flow.combine(windowVisible, io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.active) { visible, idle -> visible && !idle }
            .collect { if (it) container.hosts.startPolling() else container.hosts.stopPolling() }
    }
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
    // The former global layout / combinations become the pads' own (the configured ones and those
    // connected within the first minute), once.
    container.scope.launch {
        if (!container.settings.settings.first().hasControllerGlobals) return@launch
        val pads = kotlinx.coroutines.withTimeoutOrNull(60_000) { gamepads.controllers.first { it.isNotEmpty() } }.orEmpty()
        container.settings.update { it.withControllerGlobalsMoved(it.controllerCombos.keys + it.controllerLayouts.keys + pads.map { p -> p.key }) }
    }
    // Pads without their own combinations use the built-in ones.
    io.github.thecodingdad.gamelodge.input.ButtonCombos.forward = io.github.thecodingdad.gamelodge.ui.screens.settings.DefaultCombos.comboForward
    io.github.thecodingdad.gamelodge.input.ButtonCombos.combos = io.github.thecodingdad.gamelodge.input.ComboAction.entries
        .associateWith { a -> io.github.thecodingdad.gamelodge.ui.screens.settings.DefaultCombos.combo(a) }
    container.scope.launch {
        container.settings.settings.collect {
            gamepads.layoutOverrides = it.controllerLayouts.mapValues { (_, l) -> l == ButtonLayout.NINTENDO }
            WindowsPointer.speed = it.mouseSpeed / 100f
            WindowsPointer.exponent = it.mouseAcceleration.exponent
            WindowsPointer.streamSpeed = STREAM_MOUSE_SPEED * it.mouseSpeed / 100f
            (container.streamControls as? io.github.thecodingdad.gamelodge.desktop.stream.DesktopStreamControls)
                ?.setMouseSpeed(WindowsPointer.streamSpeed, WindowsPointer.exponent)
            io.github.thecodingdad.gamelodge.input.ButtonCombos.overrides = it.controllerCombos.mapValues { (_, pad) ->
                pad.combos.mapNotNull { (name, keys) ->
                    io.github.thecodingdad.gamelodge.input.ComboAction.entries.firstOrNull { a -> a.name == name }?.let { a -> a to keys.toSet() }
                }.toMap()
            }.filterValues { m -> m.isNotEmpty() }
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
        // Button combinations, also from a game or stream in front (which gets the buttons as
        // well: Windows doesn't let them be intercepted).
        WindowsPointer.onChange = { PointerNotice.show(it) }
        gamepads.streamInFront = { container.streamControls.inFront() }
        gamepads.onCombo = combo@{ action, _ ->
            // Streamed to: the buttons came from the client, whose own launcher handles them. A paired
            // launcher says so (any app); for others the host's games run through a runner.
            if (io.github.thecodingdad.gamelodge.desktop.host.HostStreams.active || Runner.hostGameRunning()) return@combo
            val streams = container.streamControls
            when (action) {
                io.github.thecodingdad.gamelodge.input.ComboAction.HOME,
                io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU -> java.awt.EventQueue.invokeLater {
                    val inFront = windowVisible.value && mainWindow?.isActive == true
                    if (action == io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU) {
                        // A game or stream in front: the menu shows over it (it keeps running in full screen).
                        val playing = io.github.thecodingdad.gamelodge.desktop.stream.MoonlightQt.locate().running?.process?.isAlive == true ||
                            io.github.thecodingdad.gamelodge.stream.LocalSessions.running.value != null
                        if (QuickMenuOverlay.shown.value || (playing && !inFront)) {
                            QuickMenuOverlay.toggle(vm.settings.value.uiScale / 100f)
                            if (QuickMenuOverlay.shown.value) return@invokeLater
                        }
                    }
                    QuickMenuOverlay.close()
                    // Home leaves a running stream like on Android (the game keeps running on the
                    // host; resuming reconnects quickly) instead of leaving it behind with its sound.
                    if (action == io.github.thecodingdad.gamelodge.input.ComboAction.HOME) streams.disconnect()
                    windowVisible.value = true
                    platformUi.bringToFront()
                    if (action == io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU) vm.quickMenuRequests.tryEmit(true)
                    else if (inFront) vm.homePressed.tryEmit(Unit)
                }
                io.github.thecodingdad.gamelodge.input.ComboAction.STATS -> streams.toggleOverlay()
                // One state for both: GameLodge's pointer and the stream's mouse follow it.
                io.github.thecodingdad.gamelodge.input.ComboAction.MOUSE -> {
                    WindowsPointer.toggle()
                    streams.setMouse(WindowsPointer.enabled)
                }
            }
        }
        // Like Android: touch or a mouse click hides the focus ring, the next key or controller
        // input shows it again (and continues from the touched element).
        var inputModeManager: androidx.compose.ui.input.InputModeManager? = null
        // Nothing focused (e.g. after touch on an empty spot or a closed popup): the next direction
        // key focuses the first element, so the controller always finds something to select.
        var windowFocusManager: androidx.compose.ui.focus.FocusManager? = null
        var contentHasFocus = false
        fun controllerInput() {
            PadCursor.controllerUsed()
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
            windowControls.close = { if (startup.settings.value.closeToTray) windowVisible.value = false else exitApplication() }
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
            val trayImage = remember { javax.imageio.ImageIO.read(DesktopContainer::class.java.getResource("/icon.png")) }
            val traySettings by vm.settings.collectAsState()
            val openWindow = { windowVisible.value = true; platformUi.bringToFront() }
            LauncherTray(
                trayImage, "GameLodge", traySettings,
                items = listOfNotNull(
                    TrayItem(stringResource(Res.string.tray_open), openWindow),
                    if (hostStatus.webRunning) TrayItem(stringResource(Res.string.tray_web)) { container.hostRole.localLoginUrl()?.let(BrowserOpener::open) } else null,
                    TrayItem(stringResource(Res.string.tray_quit), ::exitApplication),
                ),
                onOpen = openWindow,
            )
        }
        // A crash in the UI: into the log with its stack trace (Compose's own handler only shows a
        // dialog with the message), then that dialog as before.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides androidx.compose.ui.window.WindowExceptionHandlerFactory { window ->
                val fallback = androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory.exceptionHandler(window)
                androidx.compose.ui.window.WindowExceptionHandler { t ->
                    Log.e("UI crashed", t)
                    fallback.onException(t)
                }
            },
        ) {
        // Connection warnings over a stream.
        ConnectionWarningWindow(vm.settings.collectAsState().value)
        // The quick menu over a game or stream.
        QuickMenuOverlayWindow(
            container, vm, feedback, gamepads,
            openSettings = { target ->
                QuickMenuOverlay.close()
                windowVisible.value = true
                platformUi.bringToFront()
                vm.settingsRequests.tryEmit(target)
            },
            onMenu = { open -> (container.streamControls as? io.github.thecodingdad.gamelodge.desktop.stream.DesktopStreamControls)?.setMenu(open) },
        )
        Window(
            onCloseRequest = { windowControls.close() },
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
                    // The pressed direction first; only when it finds nothing, the first element.
                    val manager = windowFocusManager
                    if (manager != null && !arrowFocus(e, manager)) manager.moveFocus(androidx.compose.ui.focus.FocusDirection.Next)
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
            // Renderer and graphics adapter of every window (also the ones built after sleep), once it drew.
            LaunchedEffect(window) {
                androidx.compose.runtime.withFrameNanos { }
                val renderInfo = Diagnostics.renderInfo(window)
                Log.i("Window renderer: ${window.renderApi} – $renderInfo")
                // Only Intel's graphics need the black lift of animation frames.
                AnimatedImageDecoder.liftBlacks = "intel" in renderInfo.lowercase() || renderInfo == "?"
            }
            // Animated artwork pauses after a while without input (any input, hovering included, resumes
            // it), and right away while the window is minimized or hidden in the tray.
            val minimized = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.DisposableEffect(window) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowIconified(e: java.awt.event.WindowEvent) { minimized.value = true }
                    override fun windowDeiconified(e: java.awt.event.WindowEvent) { minimized.value = false }
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener) }
            }
            val lastInput by vm.lastInteraction.collectAsState()
            LaunchedEffect(lastInput, minimized.value, visible) {
                AnimationPlayback.paused.value = minimized.value || !visible
                if (AnimationPlayback.paused.value) return@LaunchedEffect
                kotlinx.coroutines.delay(ANIMATION_IDLE_MS)
                AnimationPlayback.paused.value = true
            }
            LaunchedEffect(window) {
                mainWindow = window
                gamepads.start(window)
                // Activated (Home from a stream, the tray): the keyboard focus belongs to the content.
                // Left on the frame, Compose's own focus can't show and the pad's arrows go nowhere.
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent) {
                        val owner = window.focusOwner
                        if (owner == null || owner is java.awt.Window) Gamepads.keyReceiver(window)?.requestFocusInWindow()
                    }
                })
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
                    // No mouse pointer in the way while a controller is used (also over hand cursors).
                    .pointerHoverIcon(
                        if (PadCursor.hidden) PadCursor.blank else androidx.compose.ui.input.pointer.PointerIcon.Default,
                        overrideDescendants = PadCursor.hidden,
                    )
                    .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            // Moving, clicking and scrolling with the mouse is using the launcher too (screensaver).
                            vm.touchThrottled()
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
}

/** Animations pause after this long without input. */
private const val ANIMATION_IDLE_MS = 60_000L

/** Host pixels per second of the stream's mouse at full stick deflection and 100 % speed. */
private const val STREAM_MOUSE_SPEED = 1200f

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
