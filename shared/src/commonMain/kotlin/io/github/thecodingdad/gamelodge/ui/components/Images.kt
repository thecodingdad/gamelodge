package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.MetaField
import io.github.thecodingdad.gamelodge.ui.theme.LocalAccentHolder
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
fun LogoImage(url: String?, title: String, modifier: Modifier = Modifier, alignment: Alignment = Alignment.CenterStart) {
    SubcomposeAsyncImage(
        model = ArtworkFiles.model(url),
        contentDescription = title,
        contentScale = ContentScale.Fit,
        alignment = alignment,
        modifier = modifier,
        loading = {},
        error = {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.onSurface,
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

/** How a generated tile places its image: whole on a gradient, or filling the tile aligned top / centre / bottom. */
enum class TileFit { FIT, TOP, CENTER, BOTTOM }

/** A tile made by the platform: where it came from, how it was laid out, its PNG file. */
class LocalTile(val source: io.github.thecodingdad.gamelodge.metadata.ArtworkSource, val file: File, val fit: TileFit? = null)

/**
 * Source rectangle of a [w]×[h] image that fills a tile of [ratio] (width : height): the whole
 * width or height, the rest cut off at the side [fit] keeps (top / centre / bottom; sideways centred).
 */
fun fillCrop(w: Int, h: Int, ratio: Float, fit: TileFit): IntArray {
    return if (w.toFloat() / h > ratio) {
        val cw = (h * ratio).toInt()
        val x = (w - cw) / 2
        intArrayOf(x, 0, x + cw, h)
    } else {
        val ch = (w / ratio).toInt()
        val y = when (fit) {
            TileFit.TOP, TileFit.FIT -> 0
            TileFit.CENTER -> (h - ch) / 2
            TileFit.BOTTOM -> h - ch
        }
        intArrayOf(0, y, w, y + ch)
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
    /**
     * Landscape tiles ([ratio] width : height) the platform can make, stored as PNG files in
     * [dir] (named after [name]) to be offered in the picker: an app's Android TV banner, and
     * tiles generated from its icon ([packageName]) and from [source] (cover / box art) in every
     * [TileFit].
     */
    suspend fun localTiles(packageName: String?, source: Any?, ratio: Float, dir: File, name: String): List<LocalTile> = emptyList()
    /** Portrait cover generated from the icon of a local game without artwork. */
    suspend fun generatedCover(packageName: String): ImageBitmap? = null
    /** Wide backdrop generated from the icon of a local app or game without artwork. */
    suspend fun generatedHero(packageName: String, portrait: Boolean = false): ImageBitmap? = null
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
private const val ACCENT_SETTLE_MS = 300L

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
        // Only once the focus rests: moving on cancels this (no image analysis and colour
        // animation for every card passed on the way).
        kotlinx.coroutines.delay(ACCENT_SETTLE_MS)
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
 * Wide background of an entry. Without a background image the cover stands in, softly blurred
 * (stretched sharp it looks pixelated). A local app or game without any artwork gets a generated
 * one in the shape of the area: a gradient in the icon's colours with the icon large and glowing.
 */
@Composable
fun HeroImage(entry: LibraryEntry, modifier: Modifier = Modifier, animated: Boolean = false) {
    val model = entry.heroModel()
    val pkg = entry.localTarget?.packageName
    if (model != null || pkg == null) {
        val coverOnly = model != null && ArtworkFiles.model(entry.item.heroUrl) == null
        WithAnimated(if (animated) entry.animatedHeroModel() else null, entry.item.title, modifier) {
            if (coverOnly) BlurredCover(model, entry.item.title, it)
            else ArtworkImage(model, entry.item.title, it, ContentScale.Crop, showTitleOnPlaceholder = false)
        }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // Generated in the area's aspect ratio (landscape or portrait), so the icon is never cut off.
        val portrait = maxHeight > maxWidth
        val key = "hero:$pkg:${if (portrait) "p" else "l"}"
        val bitmap by produceState(tileCache.get(key), key) {
            if (value == null) {
                value = withContext(Dispatchers.IO) {
                    runCatching { ArtworkPlatform.current.generatedHero(pkg, portrait) }
                        .getOrNull()?.also { tileCache.put(key, it) }
                }
            }
        }
        val image = bitmap
        if (image == null) TitlePlaceholder(entry.item.title, Modifier.fillMaxSize(), showTitle = false)
        else Image(image, contentDescription = entry.item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A game tile in [format]. Landscape and square use their own image when there is one; else
 * the background with the logo on it, else the whole cover on its blurred self (apps: their
 * TV banner or generated tile; local games without artwork: a tile generated from the icon).
 */
@Composable
fun TileImage(
    entry: LibraryEntry,
    format: io.github.thecodingdad.gamelodge.data.settings.TileFormat,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    showTitleOnPlaceholder: Boolean = true,
) {
    val landscape = format == io.github.thecodingdad.gamelodge.data.settings.TileFormat.LANDSCAPE
    if (format == io.github.thecodingdad.gamelodge.data.settings.TileFormat.PORTRAIT) {
        CoverImage(entry, modifier, animated, showTitleOnPlaceholder)
        return
    }
    val title = entry.item.title
    ArtworkFiles.model(if (landscape) entry.item.tileUrl else entry.item.squareUrl)?.let {
        ArtworkImage(it, title, modifier, ContentScale.Crop, showTitleOnPlaceholder = false)
        return
    }
    if (landscape && entry.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP) {
        AppTileImage(entry, modifier)
        return
    }
    val hero = ArtworkFiles.model(entry.item.heroUrl)
    val logo = entry.item.logoUrl
    if (hero != null && logo != null) {
        Box(modifier) {
            ArtworkImage(hero, title, Modifier.fillMaxSize(), ContentScale.Crop, showTitleOnPlaceholder = false)
            Box(Modifier.fillMaxSize().background(Color(0x40000000)))
            LogoImage(
                logo, title, Modifier.align(Alignment.Center).fillMaxSize(if (landscape) 0.7f else 0.78f).padding(4.dp),
                alignment = Alignment.Center,
            )
        }
        return
    }
    val cover = entry.coverModel()
    if (cover != null) {
        Box(modifier) {
            BlurredCover(cover, title, Modifier.fillMaxSize())
            CoverImage(
                entry, Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(2f / 3f),
                animated = animated, showTitleOnPlaceholder = false,
            )
        }
        return
    }
    if (entry.localTarget?.packageName != null) {
        AppTileImage(entry, modifier)
        return
    }
    TitlePlaceholder(title, modifier, showTitleOnPlaceholder)
}

/** A cover as a calm full-area background: loaded tiny, scaled up smoothly and blurred. */
@Composable
private fun BlurredCover(model: Any, title: String, modifier: Modifier) {
    val request = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
        .data(model).size(96).build()
    Box(modifier.background(io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.background)) {
        coil3.compose.AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            modifier = Modifier.fillMaxSize()
                .blur(28.dp, BlurredEdgeTreatment.Rectangle)
                .alpha(0.75f),
        )
    }
}

