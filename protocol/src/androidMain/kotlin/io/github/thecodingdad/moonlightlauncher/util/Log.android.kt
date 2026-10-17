package io.github.thecodingdad.moonlightlauncher.util

actual object Log {
    private const val TAG = "MoonlightLauncher"

    actual fun d(message: String) { android.util.Log.d(TAG, message) }
    actual fun i(message: String) { android.util.Log.i(TAG, message) }
    actual fun w(message: String, t: Throwable?) { android.util.Log.w(TAG, message, t) }
    actual fun e(message: String, t: Throwable?) { android.util.Log.e(TAG, message, t) }
}
