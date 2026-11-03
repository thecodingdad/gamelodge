package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.desktop.writeAtomically
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bundled Moonlight-Qt (portable mode, in "moonlight" next to the launcher's "lib") as the
 * streaming client. Before each start its settings get the launcher's identity and the paired
 * hosts with their pinned certificates, so it trusts the same pairing; its other settings stay.
 */
class MoonlightQt(val dir: File) {
    val exe: File get() = File(dir, "Moonlight.exe")
    val available: Boolean get() = exe.isFile

    /** How a stream start went: the stream runs, or Moonlight-Qt ended with an error. */
    sealed interface Start {
        data object Streaming : Start
        data class Failed(val message: String?, val appRunning: Boolean = false) : Start
    }

    /** The running stream: host uuid, app id and Moonlight-Qt's process. */
    data class Running(val hostUuid: String, val appId: Int, val process: Process) {
        /** A line for Moonlight-Qt's launcher control channel (quit, disconnect, servercmd N, mouse on|off, overlay …). */
        @Synchronized
        fun send(command: String): Boolean = runCatching {
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.flush()
        }.isSuccess
    }


    @Volatile var running: Running? = null
        private set

    /**
     * Starts the stream of [appId] on [host] without Moonlight-Qt's own window and dialogs
     * (launcher mode), as part of the launcher: its taskbar button, [title] and icon. [started]
     * learns once the first picture shows or the start failed; returns the exit code once the
     * stream has ended.
     */
    suspend fun stream(
        identity: StreamIdentity, hosts: List<StoredHost>, host: StoredHost, appId: Int, options: List<String>,
        apps: Map<String, List<MoonlightIni.App>>, title: String?, started: (Start) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        prepare(identity, hosts, apps)
        val command = listOf(exe.path, "stream", host.uuid, "appid:$appId") + options
        Log.i("Streaming app $appId of ${host.name}")
        val builder = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
        builder.environment()["MOONLIGHT_LAUNCHER_MODE"] = "1"
        // The stream starts with GameLodge's mouse emulation state and speed.
        val pointer = io.github.thecodingdad.gamelodge.desktop.WindowsPointer
        builder.environment()["MOONLIGHT_LAUNCHER_MOUSE"] = if (pointer.enabled) "1" else "0"
        builder.environment()["MOONLIGHT_LAUNCHER_MOUSE_SPEED"] = pointer.streamSpeed.toString()
        builder.environment()["MOONLIGHT_LAUNCHER_MOUSE_EXPONENT"] = pointer.exponent.toString()
        firstPad()?.let { builder.environment()["MOONLIGHT_LAUNCHER_FIRST_PAD"] = it }
        // Each controller's own stream settings (dead zone, vibration, motion …).
        padSettings()?.let { builder.environment()["MOONLIGHT_LAUNCHER_PADS"] = it }
        // The stream grabs the keyboard (Windows never sees Ctrl / Alt / Shift there, so the quick
        // menu hotkey can't fire): Moonlight-Qt catches it and reports "LAUNCHER-HOTKEY".
        builder.environment()["MOONLIGHT_LAUNCHER_HOTKEY"] = hotkeySpec(io.github.thecodingdad.gamelodge.input.KeyboardKeys.quickMenuShortcut)
        // Part of the launcher: its taskbar button, the game's name and the launcher's icon.
        builder.environment()["MOONLIGHT_LAUNCHER_APPID"] = io.github.thecodingdad.gamelodge.desktop.TaskbarIdentity.APP_ID
        title?.let { builder.environment()["MOONLIGHT_LAUNCHER_TITLE"] = "$it – GameLodge" }
        streamIcon()?.let { builder.environment()["MOONLIGHT_LAUNCHER_ICON"] = it.path }
        val process = builder.start()
        running = Running(host.uuid, appId, process)
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        fun report(start: Start) {
            if (reported.compareAndSet(false, true)) started(start)
        }
        var error: String? = null
        var appRunning = false
        File(dir, "stream.log").printWriter().use { log ->
            process.inputStream.bufferedReader().forEachLine { line ->
                val marker = line.substringAfter("LAUNCHER-", "")
                // Once a second: for the connection warning, not for the log.
                if (marker.startsWith("QUALITY: ")) {
                    qualitySample(marker.removePrefix("QUALITY: "))?.let(io.github.thecodingdad.gamelodge.stream.StreamQuality::sample)
                    return@forEachLine
                }
                log.println(line)
                log.flush()
                when {
                    // The window shows with its first picture; until then the launcher's start screen stays.
                    marker == "FIRSTFRAME" -> report(Start.Streaming)
                    // Connected: the picture follows (a Moonlight-Qt without FIRSTFRAME: after a moment).
                    marker == "STREAMING" -> Thread({
                        Thread.sleep(FIRST_FRAME_WAIT_MS)
                        if (process.isAlive) report(Start.Streaming)
                    }, "first-frame").apply { isDaemon = true }.start()
                    marker.startsWith("ERROR: ") -> error = marker.removePrefix("ERROR: ").trim()
                    marker.startsWith("APP-RUNNING: ") -> appRunning = true
                    marker == "HOTKEY" -> onHotkey()
                }
            }
        }
        val code = process.waitFor()
        running = null
        Log.i("Stream ended ($code)${error?.let { ": $it" } ?: ""}")
        report(Start.Failed(error, appRunning))
        code
    }

    /** "rtt variance framesLost audioDrops overlay" as Moonlight-Qt reports it. */
    private fun qualitySample(text: String): io.github.thecodingdad.gamelodge.stream.ConnectionSample? {
        val v = text.trim().split(' ').mapNotNull { it.toIntOrNull() }
        if (v.size < 5) return null
        return io.github.thecodingdad.gamelodge.stream.ConnectionSample(v[0], v[1], v[2] == 1, v[3], v[4] == 1)
    }

    /** The launcher's icon as a file for the stream window (written once). */
    private fun streamIcon(): File? = runCatching {
        val file = File(io.github.thecodingdad.gamelodge.desktop.dataDirectory(), "cache/stream-icon.png")
        if (!file.isFile) {
            file.parentFile.mkdirs()
            MoonlightQt::class.java.getResourceAsStream("/icon.png")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        file
    }.onFailure { Log.w("Stream icon not written", it) }.getOrNull()

    /** portable.dat and the identity / hosts part of Moonlight.ini. */
    fun prepare(identity: StreamIdentity, hosts: List<StoredHost>, apps: Map<String, List<MoonlightIni.App>> = emptyMap()) {
        File(dir, "portable.dat").takeIf { !it.exists() }?.writeText("")
        // Portable mode keeps QSettings in <dir>/<organization>/<application>.ini.
        val ini = File(dir, "Moonlight Game Streaming Project/Moonlight.ini").apply { parentFile.mkdirs() }
        val text = runCatching { ini.readText() }.getOrDefault("")
        ini.writeAtomically(MoonlightIni.update(text, identity, hosts.filter { it.serverCert != null }, apps))
    }

    companion object {
        /** Longest wait for the first picture after connecting. */
        private const val FIRST_FRAME_WAIT_MS = 10_000L

        @Volatile private var located: MoonlightQt? = null

        /** The quick menu shortcut was pressed in the stream (Moonlight-Qt's output thread). */
        @Volatile var onHotkey: () -> Unit = {}

        /** [shortcut] for Moonlight-Qt ("ctrl+alt+M", SDL key names), "" for none. */
        fun hotkeySpec(shortcut: io.github.thecodingdad.gamelodge.input.KeyShortcut?): String = shortcut?.let { s ->
            listOfNotNull("ctrl".takeIf { s.ctrl }, "alt".takeIf { s.alt }, "shift".takeIf { s.shift }, s.key).joinToString("+")
        }.orEmpty()

        /** The pad that starts a stream becomes player 1 ("<n>:<name>", see [Gamepads.starter]); null: the usual order. */
        @Volatile var firstPad: suspend () -> String? = { null }

        /**
         * Moonlight-Qt's SDL3, which the launcher's controller support uses as well: from the
         * installed package (not the writable copy, which a newer version may replace).
         */
        fun sdlLibrary(): File {
            val jar = File(MoonlightQt::class.java.protectionDomain.codeSource.location.toURI())
            val base = if (jar.isFile) jar.parentFile.parentFile else File(".")
            return File(File(base, "moonlight"), "SDL3.dll")
        }

        /** The controllers' stream settings as JSON (see [StreamPads]); null: Moonlight-Qt's defaults. */
        @Volatile var padSettings: suspend () -> String? = { null }

        /**
         * Next to the running launcher (lib/ → moonlight/). Moonlight-Qt keeps its settings next to
         * its program (portable mode): installed for all users (Program Files, not writable) it runs
         * from a copy in the launcher's data folder, renewed when the bundled one changes.
         */
        @Synchronized
        fun locate(): MoonlightQt {
            located?.let { return it }
            val jar = File(MoonlightQt::class.java.protectionDomain.codeSource.location.toURI())
            val base = if (jar.isFile) jar.parentFile.parentFile else File(".")
            val bundled = File(base, "moonlight")
            val dir = if (!bundled.isDirectory || writable(bundled)) bundled else mirror(bundled, File(io.github.thecodingdad.gamelodge.desktop.dataDirectory(), "moonlight"))
            return MoonlightQt(dir).also { located = it }
        }

        private fun writable(dir: File): Boolean = runCatching {
            val probe = File(dir, ".write-test")
            probe.writeText("")
            probe.delete()
        }.getOrDefault(false)

        private fun mirror(source: File, target: File): File {
            val exe = File(source, "Moonlight.exe")
            val stamp = "${exe.length()}-${exe.lastModified()}"
            val stampFile = File(target, ".bundled")
            if (stampFile.isFile && stampFile.readText() == stamp) return target
            io.github.thecodingdad.gamelodge.util.Log.i("Moonlight-Qt: copying to ${target.path} (install folder not writable)")
            source.walkTopDown().forEach { f ->
                val rel = f.relativeTo(source)
                val out = File(target, rel.path)
                if (f.isDirectory) out.mkdirs() else f.copyTo(out, overwrite = true)
            }
            stampFile.writeText(stamp)
            return target
        }
    }
}

/**
 * Moonlight-Qt's settings file (QSettings INI). Only the identity keys of [General] and the whole
 * [hosts] section are written; everything else (its own preferences) stays as it is.
 */
object MoonlightIni {
    /** An app of a host as Moonlight-Qt keeps it. */
    data class App(val id: Int, val name: String, val hdr: Boolean)

    /**
     * [apps]: the hosts' app lists the launcher knows, so Moonlight-Qt finds the app right away
     * (it waits only 10 s for its own list, which a host with many apps may not deliver in time).
     */
    fun update(existing: String, identity: StreamIdentity, hosts: List<StoredHost>, apps: Map<String, List<App>> = emptyMap()): String {
        val sections = parse(existing)
        val general = sections.getOrPut("General") { linkedMapOf() }
        general["certificate"] = byteArray(identity.certificatePem)
        general["key"] = byteArray(identity.privateKeyPem)
        general["uniqueid"] = identity.uniqueId
        // Its own host list would be replaced on its next save anyway; a fresh one avoids stale entries.
        sections.remove("hostsbackup")
        val h = linkedMapOf<String, String>()
        hosts.forEachIndexed { i, host ->
            val p = "${i + 1}\\"
            h["${p}hostname"] = quote(host.name)
            h["${p}uuid"] = host.uuid
            h["${p}customname"] = "false"
            // The LAN IP first: a host name may resolve to an IPv6 address the host doesn't stream on.
            h["${p}localaddress"] = quote(host.localIp ?: host.address)
            h["${p}localport"] = host.port.toString()
            h["${p}manualaddress"] = quote(host.address)
            h["${p}manualport"] = host.port.toString()
            h["${p}srvcert"] = byteArray(host.serverCert!!)
            h["${p}nvidiasw"] = "false"
            val list = apps[host.uuid].orEmpty()
            list.forEachIndexed { j, app ->
                val a = "${p}apps\\${j + 1}\\"
                h["${a}name"] = quote(app.name)
                h["${a}id"] = app.id.toString()
                h["${a}hdr"] = app.hdr.toString()
            }
            if (list.isNotEmpty()) h["${p}apps\\size"] = list.size.toString()
        }
        h["size"] = hosts.size.toString()
        sections["hosts"] = h
        return sections.entries.joinToString("\n") { (name, keys) ->
            "[$name]\n" + keys.entries.joinToString("") { "${it.key}=${it.value}\n" }
        }
    }

    private fun parse(text: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val result = linkedMapOf<String, LinkedHashMap<String, String>>()
        var current: LinkedHashMap<String, String>? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                current = result.getOrPut(t.substring(1, t.length - 1)) { linkedMapOf() }
            } else if ('=' in t && current != null) {
                current[t.substringBefore('=')] = t.substringAfter('=')
            }
        }
        return result
    }

    /** A QByteArray value as QSettings writes it (quoted, \n escaped). */
    fun byteArray(s: String) = quote("@ByteArray($s)")

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20 || c.code > 0x7e) sb.append("\\x").append("%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
