package io.github.thecodingdad.gamelodge.util

/**
 * Keeps the app running while a short task waits for the user outside of it (pairing: the PIN is
 * typed in a browser). Android freezes apps that aren't visible within seconds; the platform
 * holds them awake (a short foreground service with a notification showing [text]).
 */
object BackgroundHold {
    /** Set by the platform; the default does nothing (the desktop keeps running anyway). */
    var start: (tag: String, text: String) -> AutoCloseable = { _, _ -> AutoCloseable {} }

    inline fun <T> during(tag: String, text: String, block: () -> T): T {
        val hold = runCatching { start(tag, text) }.getOrNull()
        try {
            return block()
        } finally {
            runCatching { hold?.close() }
        }
    }
}
