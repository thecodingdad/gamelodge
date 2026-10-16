package io.github.thecodingdad.moonlightlauncher.desktop

import io.github.thecodingdad.moonlightlauncher.desktop.games.GameLauncher
import io.github.thecodingdad.moonlightlauncher.desktop.games.PcLibrary
import io.github.thecodingdad.moonlightlauncher.desktop.host.sync.LaunchSpec
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The streaming host's command for every synced game ("--run <spec>"): starts the game (or its
 * store installation) and stays alive exactly as long as the game runs, so the stream ends with it.
 * No window, no second launcher.
 */
object Runner {
    const val ARG = "--run"

    fun run(arg: String?): Int {
        val logDir = File(dataDirectory(), "logs")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val spec = runCatching { LaunchSpec.decode(arg ?: error("missing launch argument")) }.getOrElse {
            Log.init(File(logDir, "run-error-$stamp.log"))
            Log.w("Runner: invalid argument", it)
            return 2
        }
        Log.init(File(logDir, "run-${spec.id.map { c -> if (c.isLetterOrDigit()) c else '_' }.take(60).joinToString("")}-$stamp.log"))
        cleanupLogs(logDir)
        Log.i("Runner: ${spec.mode} ${spec.name} (${spec.id})")
        val entry = spec.toEntry()
        return runCatching {
            runBlocking {
                if (spec.mode == LaunchSpec.INSTALL) {
                    val http = OkHttpClient()
                    GameLauncher.install(entry) { PcLibrary.installedNow(entry, http) }
                } else {
                    GameLauncher.play(entry)
                }
            }
        }.fold({ ok -> if (ok) 0 else 1 }) {
            Log.w("Runner failed", it)
            1
        }
    }

    /** What the host runs for a game, followed by the encoded spec. */
    fun command(): String = launcherCommand(ARG)

    /** This launcher's Java runtime (javaw: no console window) with its jars and [arg]. */
    fun launcherCommand(arg: String): String {
        val java = ProcessHandle.current().info().command().orElse(null)
            ?.let { File(it) }
            ?.let { exe -> if (exe.name.equals("java.exe", ignoreCase = true)) File(exe.parentFile, "javaw.exe") else exe }
            ?: File(System.getProperty("java.home"), if (isWindows) "bin/javaw.exe" else "bin/java")
        val jar = File(Runner::class.java.protectionDomain.codeSource.location.toURI())
        val classpath = if (jar.isFile) File(jar.parentFile, "*").path else jar.path
        val memory = if (arg == ARG) "-Xmx256m" else "-Xmx1g"
        return "\"${java.path}\" $memory -cp \"$classpath\" $MAIN_CLASS $arg"
    }

    private const val MAIN_CLASS = "io.github.thecodingdad.moonlightlauncher.desktop.MainKt"

    private fun cleanupLogs(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith("run-") }?.sortedByDescending { it.lastModified() }?.drop(50)?.forEach { it.delete() }
        }
    }
}
