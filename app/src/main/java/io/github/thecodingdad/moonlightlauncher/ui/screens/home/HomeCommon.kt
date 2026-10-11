package io.github.thecodingdad.moonlightlauncher.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.ui.RunningSession
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Space taken by the shell's top and hint bars; full-bleed home layouts pad their content by it. */
val LocalShellInsets = androidx.compose.runtime.compositionLocalOf { androidx.compose.foundation.layout.PaddingValues() }

/** Rows shown on the home screen, shared by all three home layouts. */
data class HomeRows(
    val continuePlaying: List<LibraryEntry>,
    val favorites: List<LibraryEntry>,
    val recentlyAdded: List<LibraryEntry>,
    val running: List<RunningSession>,
) {
    val isEmpty: Boolean get() = continuePlaying.isEmpty() && favorites.isEmpty() && recentlyAdded.isEmpty()

    companion object {
        fun from(games: List<LibraryEntry>, running: List<RunningSession>) = HomeRows(
            continuePlaying = games.filter { it.item.lastPlayedAt != null }
                .sortedByDescending { it.item.lastPlayedAt }.take(20),
            favorites = games.filter { it.item.favorite },
            recentlyAdded = games.sortedByDescending { it.item.addedAt }.take(20),
            running = running,
        )
    }
}

/** "Action RPG · 2022 · 42 h" line under a title. */
@Composable
fun MetaLine(entry: LibraryEntry, modifier: Modifier = Modifier) {
    val parts = buildList {
        entry.item.genres?.split(',')?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() }?.let(::add)
        entry.item.releaseYear?.let { add(it.toString()) }
        val hours = entry.item.playtimeSeconds / 3600
        val minutes = entry.item.playtimeSeconds / 60
        when {
            hours > 0 -> add(pluralStringResource(R.plurals.playtime_hours, hours.toInt(), hours))
            minutes > 0 -> add(pluralStringResource(R.plurals.playtime_minutes, minutes.toInt(), minutes))
            entry.item.lastPlayedAt == null -> add(stringResource(R.string.never_played))
        }
        entry.remoteTargets.firstOrNull()?.hostName?.let { add(it) }
        if (entry.notInstalled) add(stringResource(R.string.not_installed))
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            parts.joinToString("  ·  "),
            style = MaterialTheme.typography.titleSmall,
            color = LauncherTheme.colors.onSurfaceMuted,
            maxLines = 1,
        )
    }
}
