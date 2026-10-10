package io.github.thecodingdad.moonlightlauncher

import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.limelight.ArtemisApplication
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
        container.scope.launch { container.filters.ensureDefaults() }
        container.metadata.start()
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
