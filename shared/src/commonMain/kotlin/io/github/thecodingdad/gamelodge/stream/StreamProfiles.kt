package io.github.thecodingdad.gamelodge.stream

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

    /** "1080p · 60 fps · 20 Mbit": the key values of a profile (unset values come from the base). */
    fun summary(uuid: UUID?): String
}
