package io.github.thecodingdad.gamelodge

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkStore
import io.github.thecodingdad.gamelodge.data.companion.CompanionFeed
import io.github.thecodingdad.gamelodge.data.companion.ServerRepository
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.emulation.Emulators
import io.github.thecodingdad.gamelodge.data.library.FilterRepository
import io.github.thecodingdad.gamelodge.data.library.LibraryRepository
import io.github.thecodingdad.gamelodge.data.library.PlaytimeTracker
import io.github.thecodingdad.gamelodge.data.remote.HostActions
import io.github.thecodingdad.gamelodge.data.remote.HostRepository
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
import io.github.thecodingdad.gamelodge.ha.HaAuth
import io.github.thecodingdad.gamelodge.ha.HaRepository
import io.github.thecodingdad.gamelodge.metadata.MetadataService
import io.github.thecodingdad.gamelodge.platform.PlatformInfo
import io.github.thecodingdad.gamelodge.stream.HostPower
import io.github.thecodingdad.gamelodge.stream.LaunchCoordinator
import io.github.thecodingdad.gamelodge.stream.StreamProfiles
import io.github.thecodingdad.gamelodge.system.ExternalDisplayInfo
import io.github.thecodingdad.gamelodge.vpn.Vpn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Everything the shared UI and view model use; each platform provides one per process. */
interface LauncherContainer {
    val scope: CoroutineScope
    val platform: PlatformInfo
    val database: LauncherDatabase
    val settings: SettingsRepository
    val secrets: SecretStore
    /** The SteamGridDB / IGDB keys with their change times (shared through a host). */
    val keySync: io.github.thecodingdad.gamelodge.data.sync.KeySync
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
    /** Status sent to Home Assistant over MQTT (started once at launch). */
    val mqtt: io.github.thecodingdad.gamelodge.ha.mqtt.MqttBridge

    /** Steering the running stream from the quick menu (what the streaming client allows). */
    val streamControls: io.github.thecodingdad.gamelodge.stream.StreamControls
        get() = io.github.thecodingdad.gamelodge.stream.NoStreamControls
    val profiles: StreamProfiles

    /** Start actions to pick with "start with…" (streamed games from the host's GameLodge; the PC adds its own games). */
    val startActions: io.github.thecodingdad.gamelodge.stream.StartActionSource
        get() = io.github.thecodingdad.gamelodge.stream.StartActionSource({ companionFeed })
    val emulators: Emulators
    /** [io.github.thecodingdad.gamelodge.vpn.NoVpn] where the launcher brings no VPN of its own. */
    val vpn: Vpn
    val backup: io.github.thecodingdad.gamelodge.data.backup.BackupManager
    val backupStorage: io.github.thecodingdad.gamelodge.data.backup.BackupStorage
    val data: io.github.thecodingdad.gamelodge.data.storage.DataMaintenance

    /** TV/monitor attached to a handheld (or null), and again whenever that changes. */
    fun externalDisplay(): Flow<ExternalDisplayInfo?>
}

val LocalContainer = staticCompositionLocalOf<LauncherContainer> { error("No launcher container") }
