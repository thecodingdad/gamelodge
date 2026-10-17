package io.github.thecodingdad.gamelodge.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.File

/** App icons and TV banners from the package manager; generated tiles drawn with the Android canvas. */
class AndroidArtwork(private val context: Context) : ArtworkPlatform {
    override fun imageModel(value: String): Any = if (value.startsWith("content://")) android.net.Uri.parse(value) else File(value)

    override suspend fun appIcon(packageName: String): ImageBitmap? =
        context.packageManager.getApplicationIcon(packageName).toBitmap(192, 192).asImageBitmap()

    override suspend fun appTile(packageName: String?, source: Any?): ImageBitmap? {
        val pkg = packageName
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

    override suspend fun generatedCover(packageName: String): ImageBitmap? =
        generatedTile(context.packageManager.getApplicationIcon(packageName).toBitmap(256, 256), 400, 600)

    override suspend fun generatedHero(packageName: String): ImageBitmap? =
        generatedHero(context.packageManager.getApplicationIcon(packageName))

    override suspend fun accentColor(model: Any): Color? {
        val request = ImageRequest.Builder(context).data(model).size(96).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap: Bitmap = result.image.toBitmap()
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
        return swatch?.rgb?.let { Color(it) }
    }
}

/** 16:9 backdrop: dark-to-colour gradient, a soft glow and the icon on the right. */
private fun generatedHero(drawable: android.graphics.drawable.Drawable): ImageBitmap {
    val w = 1280
    val h = 720
    val icon = drawable.toBitmap(384, 384)
    // Adaptive icons: only the motif, without the launcher's background shape. The foreground
    // layer is 108 units wide with the visible part in the middle 72.
    val glyph = (drawable as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground?.let { fg ->
        val full = fg.toBitmap(576, 576)
        Bitmap.createBitmap(full, 96, 96, 384, 384)
    } ?: icon
    val palette = Palette.from(icon).generate()
    val base = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: android.graphics.Color.DKGRAY
    val deep = palette.darkMutedSwatch?.rgb ?: palette.darkVibrantSwatch?.rgb
        ?: androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.BLACK, 0.7f)
    val dark = androidx.core.graphics.ColorUtils.blendARGB(deep, android.graphics.Color.BLACK, 0.55f)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    // The left side stays dark (title and info sit there), the colour builds up to the right.
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, w.toFloat(), h.toFloat(),
        intArrayOf(dark, dark, androidx.core.graphics.ColorUtils.blendARGB(base, dark, 0.45f)),
        floatArrayOf(0f, 0.35f, 1f), android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    // Upper right: the rows at the bottom must not cover it.
    val cx = w * 0.72f
    val cy = h * 0.3f
    val size = h * 0.36f
    // Soft glow in the icon's colour behind it.
    paint.shader = android.graphics.RadialGradient(
        cx, cy, size * 1.4f,
        intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(base, 150), androidx.core.graphics.ColorUtils.setAlphaComponent(base, 0)),
        null, android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawCircle(cx, cy, size * 1.4f, paint)
    paint.shader = null
    val scale = size / maxOf(glyph.width, glyph.height)
    val iw = glyph.width * scale
    val ih = glyph.height * scale
    val rect = android.graphics.RectF(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2)
    // Soft shadow in the motif's own shape.
    val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        color = android.graphics.Color.BLACK
        alpha = 110
        maskFilter = android.graphics.BlurMaskFilter(size * 0.06f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawBitmap(glyph.extractAlpha(), null, android.graphics.RectF(rect).apply { offset(0f, size * 0.04f) }, shadow)
    canvas.drawBitmap(glyph, null, rect, paint)
    return out.asImageBitmap()
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
