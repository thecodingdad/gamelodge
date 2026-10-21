package io.github.thecodingdad.gamelodge.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import io.github.thecodingdad.gamelodge.util.Log
import java.io.File

/**
 * Windows' "Open Xbox Game Bar using this button on a controller": while the launcher runs, the
 * Guide button belongs to it (back to the launcher, quick menu), so the option is switched off and
 * restored when the launcher ends. The previous state is kept in [marker] until then, so a crash
 * doesn't lose it (the next run restores it on its exit).
 */
class GameBarButton(private val marker: File) {
    fun takeOver() {
        if (!isWindows) return
        runCatching {
            // Left from a crash: already off, the state before is in the marker.
            if (marker.exists() || !enabled()) return@runCatching
            marker.parentFile?.mkdirs()
            marker.writeText(if (Advapi32Util.registryValueExists(ROOT, KEY, VALUE)) "1" else "default")
            Advapi32Util.registrySetIntValue(ROOT, KEY, VALUE, 0)
            Log.i("Game Bar controller button switched off while running")
        }.onFailure { Log.w("Game Bar controller option not changed", it) }
        Runtime.getRuntime().addShutdownHook(Thread({ restore() }, "game-bar-restore"))
    }

    private fun restore() {
        runCatching {
            val before = marker.takeIf { it.exists() }?.readText()?.trim() ?: return
            if (before == "default") Advapi32Util.registryDeleteValue(ROOT, KEY, VALUE)
            else Advapi32Util.registrySetIntValue(ROOT, KEY, VALUE, 1)
            marker.delete()
        }
    }

    /** Missing value = Windows' default, on. */
    private fun enabled(): Boolean =
        !Advapi32Util.registryKeyExists(ROOT, KEY) || !Advapi32Util.registryValueExists(ROOT, KEY, VALUE) ||
            Advapi32Util.registryGetIntValue(ROOT, KEY, VALUE) != 0

    private companion object {
        val ROOT: WinReg.HKEY = WinReg.HKEY_CURRENT_USER
        const val KEY = "Software\\Microsoft\\GameBar"
        const val VALUE = "UseNexusForGameBarEnabled"
    }
}
