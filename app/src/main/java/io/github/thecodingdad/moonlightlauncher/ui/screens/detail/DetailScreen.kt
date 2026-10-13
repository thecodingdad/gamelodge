package io.github.thecodingdad.moonlightlauncher.ui.screens.detail

import androidx.compose.ui.focus.focusRequester
import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.SportsEsports
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    entry: LibraryEntry,
    sessions: List<PlaySessionEntity>,
    animatedArtwork: Boolean,
    onFavorite: (Boolean) -> Unit,
    onPlatform: () -> Unit = {},
    profiles: List<io.github.thecodingdad.moonlightlauncher.stream.StreamProfile> = emptyList(),
    activeProfile: io.github.thecodingdad.moonlightlauncher.stream.StreamProfile? = null,
    onProfile: (io.github.thecodingdad.moonlightlauncher.stream.StreamProfile) -> Unit = {},
    onEditProfiles: () -> Unit = {},
    /** Previous (-1) / next (+1) game of the list the page was opened from; null = no list. */
    onBrowse: ((Int) -> Unit)? = null,
    /** False for the neighbour pages composed for swiping: no input, hints, accent or focus. */
    active: Boolean = true,
    extraActions: @Composable () -> Unit = {},
) {
    val actions = LocalEntryActions.current
    val colors = LauncherTheme.colors
    val hasRemote = entry.remoteTargets.isNotEmpty()
    val hasTargetChoice = entry.hasTargetChoice
    if (active) PublishAccentFrom(entry.coverModel())

    if (hasTargetChoice) PadAction(PadButton.X, enabled = active) { actions.playWith(entry) }
    PadAction(PadButton.START, enabled = active) { actions.play(entry) }
    // Remote games: LB/RB switch the stream profile used for the next start.
    var profilePicker by remember { mutableStateOf(false) }
    fun stepProfile(delta: Int) {
        if (profiles.size < 2 || activeProfile == null) return
        val i = profiles.indexOfFirst { it.uuid == activeProfile.uuid }.coerceAtLeast(0)
        onProfile(profiles[(i + delta).mod(profiles.size)])
    }
    if (hasRemote) {
        PadAction(PadButton.L1, enabled = active) { stepProfile(-1) }
        PadAction(PadButton.R1, enabled = active) { stepProfile(1) }
    }
    if (onBrowse != null) {
        PadAction(PadButton.RS_LEFT, enabled = active) { onBrowse(-1) }
        PadAction(PadButton.RS_RIGHT, enabled = active) { onBrowse(1) }
    }
    if (active) ScreenHints(
        buildList {
            add(ButtonHint(PadButton.A, stringResource(R.string.hint_select)))
            if (hasTargetChoice) add(ButtonHint(PadButton.X, stringResource(R.string.action_play_with), onClick = { actions.playWith(entry) }))
            if (onBrowse != null) add(ButtonHint(PadButton.RS_RIGHT, stringResource(R.string.hint_prev_next), onClick = { onBrowse(1) }))
            if (hasRemote && profiles.size > 1) {
                add(ButtonHint(PadButton.L1, stringResource(R.string.profile_picker_title), onClick = { stepProfile(1) }, secondButton = PadButton.R1))
            }
            add(ButtonHint(PadButton.B, stringResource(R.string.hint_back)))
        },
    )

    Box(Modifier.fillMaxSize()) {
        io.github.thecodingdad.moonlightlauncher.ui.components.HeroImage(entry, Modifier.fillMaxSize(), animated = animatedArtwork)
        val bg = colors.background
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.5f to bg.copy(alpha = 0.85f), 1f to bg.copy(alpha = 0.25f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to bg)))

        // Layout grows with the available space (e.g. on a TV): bigger cover, wider text column,
        // more generous spacing, content vertically centred instead of glued to the top left.
        val compact = io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current
        val chips: @Composable () -> Unit = {
                    // Platform as a chip: shows the current value, opens the platform picker.
                    io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(
                        label = entry.item.platform ?: stringResource(R.string.platform_title),
                        selected = false,
                        onClick = onPlatform,
                        icon = {
                            androidx.compose.material3.Icon(
                                Icons.Filled.SportsEsports, null, Modifier.padding(end = 6.dp).size(18.dp),
                            )
                        },
                    )
                    if (hasRemote && activeProfile != null) {
                        io.github.thecodingdad.moonlightlauncher.ui.screens.library.Chip(
                            label = activeProfile.name,
                            selected = false,
                            onClick = { profilePicker = true },
                            icon = {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.Tune, null, Modifier.padding(end = 6.dp).size(18.dp),
                                )
                            },
                        )
                    }
                    SourceBadges(entry)
                    MetaLine(entry)
                }
        val buttons: @Composable () -> Unit = {
                    LauncherButton(
                        stringResource(if (entry.notInstalled) R.string.action_install else R.string.action_play),
                        onClick = { actions.play(entry) },
                        icon = if (entry.notInstalled) Icons.Filled.Download else Icons.Filled.PlayArrow, primary = true,
                        modifier = Modifier.focusWhenActive(active),
                    )
                    if (hasTargetChoice) LauncherButton(stringResource(R.string.action_play_with), onClick = { actions.playWith(entry) }, icon = Icons.Filled.Tune)
                    LauncherButton(
                        "", onClick = { onFavorite(!entry.item.favorite) },
                        icon = if (entry.item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    )
                    LauncherButton("", onClick = { actions.context(entry) }, icon = Icons.Filled.MoreHoriz)
                }
        val details: @Composable () -> Unit = {
            extraActions()
            entry.item.description?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
            InfoBlock(entry, sessions)
            Spacer(Modifier.height(24.dp))
        }
        if (compact) {
            // Narrow (portrait): cover next to logo and chips on top, everything else full width below.
            Column(
                Modifier.fillMaxSize().controllerScroll().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    io.github.thecodingdad.moonlightlauncher.ui.components.CoverImage(
                        entry, Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), animated = animatedArtwork,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (entry.item.logoUrl != null) {
                            io.github.thecodingdad.moonlightlauncher.ui.components.LogoImage(entry.item.logoUrl, entry.item.title, Modifier.height(70.dp).fillMaxWidth())
                        } else {
                            Text(entry.item.title, style = MaterialTheme.typography.headlineMedium, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { chips() }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.focusRestorer(),
                ) { buttons() }
                details()
            }
        } else {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val roomy = maxWidth > 1100.dp
        val coverWidth = minOf(maxWidth * 0.26f, (maxHeight - 40.dp) * 0.82f * (2f / 3f)).coerceAtLeast(210.dp)
        val sidePadding = (maxWidth * 0.045f).coerceAtLeast(40.dp)
        val gap = (maxWidth * 0.035f).coerceAtLeast(36.dp)
        val textWidth = (maxWidth - sidePadding * 2 - gap - coverWidth).coerceAtMost(if (roomy) 860.dp else 640.dp)
        Row(
            Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = if (roomy) 32.dp else 20.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = if (roomy) Alignment.CenterVertically else Alignment.Top,
        ) {
            io.github.thecodingdad.moonlightlauncher.ui.components.CoverImage(
                entry, Modifier.width(coverWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(if (roomy) 22.dp else 16.dp)), animated = animatedArtwork,
            )
            Column(
                Modifier.width(textWidth).controllerScroll(),
                verticalArrangement = Arrangement.spacedBy(if (roomy) 18.dp else 12.dp),
            ) {
                if (entry.item.logoUrl != null) {
                    io.github.thecodingdad.moonlightlauncher.ui.components.LogoImage(
                        entry.item.logoUrl, entry.item.title,
                        Modifier.height(if (roomy) 130.dp else 90.dp).widthIn(max = if (roomy) 620.dp else 460.dp),
                    )
                } else {
                    Text(entry.item.title, style = MaterialTheme.typography.displaySmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    chips()
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusRestorer()) {
                    buttons()
                }
                details()
            }
        }
        }
        }
        if (activeProfile != null) {
            io.github.thecodingdad.moonlightlauncher.ui.shell.ProfilePickerPopup(
                visible = profilePicker, profiles = profiles, active = activeProfile,
                onSelect = onProfile, onEdit = onEditProfiles, onDismiss = { profilePicker = false },
            )
        }
    }
}

@Composable
private fun InfoBlock(entry: LibraryEntry, sessions: List<PlaySessionEntity>) {
    val colors = LauncherTheme.colors
    val rows = buildList {
        entry.item.developer?.let { add(stringResource(R.string.detail_developer) to it) }
        entry.item.publisher?.takeIf { it != entry.item.developer }?.let { add(stringResource(R.string.filter_field_publisher) to it) }
        entry.item.series?.let { add(stringResource(R.string.filter_field_series) to it) }
        entry.item.genres?.let { add(stringResource(R.string.detail_genres) to it) }
        entry.item.features?.let { add(stringResource(R.string.filter_field_feature) to it) }
        io.github.thecodingdad.moonlightlauncher.metadata.AgeRatings.display(entry.item.ageRatings)?.let { add(stringResource(R.string.filter_field_age_rating) to it) }
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
                Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted, modifier = Modifier.widthIn(min = 120.dp, max = 170.dp).padding(end = 12.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun targetLabelText(t: io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity) = targetLabel(t)

/** The play button takes the focus whenever its page becomes the shown one (controller mode). */
@Composable
private fun Modifier.focusWhenActive(active: Boolean): Modifier {
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    val controllerMode = io.github.thecodingdad.moonlightlauncher.ui.components.isControllerMode()
    androidx.compose.runtime.LaunchedEffect(active, controllerMode) {
        if (active && controllerMode) runCatching { requester.requestFocus() }
    }
    return this.then(Modifier.focusRequester(requester))
}
