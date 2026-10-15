package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi

/** System back (Android back gesture / B, Esc on the desktop) while [enabled]. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) =
    androidx.compose.ui.backhandler.BackHandler(enabled, onBack)
