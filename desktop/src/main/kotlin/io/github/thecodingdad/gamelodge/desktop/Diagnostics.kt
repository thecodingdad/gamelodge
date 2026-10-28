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
        // Which threads are busy: their CPU time over a few seconds.
        runCatching {
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            val names = Thread.getAllStackTraces().keys.associate { it.threadId() to it.name }
            val before = names.keys.associateWith { bean.getThreadCpuTime(it) }
            Thread.sleep(CPU_SAMPLE_MS)
            val busy = before.mapNotNull { (id, start) ->
                val end = bean.getThreadCpuTime(id)
                if (start < 0 || end < 0) null else names[id] to (end - start) / 1_000_000
            }.filter { it.second > 0 }.sortedByDescending { it.second }.take(10)
            Log.i("CPU in the last ${CPU_SAMPLE_MS / 1000} s:\n" + busy.joinToString("\n") { (name, ms) -> "  $ms ms  \"$name\"" }.ifEmpty { "  (idle)" })
        }.onFailure { Log.w("CPU per thread unavailable", it) }
        // All threads of the process as Windows sees them: also the JVM's own (garbage collector,
        // compiler) and the graphics driver's, which the list above doesn't know.
        if (isWindows) runCatching { Log.i("CPU of all threads in the last ${CPU_SAMPLE_MS / 1000} s (Windows):\n" + nativeThreadCpu()) }
            .onFailure { Log.w("CPU of native threads unavailable", it) }
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
            "focus=${w.focusOwner?.javaClass?.simpleName} recentFocus=${w.mostRecentFocusOwner?.javaClass?.simpleName} " +
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

    /** CPU time per thread of this process over [CPU_SAMPLE_MS], with the threads' names where they have one. */
    private fun nativeThreadCpu(): String {
        fun sample(): Map<Int, Pair<Long, String>> {
            val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
            val pid = kernel.GetCurrentProcessId()
            val snapshot = kernel.CreateToolhelp32Snapshot(com.sun.jna.platform.win32.WinDef.DWORD(TH32CS_SNAPTHREAD), com.sun.jna.platform.win32.WinDef.DWORD(0))
            val result = HashMap<Int, Pair<Long, String>>()
            val times = com.sun.jna.NativeLibrary.getInstance("kernel32").getFunction("GetThreadTimes")
            val describe = runCatching { com.sun.jna.NativeLibrary.getInstance("kernel32").getFunction("GetThreadDescription") }.getOrNull()
            try {
                val entry = com.sun.jna.platform.win32.Tlhelp32.THREADENTRY32()
                var more = kernel.Thread32First(snapshot, entry)
                while (more) {
                    if (entry.th32OwnerProcessID == pid) {
                        val handle = kernel.OpenThread(THREAD_QUERY_LIMITED_INFORMATION, false, entry.th32ThreadID)
                        if (handle != null) {
                            try {
                                val ft = Array(4) { com.sun.jna.platform.win32.WinBase.FILETIME() }
                                if (times.invokeInt(arrayOf(handle, ft[0], ft[1], ft[2], ft[3])) != 0) {
                                    // Kernel + user time in 100 ns.
                                    val cpu = ft[2].toDWordLong().toLong() + ft[3].toDWordLong().toLong()
                                    val name = describe?.let { f ->
                                        val ref = com.sun.jna.ptr.PointerByReference()
                                        if (f.invokeInt(arrayOf(handle, ref)) >= 0 && ref.value != null) {
                                            ref.value.getWideString(0).also { kernel.LocalFree(ref.value) }
                                        } else null
                                    }.orEmpty()
                                    result[entry.th32ThreadID] = cpu to name
                                }
                            } finally {
                                kernel.CloseHandle(handle)
                            }
                        }
                    }
                    more = kernel.Thread32Next(snapshot, entry)
                }
            } finally {
                kernel.CloseHandle(snapshot)
            }
            return result
        }
        val before = sample()
        Thread.sleep(CPU_SAMPLE_MS)
        val after = sample()
        val busy = after.mapNotNull { (id, now) ->
            val then = before[id]?.first ?: 0L
            val ms = (now.first - then) / 10_000
            if (ms > 0) Triple(id, ms, now.second) else null
        }.sortedByDescending { it.second }.take(15)
        val total = busy.sumOf { it.second }
        return busy.joinToString("\n") { (id, ms, name) -> "  $ms ms  #$id \"${name.ifEmpty { "(no name)" }}\"" }.ifEmpty { "  (idle)" } +
            "\n  together $total ms of ${CPU_SAMPLE_MS} ms, ${after.size} threads"
    }

    private const val TH32CS_SNAPTHREAD = 0x4L
    private const val THREAD_QUERY_LIMITED_INFORMATION = 0x0800
    private const val DWMWA_CLOAKED = 14
    private const val CPU_SAMPLE_MS = 3000L
}
