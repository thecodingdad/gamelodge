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
import androidx.compose.ui.window.Window
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
import kotlinx.coroutines.launch
import java.io.File

fun main() {
    val dataDir = dataDirectory()
    Log.init(File(dataDir, "logs/launcher.log"))
    Log.i("Moonlight Launcher ${DesktopPlatformInfo(dataDir).versionName} starting, data in $dataDir")

    val container = DesktopContainer(dataDir)
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
    val platformUi = DesktopPlatformUi(gamepads) { mainWindow }
    PlatformUi.current = platformUi
    val feedback = UiFeedback(File(dataDir, "cache"), DesktopFeedbackOutput { gamepads }).also { it.load() }

    container.library.start()
    container.scope.launch { container.filters.ensureDefaults() }
    container.servers.start()
    container.metadata.start()
    container.artwork.start()
    container.scope.launch {
        container.settings.settings.collect {
            gamepads.nintendoLayout = it.buttonLayout == ButtonLayout.NINTENDO
            DesktopPlatformUi.applyLocale(it.language)
        }
    }

    application {
        val vm = remember { LauncherViewModel(container) }
        gamepads.onInput = { vm.touch() }
        gamepads.onBack = { feedback.play(UiSound.BACK) }
        val state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1600.dp, height = 900.dp)
        Window(
            onCloseRequest = ::exitApplication,
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
