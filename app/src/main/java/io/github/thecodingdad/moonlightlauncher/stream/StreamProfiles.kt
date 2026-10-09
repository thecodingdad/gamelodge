package io.github.thecodingdad.moonlightlauncher.stream

import com.limelight.profiles.ProfilesManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** A stream profile; [uuid] null is the base configuration ("Standard"). */
data class StreamProfile(val uuid: UUID?, val name: String)

/**
 * Thin wrapper around Artemis' [ProfilesManager]. The active profile overlays the base
 * stream settings, so "active profile" and "start with…" need no changes in Artemis.
 */
object StreamProfiles {
    private val manager get() = ProfilesManager.getInstance()

    fun all(defaultName: String): List<StreamProfile> =
        listOf(StreamProfile(null, defaultName)) + manager.profiles.map { StreamProfile(it.uuid, it.name) }

    fun active(defaultName: String): StreamProfile =
        manager.active?.let { StreamProfile(it.uuid, it.name) } ?: StreamProfile(null, defaultName)

    fun setActive(uuid: UUID?) = manager.setActive(uuid)

    /** Emits on every profile change (added, renamed, activated). */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = ProfilesManager.ProfileChangeListener { trySend(Unit) }
        manager.addListener(listener)
        trySend(Unit)
        awaitClose { manager.removeListener(listener) }
    }
}
