package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.desktop.games.GameCloser
import io.github.thecodingdad.gamelodge.desktop.games.GameLauncher
import io.github.thecodingdad.gamelodge.desktop.games.PcLibrary
import io.github.thecodingdad.gamelodge.desktop.host.sync.LaunchSpec
import io.github.thecodingdad.gamelodge.util.Log
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
                    // The running launcher's game actions (if switched on for host starts).
                    SingleInstance.reportGameStart(entry.id)
                    // The host ends the app: close the game first (like Alt+F4, hard only when it
                    // stays), then end with it.
                    CloseRequests.listen {
                        Log.i("Runner: the host asked to end ${spec.name}")
                        GameCloser.close({ GameLauncher.gameProcesses() })
                        SingleInstance.reportGameEnd(entry.id)
                        kotlin.system.exitProcess(0)
                    }
                    try {
                        GameLauncher.play(entry)
                    } finally {
                        SingleInstance.reportGameEnd(entry.id)
                    }
                }
            }
        }.fold({ ok -> if (ok) 0 else 1 }) {
            Log.w("Runner failed", it)
            1
        }
    }

    /**
     * True while the streaming host runs one of its games through a runner: another process of this
     * launcher's executable (the launcher itself runs once). Its Guide presses come from the
     * stream's virtual pad and belong to the client, not to this PC's launcher.
     */
    fun hostGameRunning(): Boolean = runners().isNotEmpty()

    /** The runner processes (other processes of this launcher's executable). */
    fun runners(): List<ProcessHandle> {
        val self = ProcessHandle.current()
        val exe = self.info().command().orElse(null) ?: return emptyList()
        return ProcessHandle.allProcesses().use { all ->
            all.filter { it.pid() != self.pid() && it.info().command().orElse(null).equals(exe, ignoreCase = true) }.toList()
        }
    }

    /** What the host runs for a game, followed by the encoded spec. */
    fun command(): String = launcherCommand(ARG)

    /** This launcher's Java runtime (javaw: no console window) with its jars and [arg]. */
    fun launcherCommand(arg: String): String {
        // Installed or portable package: its starter (keeps the JVM options in one place).
        val jarFile = File(Runner::class.java.protectionDomain.codeSource.location.toURI())
        val starter = jarFile.parentFile?.parentFile?.let { File(it, "GameLodge.exe") }
        if (arg != ARG && isWindows && starter?.isFile == true) return "\"${starter.path}\" $arg"

        val java = ProcessHandle.current().info().command().orElse(null)
            ?.let { File(it) }
            ?.let { exe -> if (exe.name.equals("java.exe", ignoreCase = true)) File(exe.parentFile, "javaw.exe") else exe }
            ?: File(System.getProperty("java.home"), if (isWindows) "bin/javaw.exe" else "bin/java")
        val jar = File(Runner::class.java.protectionDomain.codeSource.location.toURI())
        val classpath = if (jar.isFile) File(jar.parentFile, "*").path else jar.path
        val memory = if (arg == ARG) "-Xmx256m" else "-Xmx1g -XX:+UseZGC -XX:+ZGenerational"
        return "\"${java.path}\" $memory -cp \"$classpath\" $MAIN_CLASS $arg"
    }

    private const val MAIN_CLASS = "io.github.thecodingdad.gamelodge.desktop.MainKt"

    private fun cleanupLogs(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith("run-") }?.sortedByDescending { it.lastModified() }?.drop(50)?.forEach { it.delete() }
        }
    }
}
