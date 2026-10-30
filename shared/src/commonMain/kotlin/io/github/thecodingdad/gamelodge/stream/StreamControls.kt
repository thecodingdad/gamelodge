package io.github.thecodingdad.gamelodge.stream

import io.github.thecodingdad.gamelodge.data.remote.HostState

/**
 * The stream this launcher runs right now, as far as the platform's streaming client lets the
 * quick menu steer it (Android: Artemis in this app, Windows: Moonlight-Qt).
 */
interface StreamControls {
    /** Commands the host offers (Apollo), sent through the stream. */
    fun serverCommands(): List<String> = emptyList()
    fun runServerCommand(index: Int) {}

    /** Leaves the stream; the game keeps running on the host. */
    fun disconnect() {}

    /** The stream is the app in front (its window has the controllers). */
    fun inFront(): Boolean = false

    /** Mouse emulation of the stream's controllers on / off; false without a stream. */
    fun setMouse(on: Boolean): Boolean = false

    /** Mouse emulation on / off like its button combination (a short notice); false without a stream. */
    fun toggleMouse(): Boolean = false

    /** The stream's performance overlay on / off; false without a stream. */
    fun toggleOverlay(): Boolean = false

    /**
     * Ends the stream and the game on [host] the way the client's own "quit" does (no "connection
     * ended" error). False when no stream of this launcher runs there.
     */
    suspend fun quit(host: HostState): Boolean = false
}

object NoStreamControls : StreamControls
