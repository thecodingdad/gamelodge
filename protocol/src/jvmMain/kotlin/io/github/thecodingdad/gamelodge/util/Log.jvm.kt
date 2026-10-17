package io.github.thecodingdad.gamelodge.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

actual object Log {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    @Volatile private var file: File? = null

    /** Log file in the data folder; set once at start. Without it, the log goes to stderr only. */
    fun init(logFile: File) {
        logFile.parentFile?.mkdirs()
        // Keep the previous run for comparisons, start fresh otherwise.
        if (logFile.exists()) logFile.renameTo(File(logFile.parentFile, logFile.nameWithoutExtension + ".old.log"))
        file = logFile
    }

    actual fun d(message: String) = write("D", message, null)
    actual fun i(message: String) = write("I", message, null)
    actual fun w(message: String, t: Throwable?) = write("W", message, t)
    actual fun e(message: String, t: Throwable?) = write("E", message, t)

    @Synchronized
    private fun write(level: String, message: String, t: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(time)).append(' ').append(level).append(' ').append(message)
            if (t != null) append('\n').append(StringWriter().also { t.printStackTrace(PrintWriter(it)) })
        }
        System.err.println(line)
        file?.let { runCatching { it.appendText(line + "\n") } }
    }
}
