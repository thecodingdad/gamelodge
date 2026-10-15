package io.github.thecodingdad.moonlightlauncher.ui.screens.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * Detail pages of a list (the filter / row the detail was opened from, in its order) to page
 * through. Swiping moves the pages with the finger, the neighbour is visible right away (a
 * vertical scroll of the page is never taken as a swipe); the controller ([content]'s browse
 * callback, right stick) crossfades. Stops at both ends.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DetailBrowser(
    startId: String,
    ids: List<String>?,
    onShown: (String) -> Unit,
    content: @Composable (id: String, browse: ((Int) -> Unit)?, active: Boolean) -> Unit,
) {
    val list = ids?.takeIf { startId in it } ?: listOf(startId)
    val pager = rememberPagerState(initialPage = list.indexOf(startId)) { list.size }
    val scope = rememberCoroutineScope()
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(pager, list) {
        snapshotFlow { pager.settledPage }.collect { list.getOrNull(it)?.let(onShown) }
    }
    val browse: ((Int) -> Unit)? = if (list.size > 1) {
        { delta ->
            val target = pager.currentPage + delta
            if (target in list.indices && !pager.isScrollInProgress) scope.launch {
                alpha.animateTo(0f, tween(110))
                pager.scrollToPage(target)
                alpha.animateTo(1f, tween(160))
            }
        }
    } else null

    HorizontalPager(
        state = pager,
        beyondViewportPageCount = 1,
        key = { list[it] },
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
    ) { page ->
        val active = page == pager.currentPage
        Box(
            Modifier
                .fillMaxSize()
                // Neighbour pages are never reached by D-pad focus search.
                .focusProperties { if (!active) enter = { FocusRequester.Cancel } }
                .focusGroup(),
        ) { content(list[page], browse, active) }
    }
}
