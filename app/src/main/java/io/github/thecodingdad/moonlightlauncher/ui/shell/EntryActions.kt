package io.github.thecodingdad.moonlightlauncher.ui.shell

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

    /** A on a tile: games open their detail page, apps start right away. */
    fun open(entry: LibraryEntry) {
        if (entry.item.kind == io.github.thecodingdad.moonlightlauncher.data.db.ItemKind.APP) play(entry) else openDetail(entry)
    }

    fun showDetail(entry: LibraryEntry) = openDetail(entry)

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
