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
object ArtemisStreamProfiles : StreamProfiles {
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
}
