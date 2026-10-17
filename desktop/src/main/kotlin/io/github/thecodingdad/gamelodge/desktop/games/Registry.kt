package io.github.thecodingdad.gamelodge.desktop.games

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.github.thecodingdad.gamelodge.desktop.isWindows

/** Windows registry reads that give "nothing found" elsewhere (tests on Linux) and on errors. */
object Reg {
    private fun root(hive: String) = if (hive == "HKCU") WinReg.HKEY_CURRENT_USER else WinReg.HKEY_LOCAL_MACHINE

    fun read(hive: String, path: String, name: String): String? {
        if (!isWindows) return null
        return runCatching {
            if (!Advapi32Util.registryValueExists(root(hive), path, name)) null
            else Advapi32Util.registryGetValue(root(hive), path, name)?.toString()
        }.getOrNull()
    }

    /** Sub keys of [path] with their values (names case-insensitive). */
    fun subKeys(hive: String, path: String): List<Pair<String, (String) -> String?>> {
        if (!isWindows) return emptyList()
        val names = runCatching { Advapi32Util.registryGetKeys(root(hive), path).toList() }.getOrDefault(emptyList())
        return names.mapNotNull { name ->
            runCatching {
                val values = Advapi32Util.registryGetValues(root(hive), "$path\\$name")
                    .mapKeys { it.key.lowercase() }.mapValues { it.value?.toString() }
                name to { key: String -> values[key.lowercase()] }
            }.getOrNull()
        }
    }

    /** All "Programs and Features" entries (machine 64/32 bit and user). */
    fun uninstallEntries(): List<Pair<String, (String) -> String?>> = listOf(
        "HKLM" to "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "HKLM" to "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "HKCU" to "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
    ).flatMap { (hive, path) -> subKeys(hive, path) }
}
