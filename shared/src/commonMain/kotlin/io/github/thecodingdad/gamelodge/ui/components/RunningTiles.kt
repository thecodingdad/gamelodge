package io.github.thecodingdad.gamelodge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.resources.Res
import io.github.thecodingdad.gamelodge.resources.hint_hold_quit
import io.github.thecodingdad.gamelodge.resources.tile_quitting
import io.github.thecodingdad.gamelodge.resources.tile_running
import io.github.thecodingdad.gamelodge.stream.LocalSessions
import io.github.thecodingdad.gamelodge.stream.Quitting
import io.github.thecodingdad.gamelodge.ui.RunningSession
import io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiSound
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.input.PadHold
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * What runs right now, by library item id: games on a host (also this launcher's stream) and the
 * game started on this PC. Each can be quit the way the quick menu does it.
 */
class RunningItems(private val quits: Map<String, () -> Unit>, private val quitting: Set<String>) {
    fun isRunning(itemId: String) = itemId in quits
    fun isQuitting(itemId: String) = itemId in quitting
    fun quit(itemId: String) = quits[itemId]?.invoke()

    companion object {
        val NONE = RunningItems(emptyMap(), emptySet())
    }
}

val LocalRunningItems = androidx.compose.runtime.compositionLocalOf { RunningItems.NONE }

/** The running sessions as [RunningItems] (cards without a library entry don't show on tiles). */
@Composable
fun rememberRunningItems(sessions: List<RunningSession>): RunningItems {
    val c = io.github.thecodingdad.gamelodge.LocalContainer.current
    val local by LocalSessions.running.collectAsState()
    val keys by Quitting.keys.collectAsState()
    val xbox = io.github.thecodingdad.gamelodge.xbox.rememberXboxRunning()
    return remember(sessions, local, keys, xbox) {
        val quits = HashMap<String, () -> Unit>()
        val quitting = HashSet<String>()
        sessions.forEach { s ->
            val id = s.entry?.item?.id ?: return@forEach
            quits[id] = { Quitting.quitHost(c, s.host, s.appName.ifBlank { s.entry.item.title }) }
            if (Quitting.hostKey(s.host) in keys) quitting += id
        }
        local?.let { s ->
            val id = s.entry.item.id
            val key = "local:$id"
            quits[id] = {
                Quitting.start(
                    c.scope, key, s.entry.item.title,
                    quit = { s.quit() },
                    running = { LocalSessions.running.value?.entry?.item?.id == id },
                )
            }
            if (key in keys) quitting += id
        }
        xbox.forEach { g ->
            val id = g.entry?.item?.id ?: return@forEach
            val key = "xbox:${g.consoleId}"
            quits[id] = {
                Quitting.start(
                    c.scope, key, g.entry.item.title,
                    quit = { quitXbox(c, g) },
                    running = { c.xbox.running.value[g.consoleId] == g.titleId },
                )
            }
            if (key in keys) quitting += id
        }
        RunningItems(quits, quitting)
    }
}

/** Starts quitting [game] (its key shows the progress until the console no longer runs it). */
fun startXboxQuit(c: io.github.thecodingdad.gamelodge.LauncherContainer, game: io.github.thecodingdad.gamelodge.xbox.XboxRunningGame) {
    Quitting.start(
        c.scope, "xbox:${game.consoleId}", game.entry?.item?.title ?: game.consoleName,
        quit = { quitXbox(c, game) },
        running = { c.xbox.running.value[game.consoleId] == game.titleId },
    )
}

/** Resumes [game]: streams it (again), or back to the stream window that still runs. */
fun resumeXbox(c: io.github.thecodingdad.gamelodge.LauncherContainer, game: io.github.thecodingdad.gamelodge.xbox.XboxRunningGame) {
    val entry = game.entry ?: return
    val target = game.target ?: return
    c.scope.launch { c.launcher.launch(entry, target) }
}

/** Quits a game on an Xbox console: our stream of it ends, the game on the console too. */
suspend fun quitXbox(c: io.github.thecodingdad.gamelodge.LauncherContainer, game: io.github.thecodingdad.gamelodge.xbox.XboxRunningGame) {
    if (game.streamed) c.streamControls.disconnect()
    c.xbox.quitGame(game.consoleId, game.target?.appUuid)
}

/** The tile being held to quit (Y) and how far: the tile shows it. */
class QuitHold {
    var itemId by mutableStateOf<String?>(null)
    val progress = Animatable(0f)
}

val LocalQuitHold = androidx.compose.runtime.staticCompositionLocalOf { QuitHold() }

/**
 * Y held on the focused [entry] quits it, when it runs. Returns its button hint (null when the
 * entry doesn't run), for the screen's hint bar.
 */
@Composable
fun quitHoldHint(entry: LibraryEntry?): ButtonHint? {
    val running = LocalRunningItems.current
    val hold = LocalQuitHold.current
    val feedback = LocalUiFeedback.current
    val scope = rememberCoroutineScope()
    val job = remember { arrayOfNulls<Job>(1) }
    val id = entry?.item?.id
    val active = id != null && running.isRunning(id) && !running.isQuitting(id)
    PadHold(
        PadButton.Y, enabled = active,
        onPress = {
            if (id == null || job[0]?.isActive == true) return@PadHold
            hold.itemId = id
            job[0] = scope.launch {
                hold.progress.snapTo(0f)
                hold.progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                feedback?.play(UiSound.CONFIRM)
                running.quit(id)
                hold.itemId = null
            }
        },
        onRelease = {
            job[0]?.cancel()
            if (hold.itemId == id) hold.itemId = null
        },
    )
    return if (active) ButtonHint(PadButton.Y, stringResource(Res.string.hint_hold_quit)) else null
}

/** Marks a running tile: accent frame, "Running" badge (top left) and the progress of a held quit. */
@Composable
fun BoxScope.RunningMark(entry: LibraryEntry, shape: Shape) {
    val running = LocalRunningItems.current
    val id = entry.item.id
    if (!running.isRunning(id)) return
    val colors = LauncherTheme.colors
    val quitting = running.isQuitting(id)
    Box(Modifier.matchParentSize().border(2.dp, colors.accent, shape))
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (reduceMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "running")
        val a by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "runningDot")
        a
    }
    Row(
        Modifier.align(Alignment.TopStart).padding(6.dp)
            .background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).alpha(pulse).background(if (quitting) colors.danger else colors.accent, CircleShape))
        Text(
            stringResource(if (quitting) Res.string.tile_quitting else Res.string.tile_running),
            style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
        )
    }
    QuitHoldBar(id)
}

/** The progress of a held quit (Y) of [itemId], along the bottom edge of its tile. */
@Composable
fun BoxScope.QuitHoldBar(itemId: String?) {
    val hold = LocalQuitHold.current
    if (itemId == null || hold.itemId != itemId) return
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp).height(5.dp)
            .clip(RoundedCornerShape(3.dp)).background(Color(0x66000000)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(hold.progress.value).background(LauncherTheme.colors.danger))
    }
}

private const val HOLD_MS = 1200
