package io.github.thecodingdad.moonlightlauncher.data.artwork

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableIntStateOf
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryDao
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import io.github.thecodingdad.moonlightlauncher.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The library's artwork kept on the device (app storage, no permission needed): downloaded once
 * in the background, shown from the file from then on, removed when no game uses it any more.
 * Animated images are large (often 10–30 MB): they are only downloaded while animated artwork
 * is switched on, and over metered connections (mobile data, hotspot) only when allowed.
 */
object ArtworkFiles {
    private lateinit var dir: File
    private val have = ConcurrentHashMap.newKeySet<String>()
    private val names = ConcurrentHashMap<String, String>()

    /** Bumped when a file arrives or goes: images showing the URL switch to the file. */
    internal val version = mutableIntStateOf(0)

    /** Animated images may be loaded from the network right now (Wi-Fi, or metered allowed). */
    @Volatile var animatedOnline: Boolean = true
        internal set

    fun init(context: Context) {
        dir = File(context.filesDir, "artwork").apply { mkdirs() }
        dir.list()?.filterNot { it.endsWith(".tmp") }?.let(have::addAll)
    }

    internal fun nameOf(url: String): String = names.getOrPut(url) {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
        if (ext != null) "$hash.$ext" else hash
    }

    internal fun fileOf(name: String) = File(dir, name)
    internal fun has(name: String) = name in have
    internal fun names(): Set<String> = have.toSet()

    internal fun added(name: String) {
        have += name
        version.intValue++
    }

    internal fun removed(name: String) {
        have -= name
        version.intValue++
    }

    /** What to show for an image: the stored file when there is one, else the address itself. */
    fun model(url: String?): Any? {
        url ?: return null
        if (!url.startsWith("http")) return url
        version.intValue // recompose when the file arrives
        val name = nameOf(url)
        return if (name in have) fileOf(name) else url
    }

    /** An animated image: the stored file, the network only when allowed right now, else nothing. */
    fun animatedModel(url: String?): Any? {
        url ?: return null
        if (!url.startsWith("http")) return url
        version.intValue
        val name = nameOf(url)
        return when {
            name in have -> fileOf(name)
            animatedOnline -> url
            else -> null
        }
    }

    /** Space the stored images take. */
    fun sizeBytes(): Long = if (::dir.isInitialized) dir.listFiles()?.sumOf { it.length() } ?: 0 else 0
}

class ArtworkStore(
    private val context: Context,
    private val http: OkHttpClient,
    private val dao: LibraryDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private data class Wanted(val still: Set<String>, val animated: Set<String>)

    @OptIn(FlowPreview::class)
    fun start() {
        val wanted = dao.observeAll().map { entries ->
            val items = entries.map { it.item }
            Wanted(
                items.flatMap { listOf(it.coverUrl, it.heroUrl, it.logoUrl, it.iconUrl) }.filterWeb(),
                items.flatMap { listOf(it.animatedCoverUrl, it.animatedHeroUrl) }.filterWeb(),
            )
        }.distinctUntilChanged()
        val prefs = settings.settings.map { it.animatedArtwork to it.animatedOnMobileData }.distinctUntilChanged()
        scope.launch(Dispatchers.IO) {
            combine(wanted, prefs, metered()) { w, (animated, mobile), metered -> Triple(w, animated, metered && !mobile) }
                .debounce(3_000)
                .collectLatest { (w, animated, holdBack) ->
                    ArtworkFiles.animatedOnline = animated && !holdBack
                    runCatching { sync(w, animated, holdBack) }.onFailure { Log.w("Artwork download failed", it) }
                }
        }
    }

    private fun List<String?>.filterWeb() = filterNotNull().filter { it.startsWith("https://") || it.startsWith("http://") }.toSet()

    private suspend fun sync(w: Wanted, animated: Boolean, holdBack: Boolean) = withContext(Dispatchers.IO) {
        val keep = w.still + (if (animated) w.animated else emptySet())
        val keepNames = keep.map(ArtworkFiles::nameOf).toSet()
        // Gone from the library (or animated switched off): the space is freed.
        ArtworkFiles.names().filter { it !in keepNames }.forEach { name ->
            ArtworkFiles.fileOf(name).delete()
            ArtworkFiles.removed(name)
        }
        val todo = w.still + (if (animated && !holdBack) w.animated else emptySet())
        for (url in todo) {
            ensureActive()
            val name = ArtworkFiles.nameOf(url)
            if (ArtworkFiles.has(name)) continue
            download(url, name)
        }
    }

    private fun download(url: String, name: String) {
        val tmp = ArtworkFiles.fileOf("$name.tmp")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return
                if (body.contentLength() > MAX_BYTES) { Log.d("Artwork too large, not kept: $url"); return }
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            if (tmp.length() in 1..MAX_BYTES && tmp.renameTo(ArtworkFiles.fileOf(name))) ArtworkFiles.added(name)
        } catch (e: Exception) {
            Log.d("Artwork $url not downloaded: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    /** True while the default network is metered (mobile data, hotspot). */
    private fun metered() = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun current() = cm.isActiveNetworkMetered
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
            override fun onLost(network: Network) { trySend(current()) }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
