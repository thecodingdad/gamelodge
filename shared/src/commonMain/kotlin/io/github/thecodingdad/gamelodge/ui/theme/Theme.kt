package io.github.thecodingdad.gamelodge.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thecodingdad.gamelodge.data.settings.CardSize
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.ThemeMode

/**
 * Colours beyond Material's scheme that the launcher design needs. The accent is read from a
 * state: when it changes (accent from the focused cover, animated) only what shows it redraws,
 * not the whole tree (a new colours object per animation frame made every focus move stutter).
 */
@Stable
class LauncherColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    private val accentState: State<Color>,
    val focusRing: Color,
    val scrim: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
) {
    /** Own colour for accented texts (group headers); null: derived from the accent. */
    internal var textAccentOverride: Color? = null
    val accent: Color get() = accentState.value
    val onAccent: Color get() = onColor(accentState.value)
    /** Accented texts: the chosen colour, or the accent made readable on the background. */
    val accentText: Color get() = textAccentOverride ?: readable(accentState.value, background, isDark)
}

/** [c] lightened (dark themes) or darkened (light theme) until it reads well on [background]. */
internal fun readable(c: Color, background: Color, dark: Boolean): Color {
    var color = c
    repeat(10) {
        val contrast = (maxOf(color.luminance(), background.luminance()) + 0.05f) / (minOf(color.luminance(), background.luminance()) + 0.05f)
        if (contrast >= 4.5f) return color
        color = if (dark) androidx.compose.ui.graphics.lerp(color, Color.White, 0.2f) else androidx.compose.ui.graphics.lerp(color, Color.Black, 0.2f)
    }
    return color
}

@Immutable
data class LauncherDimens(
    val coverWidth: Dp,
    val iconTile: Dp,
    val screenPadding: Dp = 32.dp,
    val cornerRadius: Dp = 14.dp,
)

val LocalLauncherColors = staticCompositionLocalOf<LauncherColors> { error("No LauncherTheme") }
val LocalLauncherDimens = staticCompositionLocalOf { LauncherDimens(150.dp, 112.dp) }
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Accent taken from the focused cover; screens write it, the theme animates towards it. */
class AccentHolder {
    var coverAccent = mutableStateOf<Color?>(null)
}
val LocalAccentHolder = staticCompositionLocalOf { AccentHolder() }

object LauncherTheme {
    val colors: LauncherColors @Composable get() = LocalLauncherColors.current
    val dimens: LauncherDimens @Composable get() = LocalLauncherDimens.current
}

private fun palette(mode: ThemeMode, accent: State<Color>): LauncherColors = when (mode) {
    ThemeMode.DARK -> LauncherColors(
        background = Color(0xFF0E1015), surface = Color(0xFF171A21), surfaceRaised = Color(0xFF222632),
        onSurface = Color(0xFFF2F3F7), onSurfaceMuted = Color(0xFFA3A8B8),
        accentState = accent, focusRing = Color.White, scrim = Color(0xCC000000),
        success = Color(0xFF4CD787), warning = Color(0xFFFFC14D), danger = Color(0xFFFF5C6C), isDark = true,
    )
    ThemeMode.BLACK -> LauncherColors(
        background = Color.Black, surface = Color(0xFF0B0B0D), surfaceRaised = Color(0xFF17171B),
        onSurface = Color(0xFFF2F3F7), onSurfaceMuted = Color(0xFF9A9EAC),
        accentState = accent, focusRing = Color.White, scrim = Color(0xE6000000),
        success = Color(0xFF4CD787), warning = Color(0xFFFFC14D), danger = Color(0xFFFF5C6C), isDark = true,
    )
    ThemeMode.LIGHT -> LauncherColors(
        background = Color(0xFFF3F4F8), surface = Color.White, surfaceRaised = Color(0xFFE7E9F0),
        onSurface = Color(0xFF14161C), onSurfaceMuted = Color(0xFF5B6070),
        accentState = accent, focusRing = Color(0xFF14161C), scrim = Color(0x99000000),
        success = Color(0xFF178A4C), warning = Color(0xFFB57800), danger = Color(0xFFD02B3C), isDark = false,
    )
}

private fun onColor(c: Color) = if (c.luminance() > 0.5f) Color(0xFF101114) else Color.White

/** Every text style in [family]. */
private fun Typography.withFamily(family: androidx.compose.ui.text.font.FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family), displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family), headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family), headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family), titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family), bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family), bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family), labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

private val LauncherTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

fun dimensFor(cardSize: CardSize): LauncherDimens = when (cardSize) {
    CardSize.SMALL -> LauncherDimens(coverWidth = 124.dp, iconTile = 96.dp)
    CardSize.MEDIUM -> LauncherDimens(coverWidth = 150.dp, iconTile = 112.dp)
    CardSize.LARGE -> LauncherDimens(coverWidth = 184.dp, iconTile = 132.dp)
}

/**
 * Density for a window of [widthPx] x [heightPx]: [density] (already scaled by the user), lowered
 * on small landscape screens so the top tabs and the status icons fit next to each other (about
 * 780 dp) and the home tab shows at least one full row of covers (header and hero take about
 * 255 dp above it).
 */
fun fittedDensity(density: Float, widthPx: Int, heightPx: Int, cardSize: CardSize): Float {
    if (widthPx <= 0 || heightPx <= 0 || widthPx <= heightPx) return density
    val minHeight = 255f + dimensFor(cardSize).coverWidth.value * 1.5f
    return minOf(density, heightPx / minHeight, widthPx / 780f)
}

@Composable
fun LauncherTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val accentHolder = remember { AccentHolder() }
    val fixedAccent = Color(settings.accentColor)
    val targetAccent = if (settings.accentMode == io.github.thecodingdad.gamelodge.data.settings.AccentMode.COVER) {
        accentHolder.coverAccent.value ?: fixedAccent
    } else fixedAccent
    val accent = animateColorAsState(targetAccent, tween(if (settings.reduceMotion) 0 else 600), label = "accent")

    // One colours object per theme: the animated accent is read through its state.
    val colors = remember(settings.themeMode, accent, settings.accentTextColor) {
        palette(settings.themeMode, accent).apply { textAccentOverride = settings.accentTextColor?.let { Color(it) } }
    }
    val dimens = dimensFor(settings.homeLayout.cardSize)
    // Material's scheme keeps the fixed accent: the cover accent would re-theme everything per frame.
    val scheme = remember(colors, fixedAccent) { if (colors.isDark) {
        darkColorScheme(
            primary = fixedAccent, onPrimary = onColor(fixedAccent), background = colors.background,
            surface = colors.surface, onSurface = colors.onSurface, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.onSurfaceMuted, onBackground = colors.onSurface, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = fixedAccent, onPrimary = onColor(fixedAccent), background = colors.background,
            surface = colors.surface, onSurface = colors.onSurface, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.onSurfaceMuted, onBackground = colors.onSurface, error = colors.danger,
        )
    } }

    CompositionLocalProvider(
        LocalLauncherColors provides colors,
        LocalLauncherDimens provides dimens,
        LocalReduceMotion provides settings.reduceMotion,
        LocalAccentHolder provides accentHolder,
        io.github.thecodingdad.gamelodge.ui.components.LocalTileStyle provides io.github.thecodingdad.gamelodge.ui.components.TileStyle(
            settings.tileCorners, settings.focusZoom, settings.focusGlow, settings.dimNotInstalled,
        ),
    ) {
        val family = launcherFontFamily()
        val typography = androidx.compose.runtime.remember(family) { LauncherTypography.withFamily(family) }
        MaterialTheme(colorScheme = scheme, typography = typography) {
            // Material 3 leaves the default content colour black; icons without an explicit
            // tint would be nearly invisible on the dark surfaces.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface, content = content)
        }
    }
}
