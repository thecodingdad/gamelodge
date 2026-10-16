package io.github.thecodingdad.moonlightlauncher.desktop.games

import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EA app: installed games from the uninstall entries; the offer ids for a proper launch through
 * the EA app come from the game's __Installer\installerdata.xml.
 */
class EaScanner : GameScanner {
    override val source = "ea"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        Reg.uninstallEntries().mapNotNull { (key, get) ->
            val publisher = get("Publisher").orEmpty()
            val uninstall = get("UninstallString").orEmpty()
            val dir = get("InstallLocation")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val installer = File(dir, "__Installer/installerdata.xml")
            val isEa = uninstall.contains("EA Desktop", true) || uninstall.contains("EAInstaller", true) ||
                (publisher.contains("Electronic Arts", true) && installer.exists())
            if (!isEa || !File(dir).isDirectory) return@mapNotNull null
            val name = get("DisplayName")?.takeIf { it.isNotBlank() && !it.equals("EA app", true) } ?: return@mapNotNull null
            create(key, name, dir, if (installer.exists()) offerIds(installer.readText()) else emptyList(), exeFromIcon(get("DisplayIcon")))
        }
    }

    companion object {
        /** contentIDs of installerdata.xml ("Origin.OFR.50.0002694", …). */
        fun offerIds(xml: String): List<String> = runCatching {
            val doc = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                .newDocumentBuilder().parse(xml.byteInputStream())
            val nodes = doc.getElementsByTagName("contentID")
            (0 until nodes.length).map { nodes.item(it).textContent.trim() }.filter { it.isNotEmpty() }.distinct()
        }.getOrDefault(emptyList())

        fun exeFromIcon(icon: String?): String? {
            if (icon.isNullOrBlank()) return null
            val path = icon.split(',')[0].trim().trim('"')
            return path.takeIf { it.endsWith(".exe", ignoreCase = true) }
        }

        fun create(key: String, name: String, dir: String, offers: List<String>, exe: String?) = GameEntry(
            id = if (offers.isNotEmpty()) "ea:${offers[0]}" else "ea:$key",
            name = name, source = "ea", storeId = offers.firstOrNull(), installed = true, installDir = dir,
            // The EA app handles login, DRM and updates; the EXE is the fallback.
            launch = if (offers.isNotEmpty()) "origin2://game/launch?offerIds=${offers.joinToString(",")}&autoDownload=1" else exe,
            processNames = listOfNotNull(exe?.let(WinPath::fileName)),
        )
    }
}

/** Ubisoft Connect: installs from Ubisoft\Launcher\Installs, names from the uninstall entries. */
class UbisoftScanner : GameScanner {
    override val source = "ubisoft"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val names = Reg.uninstallEntries().filter { it.first.startsWith("Uplay Install ", true) }
            .associate { it.first.substring("Uplay Install ".length).lowercase() to it.second("DisplayName") }
        Reg.subKeys("HKLM", "SOFTWARE\\WOW6432Node\\Ubisoft\\Launcher\\Installs").mapNotNull { (id, get) ->
            val dir = get("InstallDir")?.takeIf { it.isNotEmpty() && File(it).isDirectory } ?: return@mapNotNull null
            create(id, names[id.lowercase()], dir)
        }
    }

    companion object {
        fun create(id: String, name: String?, dir: String) = GameEntry(
            id = "ubisoft:$id", name = name?.takeIf { it.isNotBlank() } ?: WinPath.fileName(dir), source = "ubisoft", storeId = id,
            installed = true, installDir = dir, launch = "uplay://launch/$id/0", install = "uplay://install/$id",
        )
    }
}

/**
 * Battle.net: installed games from the uninstall entries ("--uid=<uid>"), started through the
 * Battle.net client with the game's product code.
 */
class BattleNetScanner : GameScanner {
    override val source = "battlenet"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val client = listOfNotNull(
            Reg.read("HKLM", "SOFTWARE\\WOW6432Node\\Blizzard Entertainment\\Battle.net\\Capabilities", "ApplicationIcon"),
            "C:\\Program Files (x86)\\Battle.net\\Battle.net.exe",
        ).mapNotNull(EaScanner::exeFromIcon).firstOrNull { File(it).exists() }
        Reg.uninstallEntries().mapNotNull { (key, get) ->
            val uninstall = get("UninstallString").orEmpty()
            if (!uninstall.contains("Battle.net", true)) return@mapNotNull null
            val uid = uid(uninstall) ?: return@mapNotNull null
            create(uid, get("DisplayName") ?: key, get("InstallLocation"), client)
        }.also { Log.d("Battle.net: ${it.size} installed games (client: ${client ?: "not found"})") }
    }

    companion object {
        /** Battle.net uid (uninstall string) → product code for "--exec=launch <code>". */
        val productCodes = mapOf(
            "prometheus" to "Pro", "diablo3" to "D3", "fenris" to "Fen", "osi" to "OSI", "hs_beta" to "WTCG",
            "heroes" to "Hero", "s2" to "S2", "s1" to "S1", "wow" to "WoW", "wow_classic" to "WoWC", "w3" to "W3",
            "odin" to "ODIN", "lazarus" to "LAZR", "viper" to "VIPR", "zeus" to "ZEUS", "fore" to "FORE", "auks" to "AUKS",
            "rtro" to "RTRO", "anbs" to "ANBS", "wlby" to "WLBY", "gryphon" to "GRY",
        )

        fun uid(uninstallString: String): String? {
            val i = uninstallString.indexOf("--uid=", ignoreCase = true)
            if (i < 0) return null
            val rest = uninstallString.substring(i + 6)
            val end = rest.indexOfFirst { it == ' ' || it == '"' }
            return if (end < 0) rest else rest.substring(0, end)
        }

        fun create(uid: String, name: String, dir: String?, client: String?): GameEntry {
            val code = productCodes[uid.lowercase()]
            return GameEntry(
                id = "battlenet:$uid", name = name, source = "battlenet", storeId = code ?: uid, installed = true, installDir = dir,
                launch = if (client != null && code != null) client else code?.let { "battlenet://$it" },
                launchArgs = if (client != null && code != null) "--exec=\"launch $code\"" else null,
            )
        }
    }
}

/** Amazon Games: installed games from the app's GameInstallInfo database. */
class AmazonScanner(private val dataDir: File = defaultDataDir()) : GameScanner {
    override val source = "amazon"

    override suspend fun scan(): List<GameEntry> = withContext(Dispatchers.IO) {
        val db = File(dataDir, "GameInstallInfo.sqlite")
        if (!db.exists()) return@withContext emptyList()
        runCatching { readInstalled(db) }.onFailure { Log.w("Amazon: install database unreadable", it) }.getOrDefault(emptyList())
    }

    companion object {
        fun defaultDataDir() = File(System.getenv("LOCALAPPDATA") ?: "", "Amazon Games/Data/Games/Sql")

        fun readInstalled(db: File): List<GameEntry> = readSqliteCopy(db) { c ->
            c.rows("SELECT Id, ProductTitle, InstallDirectory FROM DbSet WHERE Installed = 1").mapNotNull { row ->
                val id = row[0] ?: return@mapNotNull null
                GameEntry(
                    id = "amazon:$id", name = row[1] ?: id, source = "amazon", storeId = id, installed = true,
                    installDir = row[2], launch = "amazon-games://play/$id",
                )
            }
        }
    }
}
