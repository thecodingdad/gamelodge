package io.github.thecodingdad.moonlightlauncher.ui.input

import androidx.compose.ui.input.key.KeyEvent

actual val KeyEvent.repeatCount: Int get() = nativeKeyEvent.repeatCount
