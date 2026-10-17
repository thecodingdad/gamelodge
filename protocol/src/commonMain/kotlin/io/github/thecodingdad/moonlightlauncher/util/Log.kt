package io.github.thecodingdad.moonlightlauncher.util

/** App log: logcat on Android, the log file on the desktop. */
expect object Log {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String, t: Throwable? = null)
    fun e(message: String, t: Throwable? = null)
}
