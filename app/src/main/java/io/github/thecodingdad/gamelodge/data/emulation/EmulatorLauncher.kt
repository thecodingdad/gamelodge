package io.github.thecodingdad.gamelodge.data.emulation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

/** Why a ROM can't be started. */
enum class RomStartProblem { NO_EMULATOR, NEEDS_PATH }

/**
 * Builds the intent that starts a ROM with an emulator. The command's placeholders (ES-DE's names)
 * are filled in: the game as document URI (%ROMSAF%, %ROMPROVIDER%) — the launcher holds the folder
 * permission and passes read/write access on — or as real path (%ROM% …) for emulators that only
 * take paths and read the file themselves.
 */
object EmulatorLauncher {
    private val PATH_PLACEHOLDERS = listOf("%ROM%", "%ROMRAW%", "%GAMEDIRRAW%", "%ROMPATH%", "%ROMPATHRAW%")

    sealed interface Result {
        data class Ok(val intent: Intent) : Result
        data class Problem(val problem: RomStartProblem) : Result
    }

    fun intent(context: Context, choice: EmulatorChoice, romUri: String, romPath: String?): Result {
        val cmd = choice.command
        val pkg = choice.component.packageName
        val uri = Uri.parse(romUri)
        val fileName = romPath?.substringAfterLast('/') ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val values = buildMap {
            put("%ROMSAF%", romUri)
            put("%ROMPROVIDER%", romUri)
            put("%BASENAME%", fileName.substringBeforeLast('.', fileName))
            put("%EXTERNALDATA%", Environment.getExternalStorageDirectory().path)
            put("%INTERNALDATA%", "/data/data")
            put("%ANDROIDPACKAGE%", pkg)
            if (romPath != null) {
                put("%ROM%", romPath)
                put("%ROMRAW%", romPath)
                val dir = romPath.substringBeforeLast('/')
                put("%GAMEDIRRAW%", dir)
                put("%ROMPATH%", dir.substringBeforeLast('/'))
                put("%ROMPATHRAW%", dir.substringBeforeLast('/'))
            }
        }
        fun fill(template: String): String? {
            // Path placeholders without a known path: this emulator can't be served.
            if (romPath == null && PATH_PLACEHOLDERS.any { it in template }) return null
            var out = template
            for ((key, value) in values) out = out.replace(key, value)
            return out
        }

        val intent = Intent().setComponent(choice.component)
        cmd.action?.let { intent.action = it }
        cmd.category?.let { intent.addCategory(it) }
        cmd.data?.let { intent.data = Uri.parse(fill(it) ?: return Result.Problem(RomStartProblem.NEEDS_PATH)) }
        for (extra in cmd.extras) {
            val value = fill(extra.value) ?: return Result.Problem(RomStartProblem.NEEDS_PATH)
            when (extra.type) {
                "bool" -> intent.putExtra(extra.name, value.toBoolean())
                "int" -> intent.putExtra(extra.name, value.toIntOrNull() ?: 0)
                "long" -> intent.putExtra(extra.name, value.toLongOrNull() ?: 0L)
                "array" -> intent.putExtra(extra.name, value.split(',').toTypedArray())
                else -> intent.putExtra(extra.name, value)
            }
        }
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if ("CLEAR_TASK" in cmd.flags) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
        if ("CLEAR_TOP" in cmd.flags) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if ("NO_HISTORY" in cmd.flags) flags = flags or Intent.FLAG_ACTIVITY_NO_HISTORY
        intent.addFlags(flags)

        // The emulator may read (and write, e.g. Switch updates) the game file through our grant,
        // also when it gets the URI as an extra (the clip data carries the grant along).
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (intent.data == null) intent.clipData = ClipData.newRawUri("", uri)
        runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return Result.Ok(intent)
    }
}
