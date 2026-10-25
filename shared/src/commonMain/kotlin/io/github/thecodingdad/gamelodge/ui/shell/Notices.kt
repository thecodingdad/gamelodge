package io.github.thecodingdad.gamelodge.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A short message at the bottom of the screen, e.g. when something running in the background failed. */
data class Notice(val text: StringResource, val args: List<Any> = emptyList(), val id: Long = System.nanoTime())

object Notices {
    private val _current = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = _current.asStateFlow()

    fun show(text: StringResource, vararg args: Any) {
        _current.value = Notice(text, args.toList())
    }

    internal fun clear(notice: Notice) {
        _current.compareAndSet(notice, null)
    }
}

/** Shows [Notices] above everything else for a few seconds. */
@Composable
fun NoticeHost() {
    val notice by Notices.current.collectAsState()
    LaunchedEffect(notice) {
        val shown = notice ?: return@LaunchedEffect
        delay(NOTICE_MS)
        Notices.clear(shown)
    }
    Box(Modifier.fillMaxSize().padding(bottom = 72.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(notice != null, enter = fadeIn(), exit = fadeOut()) {
            val n = notice ?: return@AnimatedVisibility
            val colors = LauncherTheme.colors
            Text(
                if (n.args.isEmpty()) stringResource(n.text) else stringResource(n.text, *n.args.toTypedArray()),
                style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                modifier = Modifier.widthIn(max = 640.dp).background(colors.surfaceRaised, RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}

private const val NOTICE_MS = 4_000L
