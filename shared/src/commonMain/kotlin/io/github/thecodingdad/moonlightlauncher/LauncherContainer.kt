package io.github.thecodingdad.moonlightlauncher

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkStore
import io.github.thecodingdad.moonlightlauncher.data.companion.CompanionFeed
import io.github.thecodingdad.moonlightlauncher.data.companion.ServerRepository
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.emulation.Emulators
import io.github.thecodingdad.moonlightlauncher.data.library.FilterRepository
import io.github.thecodingdad.moonlightlauncher.data.library.LibraryRepository
import io.github.thecodingdad.moonlightlauncher.data.library.PlaytimeTracker
import io.github.thecodingdad.moonlightlauncher.data.remote.HostActions
import io.github.thecodingdad.moonlightlauncher.data.remote.HostRepository
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.ha.HaAuth
import io.github.thecodingdad.moonlightlauncher.ha.HaRepository
import io.github.thecodingdad.moonlightlauncher.metadata.MetadataService
import io.github.thecodingdad.moonlightlauncher.platform.PlatformInfo
import io.github.thecodingdad.moonlightlauncher.stream.HostPower
import io.github.thecodingdad.moonlightlauncher.stream.LaunchCoordinator
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfiles
import io.github.thecodingdad.moonlightlauncher.system.ExternalDisplayInfo
import io.github.thecodingdad.moonlightlauncher.vpn.Vpn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Everything the shared UI and view model use; each platform provides one per process. */
interface LauncherContainer {
    val scope: CoroutineScope
    val platform: PlatformInfo
    val database: LauncherDatabase
    val settings: SettingsRepository
    val secrets: SecretStore
    val hosts: HostRepository
    val hostActions: HostActions
    val hostPower: HostPower
    val library: LibraryRepository
    val filters: FilterRepository
    val playtime: PlaytimeTracker
    val metadata: MetadataService
    val servers: ServerRepository
    val companionFeed: CompanionFeed
    val artwork: ArtworkStore
    val ha: HaRepository
    val haAuth: HaAuth
    val launcher: LaunchCoordinator
    val profiles: StreamProfiles
    val emulators: Emulators
    /** [io.github.thecodingdad.moonlightlauncher.vpn.NoVpn] where the launcher brings no VPN of its own. */
    val vpn: Vpn

    /** TV/monitor attached to a handheld (or null), and again whenever that changes. */
    fun externalDisplay(): Flow<ExternalDisplayInfo?>
}

val LocalContainer = staticCompositionLocalOf<LauncherContainer> { error("No launcher container") }
