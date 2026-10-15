package io.github.thecodingdad.moonlightlauncher.ui.input

import androidx.compose.ui.input.key.KeyEvent

/** How often a held key has repeated (0 for the first press). */
expect val KeyEvent.repeatCount: Int
