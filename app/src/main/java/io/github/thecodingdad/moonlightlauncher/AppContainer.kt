package io.github.thecodingdad.moonlightlauncher

import android.content.Context
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.library.LibraryRepository
import io.github.thecodingdad.moonlightlauncher.data.library.LibrarySync
import io.github.thecodingdad.moonlightlauncher.data.library.PlaytimeTracker
import io.github.thecodingdad.moonlightlauncher.data.local.LocalAppScanner
import io.github.thecodingdad.moonlightlauncher.data.remote.BoxArtCache
import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container; one instance per process, owned by [LauncherApplication]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LauncherDatabase by lazy { LauncherDatabase.create(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val secrets: SecretStore by lazy { SecretStore(appContext) }

    val localApps: LocalAppScanner by lazy { LocalAppScanner(appContext) }
    val hosts: HostRepository by lazy { HostRepository(appContext, scope) }
    val boxArt: BoxArtCache by lazy { BoxArtCache(appContext) }

    val library: LibraryRepository by lazy {
        LibraryRepository(database, LibrarySync(database), localApps, hosts, boxArt, scope)
    }
    val playtime: PlaytimeTracker by lazy { PlaytimeTracker(database) }
}
