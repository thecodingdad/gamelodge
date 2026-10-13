package io.github.thecodingdad.moonlightlauncher.ui.screens.metadata

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryItemEntity
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import kotlinx.coroutines.launch

/** Uncertain matches: A = choose the right game, X = the match is correct. */
@Composable
fun ReviewScreen(items: List<LibraryEntry>) {
    val metadata = LocalContext.current.container.metadata
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<LibraryEntry?>(null) }
    var fixing by remember { mutableStateOf<LibraryItemEntity?>(null) }

    PadAction(PadButton.X) { focused?.let { e -> scope.launch { metadata.confirm(e.item.id) } } }
    ScreenHints(
        listOf(
            ButtonHint(PadButton.A, stringResource(R.string.metadata_choose_game)),
            ButtonHint(PadButton.X, stringResource(R.string.metadata_confirm), onClick = { focused?.let { e -> scope.launch { metadata.confirm(e.item.id) } } }),
            ButtonHint(PadButton.B, stringResource(R.string.hint_back)),
        ),
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        io.github.thecodingdad.moonlightlauncher.ui.screens.settings.PageTitle(stringResource(R.string.metadata_review_list))
        Text(stringResource(R.string.metadata_review_help), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted, modifier = Modifier.padding(vertical = 8.dp))
        if (items.isEmpty()) {
            Text(stringResource(R.string.metadata_review_done), style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.success)
            return@Column
        }
        LazyColumn(
            state = io.github.thecodingdad.moonlightlauncher.ui.input.rememberStickLazyListState(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.focusRestorer(),
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
                        io.github.thecodingdad.moonlightlauncher.ui.components.CoverImage(entry, Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(entry.item.title, style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
                            Text(
                                stringResource(R.string.metadata_matched_as, entry.item.metadataQuery ?: entry.item.title, (entry.item.matchConfidence * 100).toInt()),
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
