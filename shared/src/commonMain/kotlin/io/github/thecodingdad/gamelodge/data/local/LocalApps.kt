package io.github.thecodingdad.gamelodge.data.local

import kotlinx.coroutines.flow.Flow

/** An app or game installed on this device ([packageName]: the platform's id for it). */
data class LocalApp(
    val packageName: String,
    val label: String,
    /** The platform's own hint (Android's game category); the classifier decides. */
    val isGame: Boolean,
    /** The source knows for sure (PC store libraries): game (true) or app (false). */
    val knownGame: Boolean? = null,
    /** False for an owned game that isn't installed: starting it installs it. */
    val installed: Boolean = true,
    /** Platform name ("PC"), when the source knows it. */
    val platform: String? = null,
    /** Cover from the source (store artwork URL or file), used until metadata brings one. */
    val cover: String? = null,
)

/** The apps and games installed on this device. */
interface LocalAppSource {
    suspend fun scan(): List<LocalApp>

    /** Emits whenever an app is installed, removed or changed. */
    fun packageChanges(): Flow<Unit>
}
