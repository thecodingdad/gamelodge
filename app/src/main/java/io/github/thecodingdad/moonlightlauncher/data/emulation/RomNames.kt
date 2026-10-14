package io.github.thecodingdad.moonlightlauncher.data.emulation

/** Turns ROM file names into game titles ("Super Mario Sunshine (USA) (Rev 1).rvz" → "Super Mario Sunshine"); as in the companion. */
object RomNames {
    private val tags = Regex("""\s*[\(\[][^\)\]]*[\)\]]""")
    private val discTag = Regex("""[\(\[]\s*(Disc|Disk|CD)\s*(\d+)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
    private val switchTitleId = Regex("""\[([0-9A-Fa-f]{16})]""")
    private val switchAddonTag = Regex("""[\[(](UPD|Update|DLC)[\])]""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s{2,}""")
    private val trailingDash = Regex("""\s*-\s*$""")
    private val article = Regex("""^(.*), (The|A|An|Der|Die|Das)( - .*|: .*)?$""")

    /** File names that say nothing about the game (Xbox 360 "Default.xex", PS3 "EBOOT.BIN" …). */
    private val genericFiles = setOf("default", "eboot", "main", "game", "boot", "start", "launcher", "index", "rom", "disc", "disc1", "image")

    /** Folders between the game folder and its main file ("code", "USRDIR" …). */
    private val technicalFolders = setOf("code", "content", "meta", "usrdir", "ps3_game", "bin", "data", "game", "default", "disc", "disc1", "files")

    fun isGeneric(baseName: String) = baseName.trim().lowercase() in genericFiles

    /** Nearest folder above a generic file that isn't a technical one ([trail]: folders from the platform root down). */
    fun folderName(trail: List<String>): String? = trail.lastOrNull { it.lowercase() !in technicalFolders }

    fun clean(baseName: String): String {
        var name = tags.replace(baseName, "").replace('_', ' ')
        // "Legend of Zelda, The" → "The Legend of Zelda"
        article.matchEntire(name)?.let { m -> name = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}" }
        name = trailingDash.replace(spaces.replace(name, " "), "")
        return name.trim()
    }

    /** Disc number from "(Disc 2)" / "[CD2]", else null. */
    fun disc(fileName: String): Int? = discTag.find(fileName)?.groupValues?.get(2)?.toIntOrNull()

    /** Switch updates and DLC ([UPD]/[DLC] tags, or a title id not ending in 000) are no games of their own. */
    fun isSwitchAddon(fileName: String): Boolean {
        if (switchAddonTag.containsMatchIn(fileName)) return true
        val id = switchTitleId.find(fileName) ?: return false
        return !id.groupValues[1].endsWith("000")
    }
}
