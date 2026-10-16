package io.github.thecodingdad.moonlightlauncher.desktop.games

/** One game (or app) found by a scanner, installed or only owned. */
data class GameEntry(
    /** Stable id across scans: "steam:1086940", "epic:<ns>:<item>", "rom:<platform>:<name>". */
    val id: String,
    val name: String,
    /** steam, epic, gog, xbox, ea, ubisoft, battlenet, amazon, emulator. */
    val source: String,
    val storeId: String? = null,
    val game: Boolean = true,
    /** "PC" for store games, the console for emulated ones ("Nintendo GameCube"). */
    val platform: String = "PC",
    val installed: Boolean = false,
    val installDir: String? = null,
    /** Store protocol URI or executable that starts the game. */
    val launch: String? = null,
    val launchArgs: String? = null,
    val workingDir: String? = null,
    /** Store protocol URI that starts the installation (not installed games). */
    val install: String? = null,
    /** Folder the game's processes run from; defaults to [installDir]. */
    val processDir: String? = null,
    /** Executable names of the game (without path), when known. */
    val processNames: List<String> = emptyList(),
    /** Cover candidates (URLs or local files), best first. */
    val covers: List<String> = emptyList(),
    val storeHeroes: List<String> = emptyList(),
    val storeLogos: List<String> = emptyList(),
    val storeIcons: List<String> = emptyList(),
    /** Short description from the store's local data (Epic). */
    val storeSummary: String? = null,
)

/** A store's part of the PC library. */
interface GameScanner {
    /** Short name for logs and settings ("steam", "epic" …). */
    val source: String

    /** Installed games plus, where the store's local data allows it, owned but not installed ones. */
    suspend fun scan(): List<GameEntry>
}
