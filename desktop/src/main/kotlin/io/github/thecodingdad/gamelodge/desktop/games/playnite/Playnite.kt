package io.github.thecodingdad.gamelodge.desktop.games.playnite

import io.github.thecodingdad.gamelodge.desktop.games.CustomGame
import io.github.thecodingdad.gamelodge.desktop.emulation.EmulatorPresets
import io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulation
import io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulator
import io.github.thecodingdad.gamelodge.desktop.emulation.PcEmulatorPlatform
import io.github.thecodingdad.gamelodge.desktop.emulation.PcRomFolder
import io.github.thecodingdad.gamelodge.desktop.emulation.RomScanner
import io.github.thecodingdad.gamelodge.util.Log
import java.io.File
import java.util.UUID

data class PlayniteAction(val type: Int, val path: String?, val arguments: String?, val workingDir: String?, val emulatorId: UUID?, val emulatorProfileId: String?, val isPlayAction: Boolean)

data class PlayniteGame(
    val id: UUID, val name: String, val gameId: String?, val pluginId: UUID?, val installed: Boolean, val hidden: Boolean,
    val installDir: String?, val actions: List<PlayniteAction>, val roms: List<String>, val platformIds: List<UUID>, val cover: String?,
)

data class PlayniteProfile(
    val id: String, val name: String, val executable: String?, val arguments: String?, val workingDir: String?, val builtInProfileName: String?,
    val platformIds: List<UUID> = emptyList(), val extensions: List<String> = emptyList(),
)

/** A ROM folder Playnite scans for one emulator profile. */
data class PlayniteScannerConfig(val name: String?, val emulatorId: UUID?, val profileId: String?, val directory: String?, val platformOverride: UUID?)

data class PlayniteEmulator(val id: UUID, val name: String, val installDir: String?, val builtInConfigId: String?, val profiles: List<PlayniteProfile>)

data class PlayniteLibrary(
    val games: List<PlayniteGame>,
    val platforms: Map<UUID, String>,
    val emulators: List<PlayniteEmulator>,
    val scanners: List<PlayniteScannerConfig> = emptyList(),
) {
    companion object {
        /** Reads the library folder (copies first: Playnite keeps the files open). */
        fun read(dir: File): PlayniteLibrary {
            fun docs(name: String): List<Map<String, Any?>> {
                val file = File(dir, name)
                if (!file.isFile) return emptyList()
                return runCatching { LiteDb.readAll(file.readBytes()) }
                    .onFailure { Log.w("Playnite: $name unreadable", it) }
                    .getOrDefault(emptyList())
            }
            val games = docs("games.db").mapNotNull(::game)
            val platforms = docs("platforms.db").mapNotNull { d -> (d["_id"] as? UUID)?.let { id -> (d["Name"] as? String)?.let { id to it } } }.toMap()
            val emulators = docs("emulators.db").mapNotNull(::emulator)
            // Playnite 9 called the file gamescanners.db.
            val scanners = (docs("scanners.db") + docs("gamescanners.db")).map { d ->
                PlayniteScannerConfig(d.str("Name"), d.guid("EmulatorId"), d.str("EmulatorProfileId"), d.str("Directory"), d.guid("PlatformOverride"))
            }
            Log.i("Playnite: ${games.size} games, ${platforms.size} platforms, ${emulators.size} emulators, ${scanners.size} ROM scanners")
            return PlayniteLibrary(games, platforms, emulators, scanners)
        }

        private fun Map<String, Any?>.str(key: String) = this[key] as? String
        private fun Map<String, Any?>.bool(key: String) = this[key] as? Boolean ?: false
        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.docs(key: String) = (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
        private fun Map<String, Any?>.guids(key: String) = (this[key] as? List<*>)?.filterIsInstance<UUID>().orEmpty()
        private fun Map<String, Any?>.guid(key: String) = (this[key] as? UUID)?.takeIf { it != EMPTY }

        private fun game(d: Map<String, Any?>): PlayniteGame? {
            val name = d.str("Name") ?: return null
            val id = d["_id"] as? UUID ?: return null
            val actions = d.docs("GameActions").map { a ->
                PlayniteAction(actionType(a["Type"]), a.str("Path"), a.str("Arguments"), a.str("WorkingDir"), a.guid("EmulatorId"), a.str("EmulatorProfileId"), a.bool("IsPlayAction"))
            }
            return PlayniteGame(
                id, name, d.str("GameId"), d.guid("PluginId"), d.bool("IsInstalled"), d.bool("Hidden"), d.str("InstallDirectory"),
                actions, d.docs("Roms").mapNotNull { it.str("Path") }, d.guids("PlatformIds"), d.str("CoverImage"),
            )
        }

        private fun emulator(d: Map<String, Any?>): PlayniteEmulator? {
            val id = d["_id"] as? UUID ?: return null
            val profiles = (d.docs("CustomProfiles") + d.docs("BuiltinProfiles")).map { p ->
                val args = if (p.bool("OverrideDefaultArgs")) p.str("CustomArguments") else p.str("Arguments")
                PlayniteProfile(
                    p.str("Id") ?: (p["_id"] as? String).orEmpty(), p.str("Name").orEmpty(), p.str("Executable"), args, p.str("WorkingDirectory"),
                    p.str("BuiltInProfileName"), p.guids("Platforms"), (p["ImageExtensions"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                )
            }
            return PlayniteEmulator(id, d.str("Name").orEmpty(), d.str("InstallDir"), d.str("BuiltInConfigId"), profiles)
        }

        private val EMPTY = UUID(0, 0)

        /** GameActionType: LiteDB stores enums by name ("Emulator"), older data as numbers. */
        private fun actionType(v: Any?): Int = when (v) {
            is Number -> v.toInt()
            is String -> when (v.lowercase()) { "file" -> 0; "url" -> 1; "emulator" -> 2; "script" -> 3; else -> -1 }
            else -> 0
        }
    }
}

/** What an import from Playnite brings into the launcher (applied by [PlayniteImport.merge]). */
data class PlayniteImportResult(
    val emulators: List<PcEmulator>,
    val folders: List<PcRomFolder>,
    val defaults: Map<String, String>,
    val games: List<CustomGame>,
    /** Playnite emulators that couldn't be set up (no known program). */
    val skippedEmulators: List<String>,
    /** Games only their Playnite add-on can start. */
    val skippedGames: Int,
)

/**
 * One-time import from Playnite: emulators (program, arguments, consoles), the ROM folders of its
 * scanners and emulated games, and games added there by hand. Everything is copied: paths into
 * the launcher's own settings, cover images into its data folder, so Playnite can be removed.
 * Store games are left to the store scanners.
 */
class PlayniteImport(
    private val data: PlayniteLibrary,
    private val libraryDir: File,
    private val playniteDir: File?,
    /** Where cover images of imported games are copied to. */
    private val coverDir: File,
    private val exists: (String) -> Boolean = { File(it).exists() },
) {
    private val builtIn: Map<Pair<String, String>, Pair<String?, String?>> =
        playniteDir?.let { builtInProfiles(File(it, "Emulation/Emulators")) }.orEmpty()

    fun run(): PlayniteImportResult {
        val platformName = { id: UUID -> data.platforms[id]?.let(::companionPlatform) }
        val emulators = LinkedHashMap<UUID, PcEmulator>()
        val skipped = ArrayList<String>()
        for (e in data.emulators) {
            val converted = convert(e, platformName)
            if (converted != null) emulators[e.id] = converted else skipped += e.name
        }

        val folders = LinkedHashMap<String, PcRomFolder>()
        val counts = HashMap<Pair<String, String>, Int>()
        fun addFolder(path: String?, platform: String?, emulatorId: UUID?) {
            val dir = path?.trim()?.trimEnd('\\', '/')?.takeIf { it.isNotEmpty() } ?: return
            val emu = emulatorId?.let { emulators[it] } ?: return
            val p = platform ?: emu.platforms.singleOrNull()?.platform ?: return
            // A console Playnite assigned that the preset doesn't list: the emulator runs it too.
            if (!emu.supports(p)) emulators[emulatorId] = emu.copy(platforms = emu.platforms + PcEmulatorPlatform(p))
            folders.putIfAbsent(dir.lowercase(), PcRomFolder(dir, p, emu.id))
            counts.merge(p to emu.id, 1, Int::plus)
        }
        data.scanners.forEach { s ->
            val profile = data.emulators.firstOrNull { it.id == s.emulatorId }?.profiles?.firstOrNull { it.id == s.profileId }
            val platform = s.platformOverride?.let(platformName) ?: profile?.platformIds?.firstNotNullOfOrNull(platformName)
            addFolder(s.directory?.let { expand(it, null, null, null) }, platform, s.emulatorId)
        }

        val games = ArrayList<CustomGame>()
        var skippedGames = 0
        for (g in data.games.filter { !it.hidden }) {
            if (g.pluginId != null && g.pluginId in STORE_PLUGINS && !g.gameId.isNullOrEmpty()) continue
            val emu = g.actions.firstOrNull { it.type == 2 && it.isPlayAction } ?: g.actions.firstOrNull { it.type == 2 }
            if (emu != null && g.roms.isNotEmpty()) {
                // The game's folder becomes a ROM folder (most are covered by a scanner already).
                val rom = expand(g.roms.first(), g.installDir, null, null)
                addFolder(WinPathDir(rom), g.platformIds.firstNotNullOfOrNull(platformName), emu.emulatorId)
                continue
            }
            val action = g.actions.firstOrNull { it.isPlayAction && it.type in 0..1 } ?: g.actions.firstOrNull { it.type in 0..1 }
            val path = action?.path
            if (path == null) { skippedGames++; continue }
            val id = g.id.toString()
            games += CustomGame(
                id = id, name = g.name,
                path = if (action.type == 1) path else expand(path, g.installDir, null, null),
                arguments = action.arguments?.let { expand(it, g.installDir, null, null) },
                workingDir = action.workingDir?.let { expand(it, g.installDir, null, null) } ?: g.installDir,
                cover = g.cover?.let { copyCover(it, id) },
            )
        }

        // Each console's default: the emulator most of its folders use.
        val defaults = counts.entries.groupBy({ it.key.first }) { it.key.second to it.value }.mapValues { (_, l) -> l.maxBy { it.second }.first }
        val result = PlayniteImportResult(emulators.values.toList(), folders.values.toList(), defaults, games, skipped, skippedGames)
        Log.i("Playnite import: ${result.emulators.size} emulators, ${result.folders.size} ROM folders, ${games.size} own games; skipped ${skipped.size} emulators, $skippedGames games")
        return result
    }

    private fun convert(e: PlayniteEmulator, platformName: (UUID) -> String?): PcEmulator? {
        val preset = e.builtInConfigId?.let(EmulatorPresets::forPlaynite)
        // The profile most of its games use (else the first).
        val used = data.games.flatMap { it.actions }.filter { it.emulatorId == e.id }.groupingBy { it.emulatorProfileId }.eachCount()
        val profile = e.profiles.maxByOrNull { used[it.id] ?: 0 } ?: e.profiles.firstOrNull()
        val def = profile?.builtInProfileName?.let { name -> e.builtInConfigId?.let { builtIn[it to name] } }
        val installDir = e.installDir?.trimEnd('\\', '/')
        val exe = profile?.executable?.let { expand(it, installDir, installDir, null) }
            ?: def?.second?.let { regex -> installDir?.let { findExe(File(it), regex) } }
            ?: preset?.let { p -> installDir?.let { dir -> p.executables.map { "$dir\\$it" }.firstOrNull(exists) ?: "$dir\\${p.executables.first()}" } }
            ?: return null
        val args = profile?.arguments ?: def?.first ?: preset?.arguments ?: "\"{ImagePath}\""
        val platforms = preset?.platforms?.map { PcEmulatorPlatform(it.platform, emptyList(), it.core) }
            ?: profile?.platformIds?.mapNotNull(platformName)?.distinct()?.map { PcEmulatorPlatform(it, profile.extensions) }.orEmpty()
        return PcEmulator(
            id = UUID.nameUUIDFromBytes("playnite:${e.id}".toByteArray()).toString(), name = e.name, executable = exe,
            arguments = args.replace("{EmulatorDir}", "{emuDir}"), platforms = platforms, presetId = preset?.id,
            workingDir = profile?.workingDir?.let { expand(it, installDir, installDir, null) }, folderGameFile = preset?.folderGameFile,
        )
    }

    private fun copyCover(relative: String, id: String): String? {
        val source = File(coverPath(relative)).takeIf { it.isFile } ?: return null
        return runCatching {
            coverDir.mkdirs()
            val target = File(coverDir, "playnite-$id.${source.extension.ifEmpty { "jpg" }}")
            source.copyTo(target, overwrite = true)
            target.path
        }.getOrNull()
    }

    private fun coverPath(relative: String): String =
        if (File(relative).isAbsolute || relative.matches(Regex("^[A-Za-z]:\\\\.*"))) relative else File(File(libraryDir, "files"), relative.replace('\\', File.separatorChar)).path

    /** Playnite's placeholders in paths; ROM placeholders stay for the emulator's arguments. */
    fun expand(value: String, installDir: String?, emulatorDir: String?, rom: String?): String {
        var s = value
            .replace("{InstallDir}", installDir.orEmpty())
            .replace("{EmulatorDir}", emulatorDir ?: installDir.orEmpty())
            .replace("{PlayniteDir}", playniteDir?.path.orEmpty())
        if (rom != null) s = RomScanner.expand(s, rom, emulatorDir.orEmpty(), null)
        return s
    }

    private fun WinPathDir(path: String) = io.github.thecodingdad.gamelodge.desktop.games.WinPath.directory(path)

    private fun findExe(dir: File, regex: String): String? {
        if (!dir.isDirectory) return null
        val re = Regex(regex, RegexOption.IGNORE_CASE)
        return dir.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.extension.equals("exe", true) && re.containsMatchIn(it.name) }?.path
    }

    companion object {
        /** Library folder and installation: portable (library next to the program) or %APPDATA%\Playnite. */
        fun locate(): Pair<File?, File?> {
            val local = System.getenv("LOCALAPPDATA")?.let { File(it, "Playnite") }
            val programs = System.getenv("ProgramFiles")?.let { File(it, "Playnite") }
            val install = listOfNotNull(local, programs).firstOrNull { File(it, "Playnite.DesktopApp.exe").isFile }
            val roaming = System.getenv("APPDATA")?.let { File(it, "Playnite/library") }
            val library = listOfNotNull(install?.let { File(it, "library") }, roaming).firstOrNull { File(it, "games.db").isFile }
            return library to install
        }

        /** Takes [result] into the launcher's own setup: known emulators, folders and games are kept, new ones added. */
        fun merge(emulation: PcEmulation, games: List<CustomGame>, result: PlayniteImportResult): Pair<PcEmulation, List<CustomGame>> {
            val emulators = emulation.emulators.associateBy { it.id }.toMutableMap()
            result.emulators.forEach { emulators.putIfAbsent(it.id, it) }
            val folders = emulation.folders.toMutableList()
            result.folders.forEach { f -> if (folders.none { it.path.equals(f.path, ignoreCase = true) }) folders += f }
            val defaults = result.defaults + emulation.defaults
            val known = games.map { it.id }.toSet()
            return PcEmulation(folders, emulators.values.toList(), defaults) to (games + result.games.filter { it.id !in known })
        }

        /** Playnite's library plugins of the stores the launcher scans itself. */
        val STORE_PLUGINS: Set<UUID> = listOf(
            "cb91dfc9-b977-43bf-8e70-55f46e410fab", "00000002-dbd1-46c6-b5d0-b1ba559d10e4", "aebe8b7c-6dc3-4a66-af31-e7375c6b5e9e",
            "7e4fbb5e-2ae3-48d4-8ba0-6b30e7a4e287", "85dd7072-2f20-4e76-a007-41035e390724", "c2f038e5-8b92-4877-91f1-da9094155fc5",
            "e3c26a3d-d695-4cb7-a769-5ff7612c7edd", "402674cd-4af6-4886-b6ec-0e695bfa0688",
        ).map(UUID::fromString).toSet()

        private val platformNames = mapOf(
            "sony playstation" to "PlayStation", "sony playstation 2" to "PlayStation 2", "sony playstation 3" to "PlayStation 3",
            "sony playstation 4" to "PlayStation 4", "sony playstation 5" to "PlayStation 5", "sony psp" to "PlayStation Portable",
            "sony playstation portable" to "PlayStation Portable", "sony ps vita" to "PlayStation Vita", "sony playstation vita" to "PlayStation Vita",
            "nintendo wii" to "Wii", "nintendo wii u" to "Wii U", "nintendo super nintendo entertainment system" to "Super Nintendo Entertainment System",
            "nintendo snes" to "Super Nintendo Entertainment System", "nintendo nes" to "Nintendo Entertainment System",
            "nintendo game boy" to "Game Boy", "nintendo game boy color" to "Game Boy Color", "nintendo game boy advance" to "Game Boy Advance",
            "nintendo gamecube" to "Nintendo GameCube", "nintendo 64" to "Nintendo 64", "nintendo ds" to "Nintendo DS", "nintendo 3ds" to "Nintendo 3DS",
            "nintendo switch" to "Nintendo Switch",
            "microsoft xbox" to "Xbox", "microsoft xbox 360" to "Xbox 360", "microsoft xbox one" to "Xbox One",
            "sega genesis" to "Sega Mega Drive/Genesis", "sega mega drive" to "Sega Mega Drive/Genesis", "sega dreamcast" to "Dreamcast",
            "sega saturn" to "Sega Saturn", "sega master system" to "Sega Master System",
            "pc (windows)" to "PC", "pc (dos)" to "DOS", "windows" to "PC",
        )

        /** Playnite's platform names in IGDB's naming (as the companion does). */
        fun companionPlatform(name: String): String = platformNames[name.trim().lowercase()] ?: name.trim()

        /**
         * Playnite's built-in emulator definitions (emulator.yaml): profile → (arguments, executable
         * pattern). Only the few keys needed are read, line by line.
         */
        fun builtInProfiles(dir: File): Map<Pair<String, String>, Pair<String?, String?>> {
            if (!dir.isDirectory) return emptyMap()
            val result = HashMap<Pair<String, String>, Pair<String?, String?>>()
            dir.walkTopDown().filter { it.isFile && it.name == "emulator.yaml" }.forEach { file ->
                runCatching { result.putAll(parseEmulatorYaml(file.readText())) }.onFailure { Log.w("Playnite: ${file.path} unreadable", it) }
            }
            return result
        }

        internal fun parseEmulatorYaml(text: String): Map<Pair<String, String>, Pair<String?, String?>> {
            fun value(line: String) = line.substringAfter(':').trim().removeSurrounding("'").removeSurrounding("\"").ifEmpty { null }
            var id: String? = null
            val profiles = ArrayList<MutableMap<String, String?>>()
            var inProfiles = false
            for (raw in text.lines()) {
                val line = raw.trimEnd()
                if (line.isBlank() || line.trimStart().startsWith("#")) continue
                val indent = line.length - line.trimStart().length
                val t = line.trim()
                if (indent == 0) {
                    inProfiles = t.startsWith("Profiles:")
                    if (t.startsWith("Id:")) id = value(t)
                    continue
                }
                if (!inProfiles) continue
                val entry = if (t.startsWith("- ")) { profiles.add(HashMap()); t.removePrefix("- ").trim() } else t
                val current = profiles.lastOrNull() ?: continue
                when {
                    entry.startsWith("Name:") -> current["Name"] = value(entry)
                    entry.startsWith("StartupArguments:") -> current["StartupArguments"] = value(entry)
                    entry.startsWith("StartupExecutable:") -> current["StartupExecutable"] = value(entry)
                }
            }
            val config = id ?: return emptyMap()
            return profiles.mapNotNull { p -> p["Name"]?.let { (config to it) to (p["StartupArguments"] to p["StartupExecutable"]) } }.toMap()
        }
    }
}
