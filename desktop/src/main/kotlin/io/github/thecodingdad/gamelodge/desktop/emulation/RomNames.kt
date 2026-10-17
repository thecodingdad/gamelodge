package io.github.thecodingdad.gamelodge.desktop.emulation

import io.github.thecodingdad.gamelodge.desktop.games.WinPath

/** Turns ROM file names into game titles ("Super Mario Sunshine (USA) (Rev 1).rvz" → "Super Mario Sunshine"). */
object RomNames {
    private val tags = Regex("""\s*[\(\[][^\)\]]*[\)\]]""")
    private val discTag = Regex("""[\(\[]\s*(Disc|Disk|CD)\s*(\d+)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
    private val switchTitleId = Regex("""\[([0-9A-Fa-f]{16})\]""")
    private val spaces = Regex("""\s{2,}""")
    private val trailingDash = Regex("""\s*-\s*$""")
    private val article = Regex("""^(.*), (The|A|An|Der|Die|Das)( - .*|: .*)?$""")
    private val switchAddon = Regex("""[\[\(](UPD|Update|DLC)[\]\)]""", RegexOption.IGNORE_CASE)

    /** File names that say nothing about the game (Xbox 360 "Default.xex", PS3 "EBOOT.BIN" …). */
    private val genericFiles = setOf("default", "eboot", "main", "game", "boot", "start", "launcher", "index", "rom", "disc", "disc1", "image")

    /** Folders between the game folder and its main file ("code", "USRDIR" …). */
    private val technicalFolders = setOf("code", "content", "meta", "usrdir", "ps3_game", "bin", "data", "game", "default", "disc", "disc1", "files")

    fun isGeneric(nameWithoutExtension: String) = nameWithoutExtension.trim().lowercase() in genericFiles

    /** Nearest folder above [file] that isn't a technical one, staying inside [root]; null when none. */
    fun folderName(file: String, root: String): String? {
        var dir = WinPath.directory(file)
        val rootLength = root.trimEnd('\\', '/').length
        while (dir != null && dir.length > rootLength) {
            val name = WinPath.fileName(dir)
            if (name.lowercase() !in technicalFolders) return name
            dir = WinPath.directory(dir)
        }
        return null
    }

    fun clean(nameWithoutExtension: String): String {
        var name = tags.replace(nameWithoutExtension, "").replace('_', ' ')
        // "Legend of Zelda, The" → "The Legend of Zelda"
        article.matchEntire(name)?.let { m -> name = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}" }
        name = trailingDash.replace(spaces.replace(name, " "), "")
        return name.trim()
    }

    /** Disc number from "(Disc 2)" / "[CD2]", else null. */
    fun disc(fileName: String): Int? = discTag.find(fileName)?.groupValues?.get(2)?.toIntOrNull()

    /** Switch dumps of updates and DLC (title id not ending in 000, or [UPD]/[DLC] tags). */
    fun isSwitchAddon(fileName: String): Boolean {
        if (switchAddon.containsMatchIn(fileName)) return true
        val id = switchTitleId.find(fileName) ?: return false
        return !id.groupValues[1].endsWith("000")
    }
}

/**
 * Header of an Xbox 360 STFS package ("CON ", "LIVE", "PIRS"): how Arcade and Games-on-Demand
 * titles are stored. The files have no extension, the header says what they are and their name.
 */
object Stfs {
    private const val ARCADE = 0x000D0000L
    private const val GAMES_ON_DEMAND = 0x00007000L
    private const val HEADER = 0x1712

    data class Info(val contentType: Long, val titleId: Long, val title: String?) {
        val isGame: Boolean get() = contentType == ARCADE || contentType == GAMES_ON_DEMAND
    }

    fun read(file: java.io.File): Info? = runCatching {
        if (file.length() < HEADER) return null
        val b = ByteArray(HEADER)
        file.inputStream().use { input -> var off = 0; while (off < HEADER) { val n = input.read(b, off, HEADER - off); if (n < 0) return null; off += n } }
        parse(b)
    }.getOrNull()

    fun parse(b: ByteArray): Info? {
        if (b.size < HEADER) return null
        val magic = String(b, 0, 4, Charsets.US_ASCII)
        if (magic != "CON " && magic != "LIVE" && magic != "PIRS") return null
        fun u32(o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        fun text(offset: Int) = String(b, offset, 0x80, Charsets.UTF_16BE).substringBefore('\u0000').trim().ifEmpty { null }
        return Info(u32(0x344), u32(0x360), text(0x1691) ?: text(0x411))
    }
}
