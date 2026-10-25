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

    /**
     * Starts the stream of [appId] on [host] without Moonlight-Qt's own window and dialogs
     * (launcher mode): [started] learns once the stream runs or failed; returns the exit code
     * once the stream has ended.
     */
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

    suspend fun stream(
        identity: StreamIdentity, hosts: List<StoredHost>, host: StoredHost, appId: Int, options: List<String>,
        apps: Map<String, List<MoonlightIni.App>>, started: (Start) -> Unit,
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
        val process = builder.start()
        running = Running(host.uuid, appId, process)
        var reported = false
        var error: String? = null
        var appRunning = false
        File(dir, "stream.log").printWriter().use { log ->
            process.inputStream.bufferedReader().forEachLine { line ->
                log.println(line)
                log.flush()
                val marker = line.substringAfter("LAUNCHER-", "")
                when {
                    marker == "STREAMING" && !reported -> {
                        reported = true
                        started(Start.Streaming)
                    }
                    marker.startsWith("ERROR: ") -> error = marker.removePrefix("ERROR: ").trim()
                    marker.startsWith("APP-RUNNING: ") -> appRunning = true
                }
            }
        }
        val code = process.waitFor()
        running = null
        Log.i("Stream ended ($code)${error?.let { ": $it" } ?: ""}")
        if (!reported) started(Start.Failed(error, appRunning))
        code
    }

    /** portable.dat and the identity / hosts part of Moonlight.ini. */
    fun prepare(identity: StreamIdentity, hosts: List<StoredHost>, apps: Map<String, List<MoonlightIni.App>> = emptyMap()) {
        File(dir, "portable.dat").takeIf { !it.exists() }?.writeText("")
        // Portable mode keeps QSettings in <dir>/<organization>/<application>.ini.
        val ini = File(dir, "Moonlight Game Streaming Project/Moonlight.ini").apply { parentFile.mkdirs() }
        val text = runCatching { ini.readText() }.getOrDefault("")
        ini.writeAtomically(MoonlightIni.update(text, identity, hosts.filter { it.serverCert != null }, apps))
    }

    companion object {
        @Volatile private var located: MoonlightQt? = null

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
