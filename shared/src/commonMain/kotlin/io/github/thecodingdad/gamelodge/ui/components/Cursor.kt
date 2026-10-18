package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/** The mouse pointer turns into a hand over something clickable ([enabled]). */
fun Modifier.handCursor(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this
