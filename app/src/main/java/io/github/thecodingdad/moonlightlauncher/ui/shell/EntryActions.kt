package io.github.thecodingdad.moonlightlauncher.ui.shell

import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry

/** Pending "choose target / profile" request. */
data class LaunchPick(val entry: LibraryEntry, val chooseProfile: Boolean)

/**
 * Shared actions on library entries. Every screen uses these, so A/X/long-press behave the
 * same on home, library, apps and detail.
 */
@Stable
class EntryActions(
    private val openDetail: (LibraryEntry) -> Unit,
    val launchDirect: (LibraryEntry, io.github.thecodingdad.moonlightlauncher.data.db.LaunchTargetEntity) -> Unit,
) {
    var pick by mutableStateOf<LaunchPick?>(null)
    var contextMenuFor by mutableStateOf<LibraryEntry?>(null)
    /** Running session the context menu was opened for (resume card); adds stream options. */
    var contextSession by mutableStateOf<io.github.thecodingdad.moonlightlauncher.ui.RunningSession?>(null)

    fun contextForSession(session: io.github.thecodingdad.moonlightlauncher.ui.RunningSession) {
        contextSession = session
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

    /** A on a tile: games open their detail page, apps start right away. [siblings] = its list. */
    fun open(entry: LibraryEntry, siblings: List<LibraryEntry>? = null, onShown: (String) -> Unit = {}) {
        if (entry.item.kind == io.github.thecodingdad.moonlightlauncher.data.db.ItemKind.APP) {
            play(entry)
            return
        }
        browse = siblings?.filter { it.item.kind != io.github.thecodingdad.moonlightlauncher.data.db.ItemKind.APP }
            ?.map { it.item.id }?.let { DetailBrowse(it, onShown) }
        openDetail(entry)
    }

    fun showDetail(entry: LibraryEntry) {
        browse = null
        openDetail(entry)
    }

    /** A / "Play": asks for the target whenever there is more than one. */
    fun play(entry: LibraryEntry) {
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

    fun context(entry: LibraryEntry) {
        contextMenuFor = entry
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
        if (runCatching { requester.requestFocus() }.isSuccess) actions.returnFocusId = null
    }
    return this.then(androidx.compose.ui.Modifier.focusRequester(requester))
}
