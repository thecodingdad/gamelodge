package io.github.thecodingdad.moonlightlauncher.util

import java.util.Locale

/** "12 MB", "1.4 GB": short file size in decimal units, like Android's own formatting. */
fun formatFileSize(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 900 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text = when {
        unit == 0 || value >= 100 -> "%.0f".format(Locale.getDefault(), value)
        else -> "%.1f".format(Locale.getDefault(), value).removeSuffix("0").removeSuffix(".").removeSuffix(",")
    }
    return "$text ${units[unit]}"
}
