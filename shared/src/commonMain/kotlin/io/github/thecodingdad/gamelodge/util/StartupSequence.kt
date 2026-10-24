package io.github.thecodingdad.gamelodge.util

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The first pass of each start task (app scan, ROM scan, servers, metadata, artwork, host search)
 * runs one after the other instead of all at once, so the launcher's start doesn't pile up memory
 * and I/O. Right after the device booted the platform adds a short pause first ([holdFor]): the
 * system is busy then, and some devices stop the heaviest foreground app under memory pressure.
 * Animated artwork starts first after the pause, and artwork is the first task in line.
 */
object StartupSequence {
    /** Fair: the tasks take their turn in the order they asked for it. */
    private val mutex = Mutex()
    @Volatile private var holdUntil = 0L

    /** False during the pause after boot: animated artwork waits as well. */
    val settled = mutableStateOf(true)

    fun holdFor(millis: Long) {
        // Animated artwork first (it's what one notices), the tasks a moment later.
        holdUntil = System.currentTimeMillis() + millis + TASKS_AFTER_ANIMATIONS_MS
        settled.value = false
        CoroutineScope(Dispatchers.Default).launch {
            delay(millis)
            settled.value = true
        }
    }

    private const val TASKS_AFTER_ANIMATIONS_MS = 2_000L

    /** Runs [block] once the tasks before it are done (and the pause after boot is over). */
    suspend fun <T> turn(block: suspend () -> T): T = mutex.withLock {
        val wait = holdUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        block()
    }
}
