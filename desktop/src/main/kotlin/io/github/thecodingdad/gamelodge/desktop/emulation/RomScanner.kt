package io.github.thecodingdad.gamelodge.desktop.emulation

import io.github.thecodingdad.gamelodge.desktop.games.GameEntry
import io.github.thecodingdad.gamelodge.desktop.games.GameScanner
import io.github.thecodingdad.gamelodge.desktop.games.WinPath
import io.github.thecodingdad.gamelodge.util.Log
import java.io.File

/** Finds games in the ROM folders and starts each with its console's emulator. */
class RomScanner(private val config: () -> PcEmulation) : GameScanner {
    override val source = "emulator"

    override suspend fun scan(): List<GameEntry> {
        val c = config()
        val result = LinkedHashMap<String, GameEntry>()
        for (folder in c.folders) {
            val root = File(folder.path)
            if (!root.isDirectory) { Log.w("Emulation: ROM folder missing: ${folder.path}"); continue }
            for ((dir, platform) in platformFolders(root, folder.platform)) {
                val emulator = c.emulatorFor(platform, folder.emulator)
                if (emulator == null) { Log.i("Emulation: no emulator for $platform (${dir.path})"); continue }
                val support = emulator.platforms.first { it.platform == platform }
                val games = scan(dir.path, platform, support, emulator)
                Log.i("Emulation: ${emulator.name} · $platform · ${dir.path}: ${games.size} games")
                games.forEach { result.putIfAbsent(it.id, it) }
            }
        }
        return result.values.sortedBy { it.name.lowercase() }
    }

    companion object {
        /** The folders to scan and their console: the folder itself, or its subfolders named like consoles. */
        fun platformFolders(root: File, platform: String?): List<Pair<File, String>> {
            if (platform != null) return listOf(root to platform)
            PcPlatforms.forFolder(root.name)?.let { return listOf(root to it.name) }
            return root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d -> PcPlatforms.forFolder(d.name)?.let { d to it.name } }
        }

        fun scan(root: String, platform: String, support: PcEmulatorPlatform, emulator: PcEmulator): List<GameEntry> {
            val extensions = support.effectiveExtensions()
            val picked = HashMap<String, Pair<String, Int>>()
            fun offer(name: String, path: String, priority: Int) {
                if (name.isBlank()) return
                val key = name.lowercase()
                val have = picked[key]
                if (have == null || priority < have.second) picked[key] = path to priority
            }
            val names = HashMap<String, String>()

            emulator.folderGameFile?.let { inner ->
                File(root).listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                    val file = File(dir, inner.replace('\\', File.separatorChar))
                    if (file.isFile) RomNames.clean(dir.name).let { n -> names[n.lowercase()] = n; offer(n, file.path, 0) }
                }
            }

            val all = files(File(root))
            val files = all.filter { WinPath.extension(it.path) in extensions }
            val lookup = files.map { it.path.lowercase() }.toHashSet()
            for (f in files) {
                val ext = WinPath.extension(f.path)
                val fileName = f.name
                val base = WinPath.fileNameWithoutExtension(fileName)
                // A .bin next to its .cue sheet is part of that cue.
                if (ext == ".bin" && f.path.dropLast(4).plus(".cue").lowercase() in lookup) continue
                if (platform == "Nintendo Switch" && RomNames.isSwitchAddon(fileName)) continue
                val name = when {
                    ext == ".rpx" -> {
                        // Loadiine layout: <Game>\code\<x>.rpx
                        val code = f.parentFile ?: continue
                        if (!code.name.equals("code", ignoreCase = true)) continue
                        RomNames.clean(code.parentFile?.name ?: continue)
                    }
                    // "Alice - Madness Returns\Default.xex": the folder names the game.
                    RomNames.isGeneric(base) -> RomNames.folderName(f.path, root)?.let(RomNames::clean) ?: RomNames.clean(base)
                    else -> RomNames.clean(base)
                }
                val disc = RomNames.disc(fileName)
                // Multi-disc sets: a playlist (.m3u) wins, else disc 1.
                val priority = extensions.indexOf(ext) + (if (ext == ".m3u") -100 else 0) + (if (disc != null && disc > 1) 1000 else 0)
                names.putIfAbsent(name.lowercase(), name)
                offer(name, f.path, priority)
            }

            // Xbox 360 Arcade / Games on Demand: STFS packages without extension, named by their header.
            if (platform == "Xbox 360") {
                all.filter { WinPath.extension(it.path).isEmpty() }.forEach { f ->
                    val info = Stfs.read(f)?.takeIf { it.isGame } ?: return@forEach
                    val name = RomNames.clean(info.title ?: RomNames.folderName(f.path, root) ?: f.name)
                    names.putIfAbsent(name.lowercase(), name)
                    offer(name, f.path, 50)
                }
            }

            return picked.entries.sortedBy { it.key }.map { (key, value) -> entry(names[key] ?: key, value.first, platform, support, emulator) }
        }

        fun entry(name: String, rom: String, platform: String, support: PcEmulatorPlatform, emulator: PcEmulator): GameEntry {
            val emuDir = WinPath.directory(emulator.executable).orEmpty()
            fun x(value: String) = expand(value, rom, emuDir, support.core)
            val exe = emulator.executable
            return GameEntry(
                id = "rom:${slug(platform)}:${slug(name)}",
                name = name,
                source = "emulator",
                platform = PcPlatforms.byName(platform)?.displayName ?: platform,
                installed = true,
                launch = exe,
                launchArgs = x(emulator.arguments),
                workingDir = emulator.workingDir?.let(::x) ?: emuDir,
                processDir = emuDir,
                processNames = listOf(WinPath.fileName(exe)),
            )
        }

        /**
         * Placeholders: {rom} full ROM path, {romName} file name without extension, {romFile} file
         * name, {romDir} its folder, {emuDir} the emulator's folder, {core} the RetroArch core;
         * Playnite's names ({ImagePath}, {ImageName}, {ImageNameNoExt}, {ImageDir}, {EmulatorDir}) too.
         */
        fun expand(value: String, rom: String, emuDir: String, core: String?): String {
            val file = WinPath.fileName(rom)
            val noExt = WinPath.fileNameWithoutExtension(file)
            val dir = WinPath.directory(rom).orEmpty()
            val map = listOf(
                "{rom}" to rom, "{ImagePath}" to rom, "{romName}" to noExt, "{ImageNameNoExt}" to noExt,
                "{romFile}" to file, "{ImageName}" to file, "{romDir}" to dir, "{ImageDir}" to dir,
                "{emuDir}" to emuDir, "{EmuDir}" to emuDir, "{EmulatorDir}" to emuDir, "{core}" to core.orEmpty(),
            )
            var s = value
            for ((k, v) in map) s = s.replace(k, v, ignoreCase = true)
            return s
        }

        /** Same as the companion's, so ids (and the host's app entries) stay the same. */
        fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').replace("--", "-")

        private fun files(root: File): List<File> =
            runCatching { root.walkTopDown().maxDepth(5).onFail { f, _ -> Log.d("Emulation: cannot read ${f.path}") }.filter { it.isFile }.toList() }
                .getOrElse { Log.w("Emulation: cannot read ${root.path}", it); emptyList() }
    }
}
