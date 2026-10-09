package io.github.thecodingdad.moonlightlauncher.util

object Log {
    private const val TAG = "MoonlightLauncher"

    fun d(message: String) = android.util.Log.d(TAG, message)
    fun i(message: String) = android.util.Log.i(TAG, message)
    fun w(message: String, t: Throwable? = null) = android.util.Log.w(TAG, message, t)
    fun e(message: String, t: Throwable? = null) = android.util.Log.e(TAG, message, t)
}
