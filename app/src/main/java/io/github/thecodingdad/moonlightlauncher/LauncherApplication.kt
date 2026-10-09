package io.github.thecodingdad.moonlightlauncher

import android.content.Context
import com.limelight.ArtemisApplication

/**
 * Extends Artemis' application so its global state (stream profiles) is initialised
 * exactly as in a standalone Artemis install.
 */
class LauncherApplication : ArtemisApplication() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.hosts.bind()
        container.library.start()
        // Host polling is driven by MainActivity's lifecycle: Artemis' stream activity runs in
        // this process too, and polling must not compete with an active stream.
    }
}

val Context.container: AppContainer
    get() = (applicationContext as LauncherApplication).container
