package io.github.thecodingdad.moonlightlauncher.ui.input

import androidx.compose.runtime.Composable

/**
 * Swallows every gamepad shortcut while composed. Modal overlays place this first, then
 * register their own [PadAction]s, so nothing leaks to the screen underneath.
 */
@Composable
fun PadBlocker() {
    PadButton.entries.forEach { PadAction(it) {} }
}
