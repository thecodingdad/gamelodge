package io.github.thecodingdad.moonlightlauncher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** The launcher's typeface: Roboto everywhere (the system's on Android, bundled on the desktop). */
@Composable
expect fun launcherFontFamily(): FontFamily
