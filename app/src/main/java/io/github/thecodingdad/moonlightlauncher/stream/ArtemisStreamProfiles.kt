package io.github.thecodingdad.moonlightlauncher.stream

import com.limelight.profiles.ProfilesManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Thin wrapper around Artemis' [ProfilesManager]. The active profile overlays the base
 * stream settings, so "active profile" and "start with…" need no changes in Artemis.
 */
class ArtemisStreamProfiles(private val context: android.content.Context) : StreamProfiles {
    private val manager get() = ProfilesManager.getInstance()

    override fun all(defaultName: String): List<StreamProfile> =
        listOf(StreamProfile(null, defaultName)) + manager.profiles.map { StreamProfile(it.uuid, it.name) }

    override fun active(defaultName: String): StreamProfile =
        manager.active?.let { StreamProfile(it.uuid, it.name) } ?: StreamProfile(null, defaultName)

    override fun setActive(uuid: UUID?) = manager.setActive(uuid)

    override fun changes(): Flow<Unit> = callbackFlow {
        val listener = ProfilesManager.ProfileChangeListener { trySend(Unit) }
        manager.addListener(listener)
        trySend(Unit)
        awaitClose { manager.removeListener(listener) }
    }

    override fun summary(uuid: UUID?): String {
        val base = ProfileStore(context, null)
        val profile = ProfileStore(context, uuid)
        fun value(key: String, default: String) = profile.getString(key, null) ?: base.getString(key, default) ?: default
        val resolution = value("list_resolution", "1280x720")
        val fps = value("list_fps", "60")
        val height = resolution.substringAfter('x', "").takeIf { it.isNotEmpty() }?.let { "${it}p" } ?: resolution
        val defaultBitrate = com.limelight.preferences.PreferenceConfiguration.getDefaultBitrate(resolution, fps)
        val kbps = profile.getString("seekbar_bitrate_kbps", null)?.toIntOrNull()
            ?: base.getInt("seekbar_bitrate_kbps", defaultBitrate)
        val mbit = if (kbps % 1000 == 0) "${kbps / 1000}" else "%.1f".format(kbps / 1000f)
        return "$height · $fps fps · $mbit Mbit"
    }
}
