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

    /** Exceptions nobody caught (also in coroutines on the UI thread) go to the log, not to a console nobody sees. */
    fun logUncaught() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e("Uncaught in ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Every thread's stack and every window's state (e.g. "GameLodge.exe --threads" while the
     * window hangs): what Java, Windows and the drawing surface each think of it.
     */
    fun logThreads() {
        val dump = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n") { (t, stack) ->
            "  \"${t.name}\" ${t.state}${if (t.isDaemon) " daemon" else ""}\n" + stack.joinToString("\n") { "      at $it" }
        }
        Log.i("Threads:\n$dump")
        val windows = java.util.concurrent.CompletableFuture<String>()
        java.awt.EventQueue.invokeLater { windows.complete(runCatching { describeWindows() }.getOrElse { "failed: $it" }) }
        Log.i("Windows:\n" + runCatching { windows.get(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { "UI thread didn't answer in 3 s" })
    }

    private fun describeWindows(): String = java.awt.Window.getWindows().joinToString("\n") { w ->
        val title = (w as? java.awt.Frame)?.title ?: w.javaClass.simpleName
        val awt = "visible=${w.isVisible} showing=${w.isShowing} displayable=${w.isDisplayable} active=${w.isActive} " +
            "state=${(w as? java.awt.Frame)?.extendedState} bounds=${w.bounds.let { "${it.x},${it.y} ${it.width}x${it.height}" }}"
        val native = if (isWindows && w.isDisplayable) runCatching {
            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getComponentPointer(w))
            val user32 = com.sun.jna.platform.win32.User32.INSTANCE
            val cloaked = com.sun.jna.ptr.IntByReference()
            com.sun.jna.NativeLibrary.getInstance("dwmapi").getFunction("DwmGetWindowAttribute")
                .invokeInt(arrayOf(hwnd, DWMWA_CLOAKED, cloaked, 4))
            val r = com.sun.jna.platform.win32.WinDef.RECT().also { user32.GetWindowRect(hwnd, it) }
            "visible=${user32.IsWindowVisible(hwnd)} minimized=${user32.GetWindowLong(hwnd, com.sun.jna.platform.win32.WinUser.GWL_STYLE) and 0x20000000 != 0} cloaked=${cloaked.value} " +
                "rect=${r.left},${r.top} ${r.right - r.left}x${r.bottom - r.top}"
        }.getOrElse { "? ($it)" } else "-"
        "  \"$title\"\n      Java: $awt\n      Windows: $native\n      Drawing: ${drawingState(w)}"
    }

    /** The drawing surface: shown or not, and whether its frame loop still runs (Skiko internals, read reflectively). */
    private fun drawingState(window: java.awt.Window): String {
        fun find(c: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = when (c) {
            is org.jetbrains.skiko.SkiaLayer -> c
            is java.awt.Container -> c.components.firstNotNullOfOrNull(::find)
            else -> null
        }
        val layer = find(window) ?: return "no surface"
        fun field(o: Any, name: String): Any? {
            var c: Class<*>? = o.javaClass
            while (c != null) {
                c.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it.get(o) }
                c = c.superclass
            }
            return null
        }
        val loop = runCatching {
            val redrawer = layer.javaClass.methods.firstOrNull { it.name.startsWith("getRedrawer") && it.parameterCount == 0 }
                ?.invoke(layer) ?: return@runCatching "no redrawer"
            val dispatcher = field(redrawer, "frameDispatcher") ?: return@runCatching "${redrawer.javaClass.simpleName}, no frame loop"
            val job = field(dispatcher, "job") as? kotlinx.coroutines.Job
            "${redrawer.javaClass.simpleName}, frame loop active=${job?.isActive} cancelled=${job?.isCancelled} scheduled=${field(dispatcher, "frameScheduled")}"
        }.getOrElse { "? ($it)" }
        return "showing=${layer.isShowing} size=${layer.width}x${layer.height} $loop"
    }

    private const val DWMWA_CLOAKED = 14
}
