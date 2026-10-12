package io.github.thecodingdad.moonlightlauncher.ui.shell

import io.github.thecodingdad.moonlightlauncher.ui.components.controllerScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import kotlinx.coroutines.launch
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
    profiles: List<StreamProfile>,
    onContinueDespiteNetwork: (LaunchState.NetworkWarning, StreamProfile?) -> Unit,
    onCancel: () -> Unit = onDismiss,
) {
    AnimatedVisibility(state is LaunchState.Working, enter = fadeIn(), exit = fadeOut()) {
        val working = state as? LaunchState.Working ?: return@AnimatedVisibility
        LaunchProgressScreen(working, onCancel)
    }

    val failed = state as? LaunchState.Failed
    Modal(visible = failed != null, onDismiss = onDismiss, title = stringResource(R.string.launch_failed_title)) {
        if (failed == null) return@Modal
        Text(errorText(failed.reason), style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface)
        Spacer(Modifier.height(24.dp))
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
            LauncherButton(stringResource(R.string.action_retry), onClick = { onRetry(failed.entry) }, primary = true)
            LauncherButton(stringResource(R.string.action_close), onClick = onDismiss)
        }
    }

    val warning = state as? LaunchState.NetworkWarning
    Modal(visible = warning != null, onDismiss = onDismiss, title = stringResource(R.string.network_warning_title)) {
        if (warning == null) return@Modal
        Text(
            stringResource(R.string.network_warning_message, warning.result.rttMs, warning.result.jitterMs),
            style = MaterialTheme.typography.bodyLarge, color = LauncherTheme.colors.onSurface,
        )
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherButton(
                stringResource(R.string.network_continue), onClick = { onContinueDespiteNetwork(warning, warning.override) },
                primary = true, modifier = Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow,
            )
            profiles.forEach { p ->
                LauncherButton(
                    stringResource(R.string.network_start_with, p.name), onClick = { onContinueDespiteNetwork(warning, p) },
                    modifier = Modifier.fillMaxWidth(), icon = Icons.Filled.Tune,
                )
            }
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
        io.github.thecodingdad.moonlightlauncher.ui.components.ButtonRow(spacing = 12.dp) {
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
        LaunchError.WAKE_FAILED -> R.string.error_wake_failed
        LaunchError.NO_LAUNCH_PERMISSION -> R.string.error_no_launch_permission
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
    session: io.github.thecodingdad.moonlightlauncher.ui.RunningSession?,
    onResume: (io.github.thecodingdad.moonlightlauncher.ui.RunningSession) -> Unit,
    onDismiss: () -> Unit,
    onPlay: (LibraryEntry) -> Unit,
    onPlayWith: (LibraryEntry) -> Unit,
    onDetails: (LibraryEntry) -> Unit,
    onFavorite: (LibraryEntry, Boolean) -> Unit,
    onKind: (LibraryEntry, ItemKind) -> Unit,
    onHide: (LibraryEntry) -> Unit,
    onCollections: (LibraryEntry) -> Unit,
    onEditMetadata: (LibraryEntry) -> Unit,
    /** Apps: tile for the start tab (apps have no metadata). */
    onAppArtwork: (LibraryEntry) -> Unit = {},
    /** Opened from the game's own detail page: its buttons already cover play and favorite. */
    inDetail: Boolean = false,
) {
    Modal(
        visible = entry != null || session != null, onDismiss = onDismiss,
        title = entry?.item?.title ?: session?.appName, maxWidth = 520.dp,
    ) {
        fun act(block: () -> Unit) = { onDismiss(); block() }
        Column(
            Modifier.heightIn(max = 420.dp).controllerScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session != null) RunningSessionActions(session, onResume = act { onResume(session) }, onDone = onDismiss)
            val e = entry ?: return@Column
            val isApp = e.item.kind == ItemKind.APP
            if (!inDetail) {
                if (session == null) {
                    LauncherButton(
                        stringResource(
                            when {
                                e.notInstalled -> R.string.action_install
                                isApp -> R.string.action_start
                                else -> R.string.action_play
                            },
                        ),
                        act { onPlay(e) },
                        Modifier.fillMaxWidth(), if (e.notInstalled) Icons.Filled.Download else Icons.Filled.PlayArrow, primary = true,
                    )
                }
                if (e.hasTargetChoice) {
                    LauncherButton(stringResource(R.string.action_play_with), act { onPlayWith(e) }, Modifier.fillMaxWidth(), Icons.Filled.Tune)
                }
                // Apps: Android's app details (only for apps on this device; host apps have none).
                if (!isApp || e.localTarget != null) {
                    LauncherButton(stringResource(R.string.action_details), act { onDetails(e) }, Modifier.fillMaxWidth(), Icons.Filled.Info)
                }
                LauncherButton(
                    stringResource(if (e.item.favorite) R.string.action_unfavorite else R.string.action_favorite),
                    act { onFavorite(e, !e.item.favorite) }, Modifier.fillMaxWidth(),
                    if (e.item.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                )
            }
            if (isApp) {
                LauncherButton(stringResource(R.string.action_app_artwork), act { onAppArtwork(e) }, Modifier.fillMaxWidth(), Icons.Filled.Image)
            } else {
                LauncherButton(
                    stringResource(R.string.meta_edit_title), act { onEditMetadata(e) }, Modifier.fillMaxWidth(), Icons.Filled.Edit,
                    primary = inDetail && session == null,
                )
            }
            if (e.item.kind == ItemKind.GAME) {
                LauncherButton(stringResource(R.string.action_mark_app), act { onKind(e, ItemKind.APP) }, Modifier.fillMaxWidth(), Icons.Filled.Apps)
            } else {
                LauncherButton(stringResource(R.string.action_mark_game), act { onKind(e, ItemKind.GAME) }, Modifier.fillMaxWidth(), Icons.Filled.SportsEsports)
            }
            LauncherButton(stringResource(R.string.action_collections), act { onCollections(e) }, Modifier.fillMaxWidth(), Icons.Filled.CollectionsBookmark)
            LauncherButton(stringResource(R.string.action_hide), act { onHide(e) }, Modifier.fillMaxWidth(), Icons.Filled.VisibilityOff)
        }
    }
}

/** Stream section of the context menu for a game that is running on a host. */
@Composable
private fun RunningSessionActions(
    session: io.github.thecodingdad.moonlightlauncher.ui.RunningSession,
    onResume: () -> Unit,
    onDone: () -> Unit,
) {
    val c = androidx.compose.ui.platform.LocalContext.current.container
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var config by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(session.host.uuid) { config = c.hostPower.config(session.host.uuid) }

    Text(stringResource(R.string.qm_running_on, session.appName, session.host.name), style = MaterialTheme.typography.labelLarge, color = LauncherTheme.colors.accent)
    LauncherButton(stringResource(R.string.qm_resume), onResume, Modifier.fillMaxWidth(), Icons.Filled.PlayArrow, primary = true)
    HoldToConfirmButton(
        stringResource(R.string.qm_quit_game),
        onConfirmed = {
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.hosts.http(session.host).quitApp() } }
                c.hosts.invalidate(session.host.uuid)
                onDone()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Filled.StopCircle,
    )
    config?.let { cfg ->
        if (cfg.haSleepEntity != null) HoldToConfirmButton(stringResource(R.string.power_sleep), { scope.launch { c.hostPower.power(cfg.hostUuid, io.github.thecodingdad.moonlightlauncher.stream.PowerAction.SLEEP); onDone() } }, Modifier.fillMaxWidth(), Icons.Filled.Bedtime)
        if (cfg.haShutdownEntity != null) HoldToConfirmButton(stringResource(R.string.power_shutdown), { scope.launch { c.hostPower.power(cfg.hostUuid, io.github.thecodingdad.moonlightlauncher.stream.PowerAction.SHUTDOWN); onDone() } }, Modifier.fillMaxWidth(), Icons.Filled.PowerSettingsNew)
    }
    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
}

/** Full-screen progress while a stream starts: game artwork, logo, current step, B cancels. */
@Composable
private fun LaunchProgressScreen(working: LaunchState.Working, onCancel: () -> Unit) {
    // Every pad button is swallowed while starting; B (back) cancels.
    PadBlocker()
    androidx.activity.compose.BackHandler(onBack = onCancel)
    StreamProgressContent(
        entry = working.entry,
        step = stepText(working.step),
        detail = null,
        timerKey = working.step,
        showTimer = working.step == LaunchStep.WAKING_HOST,
        onCancel = onCancel,
    )
}

/**
 * Full-screen start progress with the game's artwork. Shared by the launcher (waking, VPN …)
 * and the stream activity while it connects ([StreamConnectingOverlay]).
 */
@Composable
fun StreamProgressContent(
    entry: LibraryEntry,
    step: String,
    detail: String?,
    timerKey: Any?,
    showTimer: Boolean,
    onCancel: (() -> Unit)?,
) {
    val colors = LauncherTheme.colors
    val reduceMotion = io.github.thecodingdad.moonlightlauncher.ui.theme.LocalReduceMotion.current
    val host = entry.remoteTargets.firstOrNull()?.hostName

    // Slow zoom on the artwork so the wait feels alive.
    val zoom = if (reduceMotion) 1f else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "launchZoom")
        transition.animateFloat(
            1f, 1.08f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(20_000, easing = androidx.compose.animation.core.LinearEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "zoom",
        ).value
    }
    // Waking can take a while: show how long we've been waiting.
    var seconds by androidx.compose.runtime.remember(timerKey) { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(timerKey) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            seconds++
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        ArtworkImage(
            entry.heroModel(), entry.item.title,
            Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
            showTitleOnPlaceholder = false,
        )
        val bg = colors.background
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(0f to bg.copy(alpha = 0.9f), 0.55f to bg.copy(alpha = 0.4f), 1f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to Color.Transparent, 1f to bg.copy(alpha = 0.95f)),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 56.dp, end = 56.dp, bottom = 72.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (entry.item.logoUrl != null) {
                io.github.thecodingdad.moonlightlauncher.ui.components.LogoImage(
                    entry.item.logoUrl, entry.item.title, Modifier.height(120.dp).widthIn(max = 520.dp),
                )
            } else {
                Text(entry.item.title, style = MaterialTheme.typography.displaySmall, color = colors.onSurface, maxLines = 2)
            }
            if (host != null) {
                Text(stringResource(R.string.launch_on_host, host), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceMuted)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(step, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                if (showTimer && seconds > 0) {
                    Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceMuted)
                }
            }
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted, maxLines = 1)
            }
            androidx.compose.material3.LinearProgressIndicator(
                color = colors.accent, trackColor = colors.onSurface.copy(alpha = 0.15f),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
        if (onCancel != null) {
            ButtonHintBar(
                listOf(ButtonHint(PadButton.B, stringResource(R.string.action_cancel), onClick = onCancel)),
                Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
