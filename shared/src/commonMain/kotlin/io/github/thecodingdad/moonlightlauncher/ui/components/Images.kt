package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import io.github.thecodingdad.moonlightlauncher.data.artwork.ArtworkFiles
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.MetaField
import io.github.thecodingdad.moonlightlauncher.ui.theme.LocalAccentHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/** Best available portrait cover for an entry: metadata cover (stored file when there), then host box art. */
fun LibraryEntry.coverModel(): Any? =
    ArtworkFiles.model(item.coverUrl)
        // Removed by hand: no stand-in (the title placeholder shows), not the host's box art.
        ?: boxArtPath?.takeIf { item.coverUrl == null && MetaField.COVER !in item.manual }?.let(::boxArtModel)

/** The animated cover, when there is one and it may be shown now (stored, or the network allows it). */
fun LibraryEntry.animatedCoverModel(): Any? = ArtworkFiles.animatedModel(item.animatedCoverUrl)

fun LibraryEntry.animatedHeroModel(): Any? = ArtworkFiles.animatedModel(item.animatedHeroUrl)

/** Host box art is a cached file; a ROM's cover may be a platform address (Android: content URI next to the ROM). */
private fun boxArtModel(value: String): Any = ArtworkPlatform.current.imageModel(value)

/** Best available wide background image. */
fun LibraryEntry.heroModel(): Any? {
    // Background removed by hand: none (the generated gradient shows), no cover as stand-in.
    if (item.heroUrl == null && MetaField.HERO in item.manual) return null
    return ArtworkFiles.model(item.heroUrl)
        ?: ArtworkFiles.model(item.coverUrl)
        ?: boxArtPath?.takeIf { MetaField.COVER !in item.manual }?.let(::boxArtModel)
}

/**
 * The still image, with the animated one laid over it once that is ready: the still one stays
 * until then (no placeholder flash), and remains if the animated one can't be loaded.
 */
@Composable
private fun WithAnimated(animated: Any?, title: String, modifier: Modifier, still: @Composable (Modifier) -> Unit) {
    if (animated == null) {
        still(modifier)
        return
    }
    Box(modifier) {
        still(Modifier.fillMaxSize())
        var ready by androidx.compose.runtime.remember(animated) { androidx.compose.runtime.mutableStateOf(false) }
        coil3.compose.AsyncImage(
            model = animated,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            onSuccess = { ready = true },
            modifier = Modifier.fillMaxSize().then(if (ready) Modifier else Modifier.alpha(0f)),
        )
    }
}

/** Image with a generated, title-based placeholder when no artwork exists (yet). */
@Composable
fun ArtworkImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showTitleOnPlaceholder: Boolean = true,
) {
    if (model == null) {
        TitlePlaceholder(title, modifier, showTitleOnPlaceholder)
        return
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = title,
        contentScale = contentScale,
        modifier = modifier,
        loading = { TitlePlaceholder(title, Modifier.fillMaxSize(), showTitle = false) },
        error = { TitlePlaceholder(title, Modifier.fillMaxSize(), showTitleOnPlaceholder) },
    )
}

/**
 * Transparent game logo: nothing is drawn while it loads (a coloured placeholder box would
 * flash), it fades in when ready and falls back to the title text if it can't be loaded.
 */
@Composable
fun LogoImage(url: String?, title: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = ArtworkFiles.model(url),
        contentDescription = title,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        modifier = modifier,
        loading = {},
        error = {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme.colors.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun TitlePlaceholder(title: String, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val hue = (title.hashCode().absoluteValue % 360).toFloat()
    val top = Color.hsl(hue, 0.45f, 0.32f)
    val bottom = Color.hsl((hue + 40f) % 360f, 0.5f, 0.16f)
    Box(modifier.background(Brush.linearGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
        if (showTitle) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 4,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** Images the platform makes itself: app icons, generated tiles and backdrops, cover colours. */
interface ArtworkPlatform {
    /** What the image loader gets for a stored path or platform address. */
    fun imageModel(value: String): Any = File(value)
    /** Icon of an installed app ([packageName]: the platform's app id). */
    suspend fun appIcon(packageName: String): ImageBitmap? = null
    /** Landscape tile of an app: its own banner, or one generated from its icon ([source]: box art of host apps). */
    suspend fun appTile(packageName: String?, source: Any?): ImageBitmap? = null
    /** Portrait cover generated from the icon of a local game without artwork. */
    suspend fun generatedCover(packageName: String): ImageBitmap? = null
    /** Wide backdrop generated from the icon of a local app or game without artwork. */
    suspend fun generatedHero(packageName: String): ImageBitmap? = null
    /** The dominant vibrant colour of an image. */
    suspend fun accentColor(model: Any): Color? = null

    companion object {
        /** Set once at start by the platform. */
        var current: ArtworkPlatform = object : ArtworkPlatform {}
    }
}

/** Small LRU cache (images and colours stay in memory while browsing). */
private class Lru<K : Any, V : Any>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
}

private val iconCache = Lru<String, ImageBitmap>(200)

/** Launcher icon of an installed app, loaded off the main thread and cached. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val bitmap by produceState(iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ArtworkPlatform.current.appIcon(packageName) }.getOrNull()?.also { iconCache.put(packageName, it) }
            }
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier) }
}

private val accentCache = Lru<Any, Color>(300)

/**
 * Publishes the dominant vibrant colour of [model] as the theme accent (when the user chose
 * "accent from cover"). Runs off the main thread; results are cached.
 */
@Composable
fun PublishAccentFrom(model: Any?) {
    val holder = LocalAccentHolder.current
    LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        accentCache.get(model)?.let { holder.coverAccent.value = it; return@LaunchedEffect }
        val color = withContext(Dispatchers.IO) {
            runCatching { ArtworkPlatform.current.accentColor(model) }.getOrNull()
        } ?: return@LaunchedEffect
        accentCache.put(model, color)
        holder.coverAccent.value = color
    }
}

private val tileCache = Lru<String, ImageBitmap>(80)

/** Model of an app tile set by the user (SteamGridDB URL or own image file). */
fun LibraryEntry.tileModel(): Any? = item.tileUrl?.let { if (it.startsWith("http")) it else File(it) }

/**
 * Landscape tile of an app for the start tab: the tile the user picked, the app's Android TV
 * banner, or one generated from its icon (host apps: their box art) on a background in the
 * icon's main colour.
 */
@Composable
fun AppTileImage(entry: LibraryEntry, modifier: Modifier = Modifier) {
    entry.tileModel()?.let {
        ArtworkImage(it, entry.item.title, modifier)
        return
    }
    val pkg = entry.localTarget?.packageName
    val source = if (pkg == null) entry.coverModel() else null
    val key = pkg ?: "remote:${entry.item.id}:$source"
    val bitmap by produceState(tileCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ArtworkPlatform.current.appTile(pkg, source) }.getOrNull()?.also { tileCache.put(key, it) }
            }
        }
    }
    val image = bitmap
    if (image == null) TitlePlaceholder(entry.item.title, modifier, showTitle = true)
    else Image(image, contentDescription = entry.item.title, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * Portrait cover of a game: its artwork, or, for a local Android game without any, one
 * generated from the app icon like the app tiles.
 */
@Composable
fun CoverImage(entry: LibraryEntry, modifier: Modifier = Modifier, animated: Boolean = false, showTitleOnPlaceholder: Boolean = true) {
    val model = entry.coverModel()
    val pkg = entry.localTarget?.packageName
    if (model != null || pkg == null) {
        WithAnimated(if (animated) entry.animatedCoverModel() else null, entry.item.title, modifier) {
            ArtworkImage(model, entry.item.title, it, showTitleOnPlaceholder = showTitleOnPlaceholder)
        }
        return
    }
    val key = "cover:$pkg"
    val bitmap by produceState(tileCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ArtworkPlatform.current.generatedCover(pkg) }
                    .getOrNull()?.also { tileCache.put(key, it) }
            }
        }
    }
    val image = bitmap
    if (image == null) TitlePlaceholder(entry.item.title, modifier, showTitleOnPlaceholder)
    else Image(image, contentDescription = entry.item.title, contentScale = ContentScale.Crop, modifier = modifier)
}

/**
 * Wide background of an entry. A local app or game without any artwork gets a generated one:
 * a gradient in the icon's colours with the icon large on the right, softly glowing.
 */
@Composable
fun HeroImage(entry: LibraryEntry, modifier: Modifier = Modifier, animated: Boolean = false) {
    val model = entry.heroModel()
    val pkg = entry.localTarget?.packageName
    if (model != null || pkg == null) {
        WithAnimated(if (animated) entry.animatedHeroModel() else null, entry.item.title, modifier) {
            ArtworkImage(model, entry.item.title, it, ContentScale.Crop, showTitleOnPlaceholder = false)
        }
        return
    }
    val key = "hero:$pkg"
    val bitmap by produceState(tileCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ArtworkPlatform.current.generatedHero(pkg) }
                    .getOrNull()?.also { tileCache.put(key, it) }
            }
        }
    }
    val image = bitmap
    if (image == null) TitlePlaceholder(entry.item.title, modifier, showTitle = false)
    else Image(image, contentDescription = entry.item.title, contentScale = ContentScale.Crop, modifier = modifier)
}

