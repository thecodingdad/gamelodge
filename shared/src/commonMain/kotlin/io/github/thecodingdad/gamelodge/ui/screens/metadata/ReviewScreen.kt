package io.github.thecodingdad.gamelodge.ui.screens.metadata

import io.github.thecodingdad.gamelodge.ui.components.fadingEdges
import io.github.thecodingdad.gamelodge.ui.components.mouseDragScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.ui.components.ButtonHint
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.shell.ScreenHints
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.resources.*

/** Uncertain matches: A = choose the right game, X = the match is correct. */
@Composable
fun ReviewScreen(items: List<LibraryEntry>) {
    val metadata = io.github.thecodingdad.gamelodge.LocalContainer.current.metadata
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var fixing by remember { mutableStateOf<LibraryItemEntity?>(null) }

    PadAction(PadButton.X) { focused?.let { e -> scope.launch { metadata.confirm(e.item.id) } } }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(Res.string.metadata_choose_game)),
            ButtonHint(PadButton.X, stringResource(Res.string.metadata_confirm), onClick = { focused?.let { e -> scope.launch { metadata.confirm(e.item.id) } } }),
            ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
        ),
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        io.github.thecodingdad.gamelodge.ui.screens.settings.PageTitle(stringResource(Res.string.metadata_review_list))
        Text(stringResource(Res.string.metadata_review_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.padding(vertical = 8.dp))
        if (items.isEmpty()) {
            Text(stringResource(Res.string.metadata_review_done), style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.success)
            return@Column
        }
        val listState = io.github.thecodingdad.gamelodge.ui.input.rememberStickLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusRestorer().fadingEdges(listState).mouseDragScroll(listState),
        ) {
            items(items, key = { it.item.id }) { entry ->
                FocusSurface(
                    onClick = { fixing = entry.item },
                    onFocused = { focused = entry },
                    onLongClick = { scope.launch { metadata.confirm(entry.item.id) } },
                    modifier = Modifier.fillMaxWidth().restoreFocusIf(entry === items.first()),
                    focusScale = 1.02f,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        io.github.thecodingdad.gamelodge.ui.components.CoverImage(entry, Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(entry.item.title, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
                            Text(
                                stringResource(Res.string.metadata_matched_as, entry.item.metadataQuery ?: entry.item.title, (entry.item.matchConfidence * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted,
                            )
                        }
                    }
                }
            }
        }
    }
    FixMetadataDialog(fixing) { fixing = null }
}
