package io.github.thecodingdad.moonlightlauncher.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import io.github.thecodingdad.moonlightlauncher.data.settings.CardSize
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.data.settings.ThemeMode

/** Colours beyond Material's scheme that the launcher design needs. */
@Immutable
data class LauncherColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val focusRing: Color,
    val scrim: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

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

private fun palette(mode: ThemeMode, accent: Color): LauncherColors = when (mode) {
    ThemeMode.DARK -> LauncherColors(
        background = Color(0xFF0E1015), surface = Color(0xFF171A21), surfaceRaised = Color(0xFF222632),
        onSurface = Color(0xFFF2F3F7), onSurfaceMuted = Color(0xFFA3A8B8),
        accent = accent, onAccent = onColor(accent), focusRing = Color.White, scrim = Color(0xCC000000),
        success = Color(0xFF4CD787), warning = Color(0xFFFFC14D), danger = Color(0xFFFF5C6C), isDark = true,
    )
    ThemeMode.BLACK -> LauncherColors(
        background = Color.Black, surface = Color(0xFF0B0B0D), surfaceRaised = Color(0xFF17171B),
        onSurface = Color(0xFFF2F3F7), onSurfaceMuted = Color(0xFF9A9EAC),
        accent = accent, onAccent = onColor(accent), focusRing = Color.White, scrim = Color(0xE6000000),
        success = Color(0xFF4CD787), warning = Color(0xFFFFC14D), danger = Color(0xFFFF5C6C), isDark = true,
    )
    ThemeMode.LIGHT -> LauncherColors(
        background = Color(0xFFF3F4F8), surface = Color.White, surfaceRaised = Color(0xFFE7E9F0),
        onSurface = Color(0xFF14161C), onSurfaceMuted = Color(0xFF5B6070),
        accent = accent, onAccent = onColor(accent), focusRing = Color(0xFF14161C), scrim = Color(0x99000000),
        success = Color(0xFF178A4C), warning = Color(0xFFB57800), danger = Color(0xFFD02B3C), isDark = false,
    )
}

private fun onColor(c: Color) = if (c.luminance() > 0.5f) Color(0xFF101114) else Color.White

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

@Composable
fun LauncherTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val accentHolder = remember { AccentHolder() }
    val fixedAccent = Color(settings.accentColor)
    val targetAccent = if (settings.accentMode == io.github.thecodingdad.moonlightlauncher.data.settings.AccentMode.COVER) {
        accentHolder.coverAccent.value ?: fixedAccent
    } else fixedAccent
    val accent by animateColorAsState(targetAccent, tween(if (settings.reduceMotion) 0 else 600), label = "accent")

    val colors = palette(settings.themeMode, accent)
    val dimens = when (settings.cardSize) {
        CardSize.SMALL -> LauncherDimens(coverWidth = 124.dp, iconTile = 96.dp)
        CardSize.MEDIUM -> LauncherDimens(coverWidth = 150.dp, iconTile = 112.dp)
        CardSize.LARGE -> LauncherDimens(coverWidth = 184.dp, iconTile = 132.dp)
    }
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.onSurface, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.onSurfaceMuted, onBackground = colors.onSurface, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.onSurface, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.onSurfaceMuted, onBackground = colors.onSurface, error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalLauncherColors provides colors,
        LocalLauncherDimens provides dimens,
        LocalReduceMotion provides settings.reduceMotion,
        LocalAccentHolder provides accentHolder,
    ) {
        MaterialTheme(colorScheme = scheme, typography = LauncherTypography) {
            // Material 3 leaves the default content colour black; icons without an explicit
            // tint would be nearly invisible on the dark surfaces.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface, content = content)
        }
    }
}
