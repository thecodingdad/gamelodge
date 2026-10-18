package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.util.Log
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * Class cache (CDS archive) for faster starts: loading the UI's classes is most of the start time.
 * Without an archive the GameLodge.exe starter passes its path (gamelodge.cds) and lets the JVM
 * record what it loads; once the window has been shown for a while, the loaded classes are written
 * there and later starts map them instead of loading each class again.
 */
object ClassCache {
    /** The archive to write, null when started otherwise (archive in use, IDE, other starter). */
    val pending: File? get() = System.getProperty("gamelodge.cds")?.let(::File)?.takeUnless { it.exists() }

    /** Writes the archive (blocking, briefly pauses the JVM). */
    fun write() {
        val file = pending ?: return
        runCatching {
            // Archives of earlier versions (the name is a hash of runtime and jars).
            file.parentFile?.listFiles { f -> f.name.endsWith(".jsa") || f.name.endsWith(".tmp") }?.forEach { it.delete() }
            // Written aside first: a cut-off archive must not be found by the next start.
            val tmp = File(file.path + ".tmp")
            ManagementFactory.getPlatformMBeanServer().invoke(
                ObjectName("com.sun.management:type=DiagnosticCommand"), "vmCds",
                arrayOf<Any>(arrayOf("dynamic_dump", tmp.path)), arrayOf(Array<String>::class.java.name),
            )
            check(tmp.isFile && tmp.renameTo(file)) { "no archive written" }
            Log.i("Class cache written: ${file.name} (${file.length() / 1_048_576} MB)")
        }.onFailure { Log.w("Class cache not written", it) }
    }
}
