package io.github.thecodingdad.moonlightlauncher.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.PlaySessionEntity
import androidx.compose.ui.focus.focusRequester
import io.github.thecodingdad.moonlightlauncher.ui.components.dpadScroll
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import io.github.thecodingdad.moonlightlauncher.resources.*

/** Pure aggregation, unit-testable. */
data class PlayStats(
    val totalSeconds: Long,
    val sessions: Int,
    /** Seconds per day, oldest first, last [days] days including today. */
    val perDay: List<Pair<Long, Long>>,
    val topGames: List<Pair<LibraryEntry, Long>>,
) {
    companion object {
        fun from(entries: List<LibraryEntry>, sessions: List<PlaySessionEntity>, now: Long = System.currentTimeMillis(), days: Int = 7): PlayStats {
            val dayStarts = (days - 1 downTo 0).map { offset ->
                Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.DAY_OF_YEAR, -offset)
                }.timeInMillis
            }
            val perDay = dayStarts.map { start ->
                val end = start + 86_400_000
                start to sessions.filter { it.startedAt in start until end }.sumOf { ((it.endedAt ?: it.startedAt) - it.startedAt) / 1000 }
            }
            val top = entries.filter { it.item.playtimeSeconds > 0 }.sortedByDescending { it.item.playtimeSeconds }.take(10)
                .map { it to it.item.playtimeSeconds }
            return PlayStats(entries.sumOf { it.item.playtimeSeconds }, sessions.size, perDay, top)
        }
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h h ${m} min" else "$m min"
}

@Composable
fun StatsScreen(entries: List<LibraryEntry>, sessions: List<PlaySessionEntity>) {
    val stats = remember(entries, sessions) { PlayStats.from(entries, sessions) }
    val colors = LauncherTheme.colors
    val byId = remember(entries) { entries.associateBy { it.item.id } }
    // The page is read-only; one focus target lets the D-pad scroll it.
    val scroll = rememberScrollState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().focusRequester(focus).dpadScroll(scroll).focusable().verticalScroll(scroll)
            .padding(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        io.github.thecodingdad.moonlightlauncher.ui.screens.settings.PageTitle(stringResource(Res.string.stats_title))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatTile(stringResource(Res.string.stats_total), formatDuration(stats.totalSeconds), Modifier.weight(1f))
            StatTile(stringResource(Res.string.stats_sessions), stats.sessions.toString(), Modifier.weight(1f))
            StatTile(stringResource(Res.string.stats_week), formatDuration(stats.perDay.sumOf { it.second }), Modifier.weight(1f))
        }

        Text(stringResource(Res.string.stats_last_days), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        val maxDay = stats.perDay.maxOf { it.second }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            stats.perDay.forEach { (day, secs) ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(0.8f * secs / maxDay + 0.02f)
                            .background(colors.accent, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    )
                    Text(
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(day)).substringBeforeLast('.').take(6),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (stats.topGames.isNotEmpty()) {
            Text(stringResource(Res.string.stats_most_played), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            val maxGame = stats.topGames.first().second.coerceAtLeast(1)
            stats.topGames.forEach { (entry, secs) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.item.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
                    Box(Modifier.weight(1f).height(14.dp)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(secs.toFloat() / maxGame).background(colors.accent, RoundedCornerShape(7.dp)))
                    }
                    Text(formatDuration(secs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted, modifier = Modifier.padding(start = 12.dp).width(110.dp))
                }
            }
        }

        if (sessions.isNotEmpty()) {
            Text(stringResource(Res.string.stats_recent_sessions), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            sessions.take(15).forEach { s ->
                Row {
                    Text(byId[s.itemId]?.item?.title ?: "?", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startedAt)) +
                            "  ·  " + formatDuration(((s.endedAt ?: s.startedAt) - s.startedAt) / 1000),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(LauncherTheme.colors.surface, RoundedCornerShape(14.dp)).padding(18.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurfaceMuted)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = LauncherTheme.colors.onSurface)
    }
}
