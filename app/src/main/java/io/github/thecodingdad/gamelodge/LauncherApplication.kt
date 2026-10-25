package io.github.thecodingdad.gamelodge

import io.github.thecodingdad.gamelodge.data.settings.combo
import io.github.thecodingdad.gamelodge.input.handle
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
        registerActivityLifecycleCallbacks(CurrentActivity)
        container = AppContainer(this)
        io.github.thecodingdad.gamelodge.stream.ProfileStore.resetRenderMode(this)
        // Right after boot the system is busy (on the Odin the low-memory killer then stops the
        // heaviest foreground app): the start tasks wait a moment, and run one after the other.
        if (android.os.SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            io.github.thecodingdad.gamelodge.util.StartupSequence.holdFor(BOOT_PAUSE_MS)
        }
        io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles.init(filesDir)
        io.github.thecodingdad.gamelodge.ui.components.ArtworkPlatform.current = io.github.thecodingdad.gamelodge.ui.components.AndroidArtwork(this)
        io.github.thecodingdad.gamelodge.ui.PlatformUi.current = io.github.thecodingdad.gamelodge.ui.AndroidPlatformUi(this)
        // Artwork first in line: missing covers are what one notices.
        container.artwork.start()
        container.hosts.bind()
        container.library.start()
        container.emulation.start()
        container.controllerSettings.install()
        io.github.thecodingdad.gamelodge.ui.shell.StreamConnectingOverlay.install(container)
        container.scope.launch { container.filters.ensureDefaults() }
        // Button combinations: needed by the activity and by the accessibility service, which
        // can run without any activity (e.g. after an update).
        container.scope.launch {
            container.controllerSettings.all.collect { all ->
                io.github.thecodingdad.gamelodge.input.ButtonCombos.overrides = all.mapValues { (_, s) ->
                    buildMap {
                        s.comboHome?.let { put(io.github.thecodingdad.gamelodge.input.ComboAction.HOME, it.toSet()) }
                        s.comboQuickMenu?.let { put(io.github.thecodingdad.gamelodge.input.ComboAction.QUICK_MENU, it.toSet()) }
                    }
                }.filterValues { it.isNotEmpty() }
            }
        }
        io.github.thecodingdad.gamelodge.input.AndroidCombos.installReplay()
        // In the stream without the accessibility service (which otherwise sees the buttons first):
        // combinations work there too and their buttons don't reach the host.
        com.limelight.binding.input.KeyInterceptor.interceptor = com.limelight.binding.input.KeyInterceptor.Interceptor { activity, event ->
            io.github.thecodingdad.gamelodge.input.ComboAccessibilityService.instance == null &&
                io.github.thecodingdad.gamelodge.input.ButtonCombos.handle(event) { action, controller ->
                    io.github.thecodingdad.gamelodge.input.AndroidCombos.run(activity, action, controller)
                }
        }
        container.scope.launch {
            container.settings.settings.collect {
                io.github.thecodingdad.gamelodge.input.ButtonCombos.forward = it.comboForward
                io.github.thecodingdad.gamelodge.input.ButtonCombos.combos =
                    io.github.thecodingdad.gamelodge.input.ComboAction.entries.associateWith { a -> it.combo(a) }
                // Mouse, leaving the stream and its overlay are GameLodge's combinations now.
                com.limelight.StreamControl.builtInCombos = false
            }
        }
        container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // Never with a TV attached (mirrored it would show there, and nobody touches a TV).
            kotlinx.coroutines.flow.combine(
                container.settings.settings.map { it.quickMenuEdgeHandle },
                io.github.thecodingdad.gamelodge.system.ExternalDisplay.observe(this@LauncherApplication),
            ) { on, tv -> on && tv == null }.distinctUntilChanged().collect {
                io.github.thecodingdad.gamelodge.system.EdgeHandle.update(this@LauncherApplication, it)
            }
        }
        // Host web API logins of older versions aren't used any more.
        container.secrets.keys().filter { it.startsWith(io.github.thecodingdad.gamelodge.data.settings.SecretStore.Keys.LEGACY_HOST_API_PREFIX) }
            .forEach { container.secrets.put(it, null) }
        container.servers.start()
        container.metadata.start()
        // Pairing waits for a PIN typed in another app: a short foreground service keeps us awake.
        io.github.thecodingdad.gamelodge.util.BackgroundHold.start = { tag, text ->
            io.github.thecodingdad.gamelodge.system.KeepAliveService.hold(this, tag, text)
        }
        io.github.thecodingdad.gamelodge.system.OdinMapping(this)
            .watch(container.scope, container.settings.settings.map { it.odinKeepControllers })
        // Host polling is driven by MainActivity's lifecycle: Artemis' stream activity runs in
        // this process too, and polling must not compete with an active stream.
    }

    private companion object {
        const val BOOT_WINDOW_MS = 3 * 60_000L
        const val BOOT_PAUSE_MS = 10_000L
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
