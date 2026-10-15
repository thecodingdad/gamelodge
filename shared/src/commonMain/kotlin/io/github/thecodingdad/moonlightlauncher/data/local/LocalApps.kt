package io.github.thecodingdad.moonlightlauncher.data.local

import kotlinx.coroutines.flow.Flow

/** An app or game installed on this device ([packageName]: the platform's id for it). */
data class LocalApp(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
)

/** The apps and games installed on this device. */
interface LocalAppSource {
    fun scan(): List<LocalApp>

    /** Emits whenever an app is installed, removed or changed. */
    fun packageChanges(): Flow<Unit>
}
