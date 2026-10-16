package io.github.thecodingdad.moonlightlauncher.desktop.games.playnite

import io.github.thecodingdad.moonlightlauncher.data.library.PlatformResolver
import io.github.thecodingdad.moonlightlauncher.desktop.games.GameEntry
import io.github.thecodingdad.moonlightlauncher.desktop.games.GameScanner
import io.github.thecodingdad.moonlightlauncher.desktop.games.WinPath
import io.github.thecodingdad.moonlightlauncher.util.Log
import java.io.File
import java.util.UUID

data class PlayniteAction(val type: Int, val path: String?, val arguments: String?, val workingDir: String?, val emulatorId: UUID?, val emulatorProfileId: String?, val isPlayAction: Boolean)

data class PlayniteGame(
    val id: UUID, val name: String, val gameId: String?, val pluginId: UUID?, val installed: Boolean, val hidden: Boolean,
    val installDir: String?, val actions: List<PlayniteAction>, val roms: List<String>, val platformIds: List<UUID>, val cover: String?,
)

data class PlayniteProfile(val id: String, val name: String, val executable: String?, val arguments: String?, val workingDir: String?, val builtInProfileName: String?)

data class PlayniteEmulator(val id: UUID, val name: String, val installDir: String?, val builtInConfigId: String?, val profiles: List<PlayniteProfile>)

data class PlayniteLibrary(val games: List<PlayniteGame>, val platforms: Map<UUID, String>, val emulators: List<PlayniteEmulator>) {
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
            Log.i("Playnite: ${games.size} games, ${platforms.size} platforms, ${emulators.size} emulators")
            return PlayniteLibrary(games, platforms, emulators)
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
                PlayniteProfile(p.str("Id").orEmpty(), p.str("Name").orEmpty(), p.str("Executable"), args, p.str("WorkingDirectory"), p.str("BuiltInProfileName"))
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

/**
 * Games from Playnite that the store scanners don't find: emulated games (started with the
 * emulator Playnite has set up) and games added by hand (their play action). Store games come
 * from the store scanners themselves. Playnite is only the data source: games start directly.
 */
class PlayniteScanner(private val locate: () -> Pair<File?, File?> = ::locatePlaynite) : GameScanner {
    override val source = "playnite"

    override suspend fun scan(): List<GameEntry> {
        val (library, install) = locate()
        if (library == null) return emptyList()
        return PlayniteImporter(PlayniteLibrary.read(library), library, install).games()
    }

    companion object {
        /** Library folder and installation: portable (library next to the program) or %APPDATA%\Playnite. */
        fun locatePlaynite(): Pair<File?, File?> {
            val local = System.getenv("LOCALAPPDATA")?.let { File(it, "Playnite") }
            val programs = System.getenv("ProgramFiles")?.let { File(it, "Playnite") }
            val install = listOfNotNull(local, programs).firstOrNull { File(it, "Playnite.DesktopApp.exe").isFile }
            val roaming = System.getenv("APPDATA")?.let { File(it, "Playnite/library") }
            val library = listOfNotNull(install?.let { File(it, "library") }, roaming).firstOrNull { File(it, "games.db").isFile }
            return library to install
        }

        fun installed(): Boolean = locatePlaynite().first != null
    }
}

class PlayniteImporter(
    private val data: PlayniteLibrary,
    private val libraryDir: File,
    private val playniteDir: File?,
    private val exists: (String) -> Boolean = { File(it).exists() },
) {
    private val builtIn: Map<Pair<String, String>, Pair<String?, String?>> =
        playniteDir?.let { builtInProfiles(File(it, "Emulation/Emulators")) }.orEmpty()

    fun games(): List<GameEntry> {
        val list = data.games.filter { !it.hidden }.mapNotNull { g ->
            runCatching { convert(g) }.onFailure { Log.w("Playnite: ${g.name} not importable", it) }.getOrNull()
        }
        Log.i("Playnite: ${list.size} games imported (${list.count { it.source == "emulator" }} emulated)")
        return list
    }

    /** Playnite's platform in the companion's naming (ids stay the same as the companion's). */
    private fun platformOf(g: PlayniteGame): String? = g.platformIds.firstNotNullOfOrNull { data.platforms[it] }?.let(::companionPlatform)

    private fun convert(g: PlayniteGame): GameEntry? {
        val covers = listOfNotNull(g.cover?.let(::coverPath)?.takeIf(exists))
        // Store games: found by the store scanners.
        if (g.pluginId != null && g.pluginId in STORE_PLUGINS && !g.gameId.isNullOrEmpty()) return null

        val emu = g.actions.firstOrNull { it.type == 2 && it.isPlayAction } ?: g.actions.firstOrNull { it.type == 2 }
        if (emu != null && g.roms.isNotEmpty()) {
            // ROM paths may be relative to the game's folder ("{InstallDir}\game.nsp").
            val rom = expand(g.roms.first(), g.installDir, null, null)
            val resolved = resolveEmulator(emu.emulatorId, emu.emulatorProfileId, rom)
            if (resolved == null) { Log.w("Playnite: ${g.name}: emulator profile not resolvable"); return null }
            val platform = platformOf(g) ?: "Unknown"
            return GameEntry(
                id = "rom:${slug(platform)}:${slug(g.name)}", name = g.name, source = "emulator", platform = PlatformResolver.normalizeIgdb(platform),
                installed = exists(rom), launch = resolved.exe, launchArgs = resolved.args, workingDir = resolved.workDir,
                processDir = File(resolved.exe).parent, processNames = listOf(WinPath.fileName(resolved.exe)), covers = covers,
            )
        }

        val action = g.actions.firstOrNull { it.isPlayAction && it.type in 0..1 } ?: g.actions.firstOrNull { it.type in 0..1 }
        val path = action?.path ?: return null
        return GameEntry(
            id = "playnite:${g.id}", name = g.name, source = "playnite", platform = platformOf(g)?.let(PlatformResolver::normalizeIgdb) ?: PlatformResolver.PC,
            installed = g.installed || action.type == 1, installDir = g.installDir,
            launch = if (action.type == 1) path else expand(path, g.installDir, null, null),
            launchArgs = action.arguments?.let { expand(it, g.installDir, null, null) },
            workingDir = action.workingDir?.let { expand(it, g.installDir, null, null) } ?: g.installDir,
            covers = covers,
        )
    }

    private fun coverPath(relative: String): String =
        if (File(relative).isAbsolute || relative.matches(Regex("^[A-Za-z]:\\\\.*"))) relative else File(File(libraryDir, "files"), relative.replace('\\', File.separatorChar)).path

    data class Resolved(val exe: String, val args: String, val workDir: String?, val name: String)

    /** Executable, arguments and working folder of an emulator profile for one ROM. */
    fun resolveEmulator(emulatorId: UUID?, profileId: String?, rom: String): Resolved? {
        val emulator = data.emulators.firstOrNull { it.id == emulatorId } ?: return null
        val profile = emulator.profiles.firstOrNull { it.id == profileId } ?: emulator.profiles.firstOrNull() ?: return null
        var exe = profile.executable?.let { expand(it, emulator.installDir, emulator.installDir, rom) }
        var args = profile.arguments
        val config = emulator.builtInConfigId
        if (profile.builtInProfileName != null && config != null) {
            builtIn[config to profile.builtInProfileName]?.let { (defArgs, exeRegex) ->
                if (args == null) args = defArgs
                if (exe == null && exeRegex != null && emulator.installDir != null) exe = findExe(File(emulator.installDir), exeRegex)
            }
        }
        val program = exe ?: return null
        val expanded = expand(args ?: "\"{ImagePath}\"", emulator.installDir, emulator.installDir, rom)
        val workDir = profile.workingDir?.let { expand(it, emulator.installDir, emulator.installDir, rom) } ?: File(program).parent
        return Resolved(program, expanded, workDir, emulator.name)
    }

    /** Playnite's placeholders in paths and arguments. */
    fun expand(value: String, installDir: String?, emulatorDir: String?, rom: String?): String {
        var s = value
            .replace("{InstallDir}", installDir.orEmpty())
            .replace("{EmulatorDir}", emulatorDir ?: installDir.orEmpty())
            .replace("{PlayniteDir}", playniteDir?.path.orEmpty())
        if (rom != null) {
            val name = WinPath.fileName(rom)
            s = s.replace("{ImagePath}", rom)
                .replace("{ImageNameNoExt}", name.substringBeforeLast('.'))
                .replace("{ImageName}", name)
                .replace("{ImageDir}", rom.substringBeforeLast('\\', rom.substringBeforeLast('/', "")))
        }
        return s
    }

    private fun findExe(dir: File, regex: String): String? {
        if (!dir.isDirectory) return null
        val re = Regex(regex, RegexOption.IGNORE_CASE)
        return dir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("exe", true) && re.containsMatchIn(it.name) }?.path
    }

    companion object {
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
            "microsoft xbox" to "Xbox", "microsoft xbox 360" to "Xbox 360", "microsoft xbox one" to "Xbox One",
            "sega genesis" to "Sega Mega Drive/Genesis", "sega mega drive" to "Sega Mega Drive/Genesis", "sega dreamcast" to "Dreamcast",
            "pc (windows)" to "PC", "pc (dos)" to "DOS", "windows" to "PC",
        )

        /** Playnite's platform names in IGDB's naming (as the companion does). */
        fun companionPlatform(name: String): String = platformNames[name.trim().lowercase()] ?: name.trim()

        /** Same as the companion's, so ids (and the host's app entries) stay the same. */
        fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').replace("--", "-")

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
