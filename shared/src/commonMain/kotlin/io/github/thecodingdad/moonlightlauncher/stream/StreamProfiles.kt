package io.github.thecodingdad.moonlightlauncher.stream

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A stream profile; [uuid] null is the base configuration ("Standard"). */
data class StreamProfile(val uuid: UUID?, val name: String)

/** The stream profiles of the platform's streaming client and which one is active. */
interface StreamProfiles {
    fun all(defaultName: String): List<StreamProfile>

    fun active(defaultName: String): StreamProfile

    fun setActive(uuid: UUID?)

    /** Emits on every profile change (added, renamed, activated). */
    fun changes(): Flow<Unit>
}
