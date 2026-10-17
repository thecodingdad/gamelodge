package io.github.thecodingdad.gamelodge.desktop.games

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/** Ported from the companion's ScannerTests / StoreScannerTests / RunnerTests. */
class GameScannerTest {
    @Test
    fun vdfParsesNestedLibraryFolders() {
        val vdf = VdfNode.parse(
            """
            "libraryfolders"
            {
                "0" { "path" "C:\\Program Files (x86)\\Steam" "apps" { "228980" "1" } }
                // comment
                "1" { "path" "D:\\SteamLibrary" }
            }
            """,
        )
        assertEquals("D:\\SteamLibrary", vdf["libraryfolders"]!!["1"]!!.value("path"))
        assertEquals("1", vdf["LIBRARYFOLDERS"]!!["0"]!!["apps"]!!.value("228980"))
    }

    @Test
    fun steamManifestGivesLaunchAndInstallState() {
        val game = SteamScanner.parseManifest(""""AppState" { "appid" "1086940" "name" "Baldur's Gate 3" "StateFlags" "4" "installdir" "Baldurs Gate 3" }""", "/lib/steamapps")!!
        assertEquals("steam:1086940", game.id)
        assertTrue(game.installed)
        assertEquals("steam://rungameid/1086940", game.launch)
        assertTrue(game.installDir!!.endsWith(File("common", "Baldurs Gate 3").path))
        assertFalse(SteamScanner.parseManifest(""""AppState" { "appid" "570" "name" "Dota 2" "StateFlags" "1026" }""", "/lib/steamapps")!!.installed)
    }

    @Test
    fun steamToolsAreSkipped() {
        assertNull(SteamScanner.parseManifest(""""AppState" { "appid" "228980" "name" "Steamworks Common Redistributables" "StateFlags" "4" }""", "/x"))
        assertNull(SteamScanner.parseManifest(""""AppState" { "appid" "999" "name" "Proton 9.0" "StateFlags" "4" }""", "/x"))
    }

    @Test
    fun steamIdComesFromMostRecentLogin() {
        val root = Files.createTempDirectory("steam").toFile()
        File(root, "config").mkdirs()
        File(root, "config/loginusers.vdf").writeText(
            """
            "users"
            {
                "76561198000000001" { "AccountName" "old" "MostRecent" "0" }
                "76561198000000002" { "AccountName" "me" "MostRecent" "1" }
            }
            """,
        )
        assertEquals("76561198000000002", SteamScanner.detectSteamId(root.path))
        root.deleteRecursively()
    }

    @Test
    fun steamPublicProfileGames() {
        val games = SteamScanner.parseProfileGames("""<?xml version="1.0"?><gamesList><games><game><appID>620</appID><name><![CDATA[Portal 2]]></name></game></games></gamesList>""")
        assertEquals(listOf("steam:620"), games.map { it.id })
        assertEquals("Portal 2", games.single().name)
        assertFalse(games.single().installed)
    }

    @Test
    fun epicManifestAndDlcFilter() {
        val game = EpicScanner.parseManifest(
            """{ "DisplayName": "Hades", "CatalogNamespace": "min", "CatalogItemId": "abc", "AppName": "Min",
              "InstallLocation": "C:\\Games\\Hades", "AppCategories": ["public", "games", "applications"] }""",
        )!!
        assertEquals("epic:min:abc", game.id)
        assertTrue("action=launch" in game.launch!!)
        assertTrue("apps/min%3Aabc%3AMin" in game.launch!!)
        assertNull(EpicScanner.parseManifest("""{ "DisplayName": "Hades Soundtrack", "CatalogNamespace": "min", "CatalogItemId": "dlc", "AppName": "MinDlc", "AppCategories": ["addons"] }"""))
    }

    @Test
    fun epicCatalogCacheListsOwnedGamesWithoutDlc() {
        val json = """
            [
              { "id": "g1", "namespace": "ns1", "title": "Control", "categories": [{"path":"games"}], "releaseInfo": [{"appId": "Calluna"}], "mainGameItem": {"namespace":"","id":""},
                "keyImages": [ {"type":"DieselGameBox","url":"https://x/wide"}, {"type":"DieselGameBoxTall","url":"https://x/tall"} ] },
              { "id": "d1", "namespace": "ns1", "title": "Control DLC", "categories": [{"path":"games"},{"path":"addons"}], "mainGameItem": {"id":"g1"}, "releaseInfo": [{"appId": "CallunaDlc"}] },
              { "id": "a1", "namespace": "ns2", "title": "Some Tool", "categories": [{"path":"applications"}], "releaseInfo": [{"appId": "Tool"}] }
            ]
        """
        val list = EpicScanner.parseCatalogCache(Base64.getEncoder().encodeToString(json.toByteArray()))
        val single = list.single()
        assertEquals("Control", single.name)
        assertFalse(single.installed)
        assertTrue("action=install" in single.install!!)
        assertEquals(listOf("https://x/tall"), single.covers)
    }

    private fun sqlite(file: File, sql: String) {
        BundledSQLiteDriver().open(file.path).use { c -> sql.split(';').filter { it.isNotBlank() }.forEach { c.execSQL(it) } }
    }

    @Test
    fun gogGalaxyDatabaseListsOwnedGames() {
        val db = File.createTempFile("galaxy", ".db")
        sqlite(
            db,
            """
            CREATE TABLE LibraryReleaseKeys (releaseKey TEXT);
            CREATE TABLE GamePieceTypes (id INTEGER, type TEXT);
            CREATE TABLE GamePieces (releaseKey TEXT, gamePieceTypeId INTEGER, value TEXT);
            INSERT INTO LibraryReleaseKeys VALUES ('gog_1207658924'), ('steam_570');
            INSERT INTO GamePieceTypes VALUES (1, 'originalTitle'), (2, 'meta'), (3, 'originalImages');
            INSERT INTO GamePieces VALUES ('gog_1207658924', 1, '{"title":"The Witcher 3"}'), ('gog_1207658924', 2, '{}'),
              ('gog_1207658924', 3, '{"verticalCover":"https://images.gog.com/cover.jpg","background":"https://x/{formatter}.jpg"}')
            """,
        )
        val game = GogScanner.readGalaxyOwned(db).single()
        assertEquals("gog:1207658924", game.id)
        assertEquals("The Witcher 3", game.name)
        assertEquals("goggalaxy://openGameView/1207658924", game.install)
        assertEquals(listOf("https://images.gog.com/cover.jpg"), game.covers)
        assertTrue(game.storeHeroes.isEmpty())
        db.delete()
    }

    @Test
    fun amazonInstalledGames() {
        val db = File.createTempFile("amazon", ".sqlite")
        sqlite(
            db,
            """
            CREATE TABLE DbSet (Id TEXT, ProductTitle TEXT, InstallDirectory TEXT, Installed INTEGER);
            INSERT INTO DbSet VALUES ('amzn1.adg.product.1', 'Fallout 3', 'C:\Amazon Games\Library\Fallout 3', 1), ('amzn1.adg.product.2', 'Old', 'C:\x', 0)
            """,
        )
        val game = AmazonScanner.readInstalled(db).single()
        assertEquals("amazon:amzn1.adg.product.1", game.id)
        assertEquals("amazon-games://play/amzn1.adg.product.1", game.launch)
        db.delete()
    }

    @Test
    fun xboxGameConfigAndManifest() {
        val game = XboxScanner.parseGameConfig(
            """
            <Game configVersion="1">
              <Identity Name="Microsoft.Forza" Publisher="CN=x" Version="1.0.0.0" />
              <StoreId>9NKX70BBCDRN</StoreId>
              <ExecutableList><Executable Name="ForzaHorizon5.exe" Id="Game" /><Executable Name="bin\Helper.exe" /></ExecutableList>
              <ShellVisuals DefaultDisplayName="Forza Horizon 5" PublisherDisplayName="Xbox Game Studios" />
            </Game>
            """,
        )
        assertEquals("Forza Horizon 5", game.displayName)
        assertEquals("9NKX70BBCDRN", game.storeId)
        assertEquals(listOf("ForzaHorizon5.exe", "Helper.exe"), game.executables)
        assertNull(XboxScanner.parseGameConfig("""<Game><ShellVisuals DefaultDisplayName="ms-resource:AppName" /></Game>""").displayName)
        assertEquals(
            "Game",
            XboxScanner.parseManifestAppId(
                """<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"><Applications><Application Id="Game" Executable="gamelaunchhelper.exe" /></Applications></Package>""",
            ),
        )
    }

    @Test
    fun eaOfferIdsAndLaunch() {
        val offers = EaScanner.offerIds("""<DiPManifest><contentIDs><contentID>Origin.OFR.50.0002694</contentID><contentID>71067</contentID></contentIDs></DiPManifest>""")
        assertEquals(listOf("Origin.OFR.50.0002694", "71067"), offers)
        val game = EaScanner.create("{guid}", "Mass Effect", "C:\\Games\\ME", offers, "C:\\Games\\ME\\ME.exe")
        assertEquals("ea:Origin.OFR.50.0002694", game.id)
        assertEquals("origin2://game/launch?offerIds=Origin.OFR.50.0002694,71067&autoDownload=1", game.launch)
        assertEquals(listOf("ME.exe"), game.processNames)
        assertEquals("C:\\x\\old.exe", EaScanner.create("{guid}", "Old", "C:\\x", emptyList(), "C:\\x\\old.exe").launch)
        assertEquals("C:\\x\\a.exe", EaScanner.exeFromIcon("\"C:\\x\\a.exe\",0"))
    }

    @Test
    fun battleNetUidAndProductCode() {
        assertEquals("prometheus", BattleNetScanner.uid("\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --lang=deDE --uid=prometheus --displayname=\"Overwatch\""))
        val game = BattleNetScanner.create("prometheus", "Overwatch", "C:\\Games\\Overwatch", "C:\\BN\\Battle.net.exe")
        assertEquals("C:\\BN\\Battle.net.exe", game.launch)
        assertEquals("--exec=\"launch Pro\"", game.launchArgs)
        assertNull(BattleNetScanner.create("mystery", "X", null, "C:\\BN\\Battle.net.exe").launch)
    }

    @Test
    fun ubisoftNameFallsBackToFolder() {
        val game = UbisoftScanner.create("4311", null, "C:\\Games\\Assassin's Creed Origins\\")
        assertEquals("Assassin's Creed Origins", game.name)
        assertEquals("uplay://launch/4311/0", game.launch)
    }

    private val steam = ProcessInfo(1, "steam.exe", "C:\\Program Files (x86)\\Steam\\steam.exe")
    private val game = ProcessInfo(2, "Game.exe", "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Game\\bin\\Game.exe")
    private val crash = ProcessInfo(3, "UnityCrashHandler64.exe", "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Game\\UnityCrashHandler64.exe")
    private val gameDir = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Game"

    @Test
    fun matcherUsesFolderAndNamesButNotLaunchersOrHelpers() {
        val m = GameMatcher(gameDir, listOf("Other.exe"))
        assertTrue(m.matches(game))
        assertFalse(m.matches(steam))
        assertFalse(m.matches(crash))
        assertTrue(m.matches(ProcessInfo(4, "other.EXE", null)))
        assertFalse(GameMatcher("C:\\Games\\Game", emptyList()).matches(ProcessInfo(5, "x.exe", "C:\\Games\\GameOfLife\\x.exe")))
    }

    private fun scripted(vararg frames: List<ProcessInfo>): ProcessSource {
        var i = 0
        return ProcessSource { frames[minOf(i++, frames.size - 1)] }
    }

    @Test
    fun waitsForStartThenForExitWithGrace() = runBlocking {
        // launcher only → game appears → flickers off once (restart) → back → gone for good
        val source = scripted(listOf(steam), listOf(steam), listOf(steam, game), listOf(steam, game), emptyList(), listOf(game), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val watcher = ProcessWatcher(source, GameMatcher(gameDir, emptyList()), pollMs = 1_000, graceMs = 3_000, wait = {})
        assertTrue(watcher.waitForStart(10_000))
        watcher.waitForExit()
        assertTrue(watcher.running().isEmpty())
    }

    @Test
    fun startTimesOutWhenTheGameNeverAppears() = runBlocking {
        val watcher = ProcessWatcher(scripted(listOf(steam)), GameMatcher("C:\\Games\\X", emptyList()), pollMs = 1_000, wait = {})
        assertFalse(watcher.waitForStart(5_000))
    }
}
