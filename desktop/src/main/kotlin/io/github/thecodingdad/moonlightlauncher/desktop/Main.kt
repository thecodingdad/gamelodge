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
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import io.github.thecodingdad.moonlightlauncher.resources.Res
import io.github.thecodingdad.moonlightlauncher.resources.tray_open
import io.github.thecodingdad.moonlightlauncher.resources.tray_quit
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
    val hostServices by lazy { HostServices(container.hostRole, container.hostSync, container.secrets) }
    val platformUi = DesktopPlatformUi(gamepads, { mainWindow }, { container.pcLibrary }, { hostServices }, startup)
    bringToFront = { platformUi.bringToFront() }
    PlatformUi.current = platformUi
    val feedback = UiFeedback(File(dataDir, "cache"), DesktopFeedbackOutput { gamepads }).also { it.load() }

    container.onGameEnded = { platformUi.bringToFront() }
    container.library.start()
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
        gamepads.onInput = { vm.touch() }
        gamepads.onBack = { feedback.play(UiSound.BACK) }
        val state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1600.dp, height = 900.dp)
        val startupSettings by startup.settings.collectAsState()
        val visible by windowVisible.collectAsState()
        val icon = remember { BitmapPainter(DesktopContainer::class.java.getResourceAsStream("/icon.png")!!.use { loadImageBitmap(it) }) }
        // Tray icon while the launcher may run without its window.
        if (startupSettings.closeToTray) {
            Tray(
                icon = icon,
                tooltip = "Moonlight Launcher",
                onAction = { windowVisible.value = true; platformUi.bringToFront() },
                menu = {
                    Item(stringResource(Res.string.tray_open), onClick = { windowVisible.value = true; platformUi.bringToFront() })
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
                when {
                    // F11: full screen like a console.
                    e.key == Key.F11 && e.type == KeyEventType.KeyDown -> {
                        state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized else WindowPlacement.Fullscreen
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
            }
            // A language change rebuilds the content (the texts are read once); the navigation
            // lives outside, so the settings page stays open.
            val nav = androidx.navigation.compose.rememberNavController()
            val revision by platformUi.languageRevision
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

/** Keyboard equivalents of the pad buttons, as on Android (handy without a controller). */
private fun keyboardButton(key: Key): PadButton? = when (key) {
    Key.PageUp -> PadButton.L1
    Key.PageDown -> PadButton.R1
    Key.F1 -> PadButton.Y
    Key.F2 -> PadButton.X
    Key.F3 -> PadButton.START
    else -> null
}
