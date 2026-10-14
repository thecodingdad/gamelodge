package io.github.thecodingdad.moonlightlauncher

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.limelight.ArtemisApplication
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Extends Artemis' application so its global state (stream profiles) is initialised
 * exactly as in a standalone Artemis install.
 */
class LauncherApplication : ArtemisApplication(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.hosts.bind()
        container.library.start()
        container.emulation.start()
        container.controllerSettings.install()
        io.github.thecodingdad.moonlightlauncher.ui.shell.StreamConnectingOverlay.install(container)
        container.scope.launch { container.filters.ensureDefaults() }
        // Button combinations: needed by the activity and by the accessibility service, which
        // can run without any activity (e.g. after an update).
        container.scope.launch {
            container.controllerSettings.all.collect { all ->
                io.github.thecodingdad.moonlightlauncher.input.ButtonCombos.overrides = all.mapValues { (_, s) ->
                    buildMap {
                        s.comboHome?.let { put(io.github.thecodingdad.moonlightlauncher.input.ComboAction.HOME, it.toSet()) }
                        s.comboQuickMenu?.let { put(io.github.thecodingdad.moonlightlauncher.input.ComboAction.QUICK_MENU, it.toSet()) }
                    }
                }.filterValues { it.isNotEmpty() }
            }
        }
        container.scope.launch {
            container.settings.settings.collect {
                io.github.thecodingdad.moonlightlauncher.input.ButtonCombos.combos = mapOf(
                    io.github.thecodingdad.moonlightlauncher.input.ComboAction.HOME to it.comboHome,
                    io.github.thecodingdad.moonlightlauncher.input.ComboAction.QUICK_MENU to it.comboQuickMenu,
                )
            }
        }
        container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // Never with a TV attached (mirrored it would show there, and nobody touches a TV).
            kotlinx.coroutines.flow.combine(
                container.settings.settings.map { it.quickMenuEdgeHandle },
                io.github.thecodingdad.moonlightlauncher.system.ExternalDisplay.observe(this@LauncherApplication),
            ) { on, tv -> on && tv == null }.distinctUntilChanged().collect {
                io.github.thecodingdad.moonlightlauncher.system.EdgeHandle.update(this@LauncherApplication, it)
            }
        }
        container.servers.start()
        container.metadata.start()
        io.github.thecodingdad.moonlightlauncher.system.OdinMapping(this)
            .watch(container.scope, container.settings.settings.map { it.odinKeepControllers })
        container.vpn.startNetworkWatch()
        // Host polling is driven by MainActivity's lifecycle: Artemis' stream activity runs in
        // this process too, and polling must not compete with an active stream.
    }

    /** Coil loader with animated WebP/GIF support, sharing the app's HTTP client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

val Context.container: AppContainer
    get() = (applicationContext as LauncherApplication).container
