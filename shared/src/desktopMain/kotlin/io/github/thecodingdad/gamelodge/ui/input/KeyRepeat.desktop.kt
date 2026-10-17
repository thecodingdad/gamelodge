package io.github.thecodingdad.gamelodge.ui.input

import androidx.compose.ui.input.key.KeyEvent

/** AWT reports no repeat count; repeats are counted by the gamepad layer where it matters. */
actual val KeyEvent.repeatCount: Int get() = 0
