package io.github.thecodingdad.moonlightlauncher.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry
import io.github.thecodingdad.moonlightlauncher.data.db.TargetType
import io.github.thecodingdad.moonlightlauncher.stream.LaunchError
import io.github.thecodingdad.moonlightlauncher.stream.LaunchState
import io.github.thecodingdad.moonlightlauncher.stream.LaunchStep
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import io.github.thecodingdad.moonlightlauncher.ui.components.ArtworkImage
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHintBar
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.HoldToConfirmButton
import io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.components.heroModel
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadBlocker
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

@Composable
fun targetLabel(target: LaunchTargetEntity): String = when (target.type) {
    TargetType.LOCAL -> stringResource(R.string.target_local)
    TargetType.REMOTE -> stringResource(R.string.target_remote, target.hostName ?: "?")
}

/** Where to start (always asked when there is more than one target) and, for "start with…", which profile. */
@Composable
fun TargetPickerDialog(
    pick: LaunchPick?,
    profiles: List<StreamProfile>,
    activeProfile: StreamProfile,
    onDismiss: () -> Unit,
    onLaunch: (LibraryEntry, LaunchTargetEntity, StreamProfile?) -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    Modal(visible = pick != null, onDismiss = onDismiss, title = pick?.entry?.item?.title, initialFocus = startFocus) {
        val p = pick ?: return@Modal
        val hasRemote = p.entry.remoteTargets.isNotEmpty()
        var profileIndex by remember(p) {
            mutableIntStateOf(profiles.indexOfFirst { it.uuid == activeProfile.uuid }.coerceAtLeast(0))
        }
        val showProfiles = p.chooseProfile && hasRemote && profiles.isNotEmpty()
        val chosenProfile = profiles.getOrNull(profileIndex)

        if (showProfiles) {
            PadAction(PadButton.L1) { profileIndex = (profileIndex - 1).mod(profiles.size) }
            PadAction(PadButton.R1) { profileIndex = (profileIndex + 1).mod(profiles.size) }
            Text(stringResource(R.string.launch_profile), style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusSurface(onClick = { profileIndex = (profileIndex - 1).mod(profiles.size) }, modifier = Modifier.size(48.dp), color = LauncherTheme.colors.surfaceRaised) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.align(Alignment.Center), tint = LauncherTheme.colors.onSurface)
                }
                Box(
                    Modifier.weight(1f).background(LauncherTheme.colors.surfaceRaised, RoundedCornerShape(12.dp)).padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, null, tint = LauncherTheme.colors.accent)
                        Text("  ${chosenProfile?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurface)
                    }
                }
                FocusSurface(onClick = { profileIndex = (profileIndex + 1).mod(profiles.size) }, modifier = Modifier.size(48.dp), color = LauncherTheme.colors.surfaceRaised) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.align(Alignment.Center), tint = LauncherTheme.colors.onSurface)
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Text(stringResource(R.string.launch_where), style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.onSurfaceMuted)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            p.entry.targets.sortedBy { it.type != TargetType.LOCAL }.forEachIndexed { index, target ->
                LauncherButton(
                    text = targetLabel(target),
                    icon = if (target.type == TargetType.LOCAL) Icons.Filled.PhoneAndroid else Icons.Filled.Cloud,
                    primary = true,
                    onClick = { onLaunch(p.entry, target, if (showProfiles) chosenProfile else null) },
                    modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(startFocus) else Modifier),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ButtonHintBar(
            buildList {
                if (showProfiles) add(ButtonHint(PadButton.L1, stringResource(R.string.hint_profile)))
                add(ButtonHint(PadButton.A, stringResource(R.string.hint_start)))
                add(ButtonHint(PadButton.B, stringResource(R.string.action_cancel), onClick = onDismiss))
            },
        )
    }
}

/** Progress / error / confirmation overlay for the launch flow. */
@Composable
fun LaunchOverlay(
    state: LaunchState,
    onDismiss: () -> Unit,
    onRetry: (LibraryEntry) -> Unit,
    onConfirmQuit: (LaunchState.ConfirmQuitRunning) -> Unit,
) {
    AnimatedVisibility(state is LaunchState.Working, enter = fadeIn(), exit = fadeOut()) {
        val working = state as? LaunchState.Working ?: return@AnimatedVisibility
        PadBlocker()
        Box(Modifier.fillMaxSize().background(LauncherTheme.colors.background), contentAlignment = Alignment.Center) {
            ArtworkImage(working.entry.heroModel(), working.entry.item.title, Modifier.fillMaxSize().blur(24.dp), showTitleOnPlaceholder = false)
            Box(Modifier.fillMaxSize().background(LauncherTheme.colors.scrim))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(working.entry.item.title, style = MaterialTheme.typography.displaySmall, color = LauncherTheme.colors.onSurface)
                CircularProgressIndicator(color = LauncherTheme.colors.accent)
                Text(stepText(working.step), style = MaterialTheme.typography.titleMedium, color = LauncherTheme.colors.onSurfaceMuted)
            }
        }
    }

    val failed = state as? LaunchState.Failed
    Modal(visible = failed != null, onDismiss = onDismiss, title = stringResource(R.string.launch_failed_title)) {
        if (failed == null) return@Modal
        Text(errorText(failed.reason), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LauncherButton(stringResource(R.string.action_retry), onClick = { onRetry(failed.entry) }, primary = true)
            LauncherButton(stringResource(R.string.action_close), onClick = onDismiss)
        }
    }

    val confirm = state as? LaunchState.ConfirmQuitRunning
    Modal(visible = confirm != null, onDismiss = onDismiss, title = stringResource(R.string.launch_running_title)) {
        if (confirm == null) return@Modal
        Text(
            stringResource(R.string.launch_running_message, confirm.runningName, confirm.entry.item.title),
            style = MaterialTheme.typography.bodyLarge,
            color = LauncherTheme.colors.onSurface,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldToConfirmButton(stringResource(R.string.launch_quit_and_start), onConfirmed = { onConfirmQuit(confirm) }, icon = Icons.Filled.PowerSettingsNew)
            LauncherButton(stringResource(R.string.action_cancel), onClick = onDismiss)
        }
        Spacer(Modifier.height(12.dp))
        ButtonHintBar(listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_hold_to_confirm)), ButtonHint(PadButton.B, stringResource(R.string.action_cancel), onClick = onDismiss)))
    }
}

@Composable
private fun stepText(step: LaunchStep) = stringResource(
    when (step) {
        LaunchStep.WAKING_HOST -> R.string.step_waking_host
        LaunchStep.CONNECTING_VPN -> R.string.step_connecting_vpn
        LaunchStep.CHECKING_NETWORK -> R.string.step_checking_network
        LaunchStep.QUITTING_RUNNING -> R.string.step_quitting
        LaunchStep.STARTING -> R.string.step_starting
    },
)

@Composable
private fun errorText(error: LaunchError) = stringResource(
    when (error) {
        LaunchError.HOST_OFFLINE -> R.string.error_host_offline
        LaunchError.NOT_PAIRED -> R.string.error_not_paired
        LaunchError.APP_NOT_INSTALLED -> R.string.error_app_missing
        LaunchError.START_FAILED -> R.string.error_start_failed
        LaunchError.QUIT_FAILED -> R.string.error_quit_failed
    },
)

/** X / long-press menu for an entry. */
@Composable
fun EntryContextMenu(
    entry: LibraryEntry?,
    onDismiss: () -> Unit,
    onPlay: (LibraryEntry) -> Unit,
    onPlayWith: (LibraryEntry) -> Unit,
    onDetails: (LibraryEntry) -> Unit,
    onFavorite: (LibraryEntry, Boolean) -> Unit,
    onKind: (LibraryEntry, ItemKind) -> Unit,
    onHide: (LibraryEntry) -> Unit,
) {
    Modal(visible = entry != null, onDismiss = onDismiss, title = entry?.item?.title, maxWidth = 520.dp) {
        val e = entry ?: return@Modal
        fun act(block: () -> Unit) = { onDismiss(); block() }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherButton(stringResource(R.string.action_play), act { onPlay(e) }, Modifier.fillMaxWidth(), Icons.Filled.PlayArrow, primary = true)
            if (e.remoteTargets.isNotEmpty()) {
                LauncherButton(stringResource(R.string.action_play_with), act { onPlayWith(e) }, Modifier.fillMaxWidth(), Icons.Filled.Tune)
            }
            LauncherButton(stringResource(R.string.action_details), act { onDetails(e) }, Modifier.fillMaxWidth(), Icons.Filled.Info)
            LauncherButton(
                stringResource(if (e.item.favorite) R.string.action_unfavorite else R.string.action_favorite),
                act { onFavorite(e, !e.item.favorite) }, Modifier.fillMaxWidth(),
                if (e.item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            )
            if (e.item.kind == ItemKind.GAME) {
                LauncherButton(stringResource(R.string.action_mark_app), act { onKind(e, ItemKind.APP) }, Modifier.fillMaxWidth(), Icons.Filled.Apps)
            } else {
                LauncherButton(stringResource(R.string.action_mark_game), act { onKind(e, ItemKind.GAME) }, Modifier.fillMaxWidth(), Icons.Filled.SportsEsports)
            }
            LauncherButton(stringResource(R.string.action_hide), act { onHide(e) }, Modifier.fillMaxWidth(), Icons.Filled.VisibilityOff)
        }
    }
}
