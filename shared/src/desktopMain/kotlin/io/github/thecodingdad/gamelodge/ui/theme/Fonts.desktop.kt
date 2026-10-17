package io.github.thecodingdad.gamelodge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.thecodingdad.gamelodge.resources.Res
import io.github.thecodingdad.gamelodge.resources.roboto_bold
import io.github.thecodingdad.gamelodge.resources.roboto_medium
import io.github.thecodingdad.gamelodge.resources.roboto_regular
import org.jetbrains.compose.resources.Font

@Composable
actual fun launcherFontFamily(): FontFamily = FontFamily(
    Font(Res.font.roboto_regular, FontWeight.Normal),
    Font(Res.font.roboto_medium, FontWeight.Medium),
    Font(Res.font.roboto_bold, FontWeight.Bold),
)
