package io.github.thecodingdad.moonlightlauncher.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.PlaySessionEntity
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.PublishAccentFrom
import io.github.thecodingdad.moonlightlauncher.ui.components.SourceBadges
import io.github.thecodingdad.moonlightlauncher.ui.components.coverModel
import io.github.thecodingdad.moonlightlauncher.ui.components.heroModel
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.MetaLine
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints
import io.github.thecodingdad.moonlightlauncher.ui.shell.targetLabel
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun DetailScreen(
    entry: LibraryEntry,
    sessions: List<PlaySessionEntity>,
    animatedArtwork: Boolean,
    onFavorite: (Boolean) -> Unit,
    extraActions: @Composable () -> Unit = {},
) {
    val actions = LocalEntryActions.current
    val colors = LauncherTheme.colors
    val hasRemote = entry.remoteTargets.isNotEmpty()
    PublishAccentFrom(entry.coverModel())

    if (hasRemote) PadAction(PadButton.X) { actions.playWith(entry) }
    ScreenHints(
        buildList {
            add(ButtonHint(PadButton.A, stringResource(R.string.hint_select)))
            if (hasRemote) add(ButtonHint(PadButton.X, stringResource(R.string.action_play_with), onClick = { actions.playWith(entry) }))
            add(ButtonHint(PadButton.B, stringResource(R.string.hint_back)))
        },
    )

    Box(Modifier.fillMaxSize()) {
        ArtworkImage(entry.heroModel(animatedArtwork), entry.item.title, Modifier.fillMaxSize(), ContentScale.Crop, showTitleOnPlaceholder = false)
        val bg = colors.background
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.5f to bg.copy(alpha = 0.85f), 1f to bg.copy(alpha = 0.25f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to bg)))

        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            ArtworkImage(
                entry.coverModel(animatedArtwork), entry.item.title,
                Modifier.width(210.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)),
            )
            Column(
                Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entry.item.logoUrl != null) {
                    io.github.thecodingdad.moonlightlauncher.ui.components.LogoImage(entry.item.logoUrl, entry.item.title, Modifier.height(90.dp).widthIn(max = 460.dp))
                } else {
                    Text(entry.item.title, style = MaterialTheme.typography.displaySmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceBadges(entry)
                    MetaLine(entry)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusRestorer()) {
                    LauncherButton(
                        stringResource(R.string.action_play), onClick = { actions.play(entry) },
                        icon = Icons.Filled.PlayArrow, primary = true,
                        modifier = Modifier.restoreFocusIf(true),
                    )
                    if (hasRemote) LauncherButton(stringResource(R.string.action_play_with), onClick = { actions.playWith(entry) }, icon = Icons.Filled.Tune)
                    LauncherButton(
                        "", onClick = { onFavorite(!entry.item.favorite) },
                        icon = if (entry.item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    )
                    LauncherButton("", onClick = { actions.context(entry) }, icon = Icons.Filled.MoreHoriz)
                }
                extraActions()
                entry.item.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                }
                InfoBlock(entry, sessions)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InfoBlock(entry: LibraryEntry, sessions: List<PlaySessionEntity>) {
    val colors = LauncherTheme.colors
    val rows = buildList {
        entry.item.developer?.let { add(stringResource(R.string.detail_developer) to it) }
        entry.item.genres?.let { add(stringResource(R.string.detail_genres) to it) }
        entry.item.rating?.let { add(stringResource(R.string.detail_rating) to "%.0f / 100".format(it)) }
        add(stringResource(R.string.detail_available) to entry.targets.map { targetLabelText(it) }.joinToString(", "))
        entry.item.lastPlayedAt?.let { add(stringResource(R.string.detail_last_played) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
        if (sessions.isNotEmpty()) add(stringResource(R.string.detail_sessions) to sessions.size.toString())
    }
    Column(
        Modifier.background(colors.surface.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted, modifier = Modifier.width(170.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
    }
}

@Composable
private fun targetLabelText(t: io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity) = targetLabel(t)
