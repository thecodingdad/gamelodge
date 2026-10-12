package io.github.thecodingdad.moonlightlauncher.ui.components

import android.graphics.Bitmap
import android.util.LruCache
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.ui.theme.LocalAccentHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/** Best available portrait cover for an entry: metadata cover, then host box art. */
fun LibraryEntry.coverModel(animated: Boolean = false): Any? =
    (if (animated) item.animatedCoverUrl else null)
        ?: item.coverUrl
        ?: boxArtPath?.let(::File)

/** Best available wide background image. */
fun LibraryEntry.heroModel(animated: Boolean = false): Any? =
    (if (animated) item.animatedHeroUrl else null)
        ?: item.heroUrl
        ?: item.coverUrl
        ?: boxArtPath?.let(::File)

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
        model = url,
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

private val iconCache = LruCache<String, ImageBitmap>(200)

/** Launcher icon of an installed app, loaded off the main thread and cached. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap()
                }.getOrNull()?.also { iconCache.put(packageName, it) }
            }
        }
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = modifier) }
}

private val accentCache = LruCache<Any, Color>(300)

/**
 * Publishes the dominant vibrant colour of [model] as the theme accent (when the user chose
 * "accent from cover"). Runs off the main thread; results are cached.
 */
@Composable
fun PublishAccentFrom(model: Any?) {
    val context = LocalContext.current
    val holder = LocalAccentHolder.current
    LaunchedEffect(model) {
        if (model == null) return@LaunchedEffect
        accentCache.get(model)?.let { holder.coverAccent.value = it; return@LaunchedEffect }
        val color = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(model).size(96).allowHardware(false).build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
            val bitmap: Bitmap = result.image.toBitmap()
            val palette = Palette.from(bitmap).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
            swatch?.rgb?.let { Color(it) }
        } ?: return@LaunchedEffect
        accentCache.put(model, color)
        holder.coverAccent.value = color
    }
}

private val tileCache = LruCache<String, ImageBitmap>(80)

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
    val context = LocalContext.current
    val pkg = entry.localTarget?.packageName
    val source = if (pkg == null) entry.coverModel() else null
    val key = pkg ?: "remote:${entry.item.id}:$source"
    val bitmap by produceState(tileCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { buildAppTile(context, pkg, source) }.getOrNull()?.also { tileCache.put(key, it) }
            }
        }
    }
    val image = bitmap
    if (image == null) TitlePlaceholder(entry.item.title, modifier, showTitle = true)
    else Image(image, contentDescription = entry.item.title, contentScale = ContentScale.Crop, modifier = modifier)
}

private suspend fun buildAppTile(context: android.content.Context, pkg: String?, source: Any?): ImageBitmap? {
    if (pkg != null) {
        val pm = context.packageManager
        // Apps made for Android TV bring their own 16:9 banner.
        val banner = runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
            ?: pm.getLeanbackLaunchIntentForPackage(pkg)?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() }
        if (banner != null) return banner.toBitmap(640, 360).asImageBitmap()
        return generatedTile(pm.getApplicationIcon(pkg).toBitmap(256, 256))
    }
    source ?: return null
    val request = ImageRequest.Builder(context).data(source).size(512).allowHardware(false).build()
    val art = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    return generatedTile(art)
}

/**
 * Portrait cover of a game: its artwork, or, for a local Android game without any, one
 * generated from the app icon like the app tiles.
 */
@Composable
fun CoverImage(entry: LibraryEntry, modifier: Modifier = Modifier, animated: Boolean = false, showTitleOnPlaceholder: Boolean = true) {
    val model = entry.coverModel(animated)
    val pkg = entry.localTarget?.packageName
    if (model != null || pkg == null) {
        ArtworkImage(model, entry.item.title, modifier, showTitleOnPlaceholder = showTitleOnPlaceholder)
        return
    }
    val context = LocalContext.current
    val key = "cover:$pkg"
    val bitmap by produceState(tileCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { generatedTile(context.packageManager.getApplicationIcon(pkg).toBitmap(256, 256), 400, 600) }
                    .getOrNull()?.also { tileCache.put(key, it) }
            }
        }
    }
    val image = bitmap
    if (image == null) TitlePlaceholder(entry.item.title, modifier, showTitleOnPlaceholder)
    else Image(image, contentDescription = entry.item.title, contentScale = ContentScale.Crop, modifier = modifier)
}

/** Gradient in the image's main colour with the image (icon / box art) centred; 16:9 by default. */
private fun generatedTile(icon: Bitmap, w: Int = 640, h: Int = 360): ImageBitmap {
    val palette = Palette.from(icon).generate()
    val base = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: android.graphics.Color.DKGRAY
    val dark = androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.BLACK, 0.5f)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    paint.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), base, dark, android.graphics.Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    val target = minOf(w, h) * 0.6f
    val scale = target / maxOf(icon.width, icon.height)
    val iw = icon.width * scale
    val ih = icon.height * scale
    canvas.drawBitmap(icon, null, android.graphics.RectF((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2), paint)
    return out.asImageBitmap()
}
