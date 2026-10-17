package io.github.thecodingdad.gamelodge.data.library

import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.PlaySessionEntity

/**
 * Records play sessions. A session starts when the launcher starts a game or stream and
 * ends when the launcher becomes visible again (the user returned from the game).
 */
class PlaytimeTracker(
    private val db: LauncherDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Sessions shorter than this are accidental starts and don't count as "played". */
    private val minimumSessionMs = 60_000L

    suspend fun start(itemId: String, targetId: String, profileName: String?) {
        finishOpenSession()
        val now = clock()
        db.sessionDao().insert(PlaySessionEntity(itemId = itemId, targetId = targetId, profileName = profileName, startedAt = now))
        db.libraryDao().setLastPlayed(itemId, now)
    }

    /** Called whenever the launcher returns to the foreground; returns the session it closed. */
    suspend fun finishOpenSession(): PlaySessionEntity? {
        val open = db.sessionDao().getOpenSession() ?: return null
        val now = clock()
        db.sessionDao().close(open.id, now)
        val duration = now - open.startedAt
        if (duration >= minimumSessionMs) db.libraryDao().addPlaytime(open.itemId, duration / 1000)
        return open.copy(endedAt = now)
    }
}
