package io.github.thecodingdad.gamelodge.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.ui.RunningSession
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*

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
        // Apps are used, not played.
        val app = entry.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP
        when {
            hours > 0 -> add(pluralStringResource(if (app) Res.plurals.usage_hours else Res.plurals.playtime_hours, hours.toInt(), hours))
            minutes > 0 -> add(pluralStringResource(if (app) Res.plurals.usage_minutes else Res.plurals.playtime_minutes, minutes.toInt(), minutes))
            entry.item.lastPlayedAt == null -> add(stringResource(if (app) Res.string.never_used else Res.string.never_played))
        }
        entry.streamTargets.firstOrNull()?.hostName?.let { add(it) }
        if (entry.notInstalled) add(stringResource(Res.string.not_installed))
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
