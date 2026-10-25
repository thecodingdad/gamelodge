package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A game the launcher started on this device and can follow (Windows: its processes), for the
 * quick menu's "Running" section. Android apps can't be closed by another app: none there.
 */
object LocalSessions {
    class Running(val entry: LibraryEntry, val resume: () -> Unit, val quit: suspend () -> Unit)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    fun started(session: Running) { _running.value = session }

    fun ended(entry: LibraryEntry) {
        if (_running.value?.entry?.item?.id == entry.item.id) _running.value = null
    }
}
