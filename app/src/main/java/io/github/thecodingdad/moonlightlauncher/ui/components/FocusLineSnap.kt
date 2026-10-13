package io.github.thecodingdad.moonlightlauncher.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Bring-into-view that never scrolls; for lists/grids that position the focused line themselves. */
@OptIn(ExperimentalFoundationApi::class)
object NoScrollBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * Keeps the focused line of a grid at the top (console style), so every line sits at the same
 * height instead of scrolling "just enough". Call the returned function with the grid item
 * index to show at the top (the focused item, or the header above its line). Use together with
 * [NoScrollBringIntoView] on the grid. Only acts in controller mode.
 */
@Composable
fun rememberFocusLineSnap(state: LazyGridState): (Int) -> Unit {
    var target by remember { mutableStateOf<Int?>(null) }
    var snapped by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        val index = target ?: return@LaunchedEffect
        // First focus (restored position) jumps, later moves glide.
        if (snapped) state.animateScrollToItem(index) else state.scrollToItem(index)
        snapped = true
    }
    // Touch scrolls freely: a finger landing on a card must not pull its line to the top.
    val controller by androidx.compose.runtime.rememberUpdatedState(isControllerMode())
    return remember { { index -> if (controller) target = index } }
}
