package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.util.Log
import org.jetbrains.skiko.SkikoLoggerInterface

/** Diagnostics for problems that only show on a user's PC (drawing, hangs). */
object Diagnostics {
    /**
     * Skiko (the drawing library) logs device errors, renderer fallbacks and the like to the
     * console, which a Windows app has none of: they go to the launcher's log instead.
     */
    fun routeSkikoLog() {
        val logger = object : SkikoLoggerInterface {
            override val isTraceEnabled = false
            override val isDebugEnabled = false
            override val isInfoEnabled = true
            override val isWarnEnabled = true
            override val isErrorEnabled = true
            override fun trace(message: String) = Unit
            override fun debug(message: String) = Unit
            override fun info(message: String) = Log.i("Skiko: $message")
            override fun warn(message: String) = Log.w("Skiko: $message")
            override fun error(message: String) = Log.e("Skiko: $message")
            override fun trace(t: Throwable, message: String) = Unit
            override fun debug(t: Throwable, message: String) = Unit
            override fun info(t: Throwable, message: String) = Log.i("Skiko: $message (${t.message})")
            override fun warn(t: Throwable, message: String) = Log.w("Skiko: $message", t)
            override fun error(t: Throwable, message: String) = Log.e("Skiko: $message", t)
        }
        // Skiko's logger object is internal: its factory is set the way Java code would.
        runCatching {
            val skikoLogger = Class.forName("org.jetbrains.skiko.Logger")
            skikoLogger.getMethod("setLoggerFactory", Function0::class.java)
                .invoke(skikoLogger.getField("INSTANCE").get(null), { logger } as Function0<SkikoLoggerInterface>)
        }.onFailure { Log.w("Skiko's log not routed", it) }
    }

    /** Graphics adapter and API of a window's drawing surface ("?" while there is none yet). */
    fun renderInfo(window: java.awt.Window): String {
        fun find(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = when (c) {
            is org.jetbrains.skiko.SkiaLayer -> c
            is java.awt.Container -> c.components.firstNotNullOfOrNull(::find)
            else -> null
        }
        return runCatching { find(window)?.renderInfo }.getOrNull()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("; ") ?: "?"
    }

    /** Every thread's stack (e.g. "GameLodge.exe --threads" while the window hangs). */
    fun logThreads() {
        val dump = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n") { (t, stack) ->
            "  \"${t.name}\" ${t.state}${if (t.isDaemon) " daemon" else ""}\n" + stack.joinToString("\n") { "      at $it" }
        }
        Log.i("Threads:\n$dump")
    }
}
