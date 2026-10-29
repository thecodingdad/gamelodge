package io.github.thecodingdad.gamelodge.desktop.host.sync

import io.github.thecodingdad.gamelodge.host.*

import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Rect
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How the last sync went. [error]: "no_host", "host_status:<code>", "unreachable" or null. */
data class SyncResult(val added: Int, val updated: Int, val removed: Int, val failed: Int, val error: String? = null, val at: Long = System.currentTimeMillis())

/** Connection to the streaming host on this PC (its web API). */
data class HostConnection(val url: String, val type: HostType, val user: String?, val secret: String?) {
    /** URL and the credentials its type needs are there. */
    val complete: Boolean
        get() = url.isNotBlank() && !secret.isNullOrBlank() && (type == HostType.VIBEPOLLO || !user.isNullOrBlank())
}

/** Whether the streaming host answers with these credentials ([apps]: entries it lists). */
data class HostCheck(val state: State, val apps: Int = 0, val status: Int = 0, val at: Long = System.currentTimeMillis()) {
    enum class State { CHECKING, CONNECTED, NOT_SET_UP, AUTH_FAILED, UNREACHABLE, ERROR }
}

/**
 * Puts the PC's games on the streaming host (Vibepollo, Apollo, Sunshine) as app entries that
 * start through the launcher's runner: plan against the host's list, back it up, then add,
 * update and remove only the entries this sync (or the companion) made.
 */
class HostSync(
    private val dataDir: File,
    private val http: OkHttpClient,
    private val connection: () -> HostConnection?,
    /** Games that should be on the host now. */
    private val wanted: suspend () -> List<SyncEntry>,
    /** Stores whose last scan failed: their entries are not removed. */
    private val incompleteSources: () -> Set<String>,
    /** Program and arguments the host runs, followed by the encoded launch spec. */
    private val runnerCommand: () -> String,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    private val _last = MutableStateFlow<SyncResult?>(null)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val last: StateFlow<SyncResult?> = _last.asStateFlow()
    /** Changes sent to the host so far (done, total) while a sync writes; null otherwise. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    private val _check = MutableStateFlow<HostCheck?>(null)
    /** Last connection check (also updated by every sync). */
    val check: StateFlow<HostCheck?> = _check.asStateFlow()

    /** Checks the stored connection and publishes the result in [check]. */
    suspend fun refreshCheck(): HostCheck {
        if (_check.value == null) _check.value = HostCheck(HostCheck.State.CHECKING)
        return test(connection()).also { _check.value = it }
    }

    /** Checks [conn] (e.g. values not saved yet) without publishing the result. */
    suspend fun test(conn: HostConnection?): HostCheck = withContext(Dispatchers.IO) {
        if (conn == null || !conn.complete) return@withContext HostCheck(HostCheck.State.NOT_SET_UP)
        val (apps, res) = HostApiClient(conn.url, conn.type, conn.secret, conn.user, http).getApps()
        checkOf(apps?.size, res.status)
    }

    private fun checkOf(apps: Int?, status: Int) = when {
        apps != null -> HostCheck(HostCheck.State.CONNECTED, apps = apps, status = status)
        status == 0 -> HostCheck(HostCheck.State.UNREACHABLE)
        status == 401 || status == 403 -> HostCheck(HostCheck.State.AUTH_FAILED, status = status)
        else -> HostCheck(HostCheck.State.ERROR, status = status)
    }

    private val coverDir = File(dataDir, "covers")
    private val backupDir = File(dataDir, "backups")

    suspend fun run(): SyncResult = mutex.withLock {
        _running.value = true
        report(0, 0)
        try {
            withContext(Dispatchers.IO) { runLocked() }.also { _last.value = it }
        } catch (e: Exception) {
            Log.w("Sync failed", e)
            SyncResult(0, 0, 0, 0, "unreachable").also { _last.value = it }
        } finally {
            _running.value = false
            _progress.value = null
            io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.remove(TASK_ID)
        }
    }

    private fun report(done: Int, total: Int) {
        _progress.value = done to total
        io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.set(io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask(TASK_ID, io.github.thecodingdad.gamelodge.resources.Res.string.task_host_sync, done = done, total = total))
    }

    private suspend fun runLocked(): SyncResult {
        val conn = connection() ?: return SyncResult(0, 0, 0, 0, "no_host")
        val host = HostApiClient(conn.url, conn.type, conn.secret, conn.user, http)
        val (apps, res) = host.getApps()
        _check.value = checkOf(apps?.size, res.status)
        if (apps == null) {
            Log.w("Sync: host answered ${res.status}")
            return SyncResult(0, 0, 0, 0, if (res.status == 0) "unreachable" else "host_status:${res.status}")
        }
        backup(res.body)
        val entries = wanted()
        val coverPaths = entries.associate { it.entry.id to cover(it) }
        val incomplete = incompleteSources()
        val command = runnerCommand()
        val plan = SyncPlanner.plan(entries, apps, { AppBuilder.build(it, command, coverPaths[it.entry.id]) }) { app ->
            (app[AppBuilder.MARKER] as? kotlinx.serialization.json.JsonObject)?.str("source") in incomplete
        }
        if (incomplete.isNotEmpty()) Log.w("Sync: no removals for incomplete sources: ${incomplete.joinToString()}")
        Log.i("Sync plan: ${plan.adds.size} new, ${plan.updates.size} changed, ${plan.removes.size} removed")
        var failed = 0
        val total = plan.adds.size + plan.updates.size + plan.removes.size
        var done = 0
        for (change in plan.adds + plan.updates) {
            report(done++, total)
            val r = host.saveApp(change.app)
            if (!r.ok) {
                failed++
                Log.w("Sync: saving ${change.name} failed (${r.status})")
            }
        }
        for ((uuid, name) in plan.removes) {
            report(done++, total)
            val r = host.deleteApp(uuid)
            if (!r.ok) {
                failed++
                Log.w("Sync: removing $name failed (${r.status})")
            }
        }
        if (!plan.isEmpty) Log.i("Sync done ($failed failed)")
        return SyncResult(plan.adds.size, plan.updates.size, plan.removes.size, failed)
    }

    /** The host's box art: a PNG made from the first cover that loads; kept until another cover is chosen. */
    private fun cover(s: SyncEntry): String? {
        val file = File(coverDir, "${fileName(s.entry.id)}.png")
        val src = File(file.path + ".src")
        val candidates = s.covers.distinct()
        if (file.exists() && (candidates.isEmpty() || src.takeIf { it.exists() }?.readText() == candidates.first())) return file.absolutePath
        coverDir.mkdirs()
        for (candidate in candidates) {
            val bytes = load(candidate) ?: continue
            val png = runCatching { toPng(bytes) }.getOrNull() ?: continue
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(png)
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            src.writeText(candidate)
            return file.absolutePath
        }
        return file.takeIf { it.exists() }?.absolutePath
    }

    private fun load(source: String): ByteArray? = runCatching {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            http.newCall(Request.Builder().url(source).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()?.takeIf { it.size > 2048 } // placeholder images are tiny
            }
        } else {
            val f = if (source.startsWith("file:")) File(URI(source)) else File(source)
            f.takeIf { it.isFile }?.readBytes()
        }
    }.getOrNull()

    /** Backups of the host's app list, the last 10. */
    private fun backup(body: String) {
        runCatching {
            backupDir.mkdirs()
            File(backupDir, "apps-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json").writeText(body)
            backupDir.listFiles { f -> f.name.startsWith("apps-") }?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        }.onFailure { Log.w("Sync: backup failed", it) }
    }

    /** The image served for "image:<id>" (the PNG box art of a game; a ".v<time>" version suffix is ignored). */
    fun coverFile(imageId: String): File? {
        if (imageId.contains("..") || !imageId.all { it.isLetterOrDigit() && it.code < 128 || it in "-_." }) return null
        val name = imageId.replace(VERSION_SUFFIX, "")
        return File(coverDir, "$name.png").takeIf { it.isFile }
    }

    companion object {
        private const val TASK_ID = "host-sync"
        private val VERSION_SUFFIX = Regex("""\.v\d+$""")
        /** File name of a game's box art (letters, digits, - and . stay). */
        fun fileName(id: String) = id.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.') it else '_' }.joinToString("")

        /** Any image (JPEG, PNG, WebP …) as PNG, at most 600 px wide. */
        fun toPng(bytes: ByteArray): ByteArray {
            val image = Image.makeFromEncoded(bytes)
            val scale = minOf(1f, 600f / image.width)
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
            return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
    }
}
