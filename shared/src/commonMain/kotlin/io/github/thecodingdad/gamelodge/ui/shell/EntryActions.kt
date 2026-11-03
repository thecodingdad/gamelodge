package io.github.thecodingdad.gamelodge.ui.shell

import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.resources.*

/** Pending "choose target / profile" request. */
data class LaunchPick(val entry: LibraryEntry, val chooseProfile: Boolean)

/**
 * Shared actions on library entries. Every screen uses these, so A/X/long-press behave the
 * same on home, library, apps and detail.
 */
@Stable
class EntryActions(
    private val openDetail: (LibraryEntry) -> Unit,
    val launchDirect: (LibraryEntry, io.github.thecodingdad.gamelodge.data.db.LaunchTargetEntity) -> Unit,
) {
    var pick by mutableStateOf<LaunchPick?>(null)
    var contextMenuFor by mutableStateOf<LibraryEntry?>(null)
    /** Running session the context menu was opened for (resume card); adds stream options. */
    var contextSession by mutableStateOf<io.github.thecodingdad.gamelodge.ui.RunningSession?>(null)

    /** List the context menu was opened from: "Details" there pages through it like A does. */
    private var contextBrowse: DetailBrowse? = null

    fun contextForSession(session: io.github.thecodingdad.gamelodge.ui.RunningSession) {
        contextSession = session
        contextBrowse = null
        contextMenuFor = session.entry
    }

    fun closeContext() {
        contextMenuFor = null
        contextSession = null
    }

    /**
     * The list a detail page was opened from: its games can be paged through in that order.
     * [onShown] tells the list which game is on screen, so its focus follows on the way back.
     */
    class DetailBrowse(val ids: List<String>, val onShown: (String) -> Unit)

    var browse by mutableStateOf<DetailBrowse?>(null)
        private set

    /** Game paged to in the detail view: its card takes the focus when the list is back. */
    var returnFocusId by mutableStateOf<String?>(null)

    /** Setting: A on a game starts/installs it; the detail page is then reached via X. */
    var directStart by mutableStateOf(false)

    /**
     * A on a tile: games open their detail page (or start right away with [directStart]), apps
     * always start. [siblings] = its list.
     */
    fun open(entry: LibraryEntry, siblings: List<LibraryEntry>? = null, onShown: (String) -> Unit = {}) {
        if (entry.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP || directStart) {
            play(entry)
            return
        }
        browse = siblings?.let { browseOf(it, onShown) }
        openDetail(entry)
    }

    private fun browseOf(siblings: List<LibraryEntry>, onShown: (String) -> Unit) =
        DetailBrowse(siblings.filter { it.item.kind != io.github.thecodingdad.gamelodge.data.db.ItemKind.APP }.map { it.item.id }, onShown)

    /** Hint label for A on [entry], matching what [open] does. */
    fun openLabel(entry: LibraryEntry?): org.jetbrains.compose.resources.StringResource = when {
        entry == null -> Res.string.hint_open
        entry.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP -> Res.string.hint_start
        !directStart -> Res.string.action_details
        entry.notInstalled -> Res.string.action_install
        else -> Res.string.action_play
    }

    /** "Details" in the context menu. */
    fun showDetail(entry: LibraryEntry) {
        browse = contextBrowse?.takeIf { entry.item.id in it.ids }
        openDetail(entry)
    }

    /** A / "Play": asks for the target whenever there is more than one. */
    fun play(entry: LibraryEntry) {
        // Already running on this PC: back to it instead of a second start.
        io.github.thecodingdad.gamelodge.stream.LocalSessions.running.value?.takeIf { it.entry.item.id == entry.item.id }?.let {
            it.resume()
            return
        }
        when (entry.targets.size) {
            0 -> Unit
            1 -> launchDirect(entry, entry.targets.single())
            else -> pick = LaunchPick(entry, chooseProfile = false)
        }
    }

    /** X / "Start with…": target and profile chooser. */
    fun playWith(entry: LibraryEntry) {
        pick = LaunchPick(entry, chooseProfile = true)
    }

    /** Context menu (X / long press); [siblings] and [onShown] as for [open]. */
    fun context(entry: LibraryEntry, siblings: List<LibraryEntry>? = null, onShown: (String) -> Unit = {}) {
        contextMenuFor = entry
        contextBrowse = siblings?.let { browseOf(it, onShown) }
    }
}

val LocalEntryActions = staticCompositionLocalOf<EntryActions> { error("No EntryActions") }

/**
 * Back from paging through details: the card of the last game viewed takes the focus once the
 * list has settled (after its own focus restore, which would pick the card the detail was
 * opened from).
 */
@androidx.compose.runtime.Composable
fun androidx.compose.ui.Modifier.focusAfterBrowse(id: String): androidx.compose.ui.Modifier {
    val actions = LocalEntryActions.current
    val requester = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(actions.returnFocusId) {
        if (actions.returnFocusId != id) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        io.github.thecodingdad.gamelodge.ui.components.FocusSound.silence()
        if (runCatching { requester.requestFocus() }.isSuccess) actions.returnFocusId = null
    }
    return this.then(androidx.compose.ui.Modifier.focusRequester(requester))
}
