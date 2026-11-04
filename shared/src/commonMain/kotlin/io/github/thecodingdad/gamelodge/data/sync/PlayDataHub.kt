package io.github.thecodingdad.gamelodge.data.sync

import io.github.thecodingdad.gamelodge.data.db.withTransaction
import io.github.thecodingdad.gamelodge.data.companion.CollectionMemberData
import io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges
import io.github.thecodingdad.gamelodge.data.companion.PlayFlagData
import io.github.thecodingdad.gamelodge.data.companion.PlaySessionData
import io.github.thecodingdad.gamelodge.data.db.HubFlagEntity
import io.github.thecodingdad.gamelodge.data.db.HubMemberEntity
import io.github.thecodingdad.gamelodge.data.db.HubSessionEntity
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The host's store of its games' play data: what its paired GameLodges
 * and the host itself send, handed out by change order ([changesAfter]). Sessions are kept once per
 * id; flags and memberships keep the newest change. Only the host's own games are accepted.
 */
class PlayDataHub(
    private val db: LauncherDatabase,
    /** Ids of the games the host offers right now. */
    private val games: suspend () -> Set<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.playDataDao()
    private val mutex = Mutex()

    /** Up to [limit] changes after [after], oldest first. */
    suspend fun changesAfter(after: Long, limit: Int = PAGE): PlayDataChanges {
        val sessions = dao.hubSessionsAfter(after, limit + 1).map { it.seq to it }
        val flags = dao.hubFlagsAfter(after, limit + 1).map { it.seq to it }
        val members = dao.hubMembersAfter(after, limit + 1).map { it.seq to it }
        val all = (sessions + flags + members).sortedBy { it.first }
        val page = all.take(limit)
        return PlayDataChanges(
            seq = page.lastOrNull()?.first ?: after,
            more = all.size > limit,
            sessions = page.mapNotNull { (it.second as? HubSessionEntity)?.let { s -> PlaySessionData(s.id, s.gameId, s.startedAt, s.seconds, s.kind, s.deviceId, s.deviceName) } },
            flags = page.mapNotNull { (it.second as? HubFlagEntity)?.let { f -> PlayFlagData(f.gameId, f.field, f.value, f.changedAt, f.deviceId) } },
            memberships = page.mapNotNull { (it.second as? HubMemberEntity)?.let { m -> CollectionMemberData(m.collection, m.gameId, m.member, m.changedAt, m.deviceId) } },
        )
    }

    /** Takes [changes] from the device [deviceId]; false (nothing taken) when anything in them is invalid. */
    suspend fun apply(deviceId: String, deviceName: String, changes: PlayDataChanges): Boolean = mutex.withLock {
        val known = games()
        if (!valid(changes, known, clock())) return@withLock false
        db.withTransaction {
            var seq = dao.maxSeq() ?: 0L
            for (s in changes.sessions) {
                val existing = dao.hubSession(s.id)
                // A session stays as it came; only a device's own earlier playtime may be sent again.
                if (existing != null && !(existing.deviceId == deviceId && existing.kind == PlaySessionData.KIND_EARLIER && s.kind == PlaySessionData.KIND_EARLIER)) continue
                dao.upsertHubSession(HubSessionEntity(s.id, s.gameId, deviceId, deviceName, s.startedAt, s.seconds, s.kind, ++seq))
            }
            for (f in changes.flags) {
                val existing = dao.hubFlag(f.gameId, f.field)
                // The newer change wins; a losing one still renews the winner, so the device reads it back.
                val winner = if (existing == null || f.changedAt >= existing.changedAt) HubFlagEntity(f.gameId, f.field, f.value, f.changedAt, deviceId, 0) else existing
                dao.upsertHubFlag(winner.copy(seq = ++seq))
            }
            for (m in changes.memberships) {
                val name = m.collection.trim()
                val existing = dao.hubMember(name, m.gameId)
                val winner = if (existing == null || m.changedAt >= existing.changedAt) HubMemberEntity(name, m.gameId, m.member, m.changedAt, deviceId, 0) else existing
                dao.upsertHubMember(winner.copy(seq = ++seq))
            }
        }
        true
    }

    companion object {
        /** Changes per request and per answer. */
        const val PAGE = 1000
        /** A real session is at most a day long. */
        const val MAX_SESSION_SECONDS = 24L * 3600
        /** Earlier playtime: at most ten years. */
        const val MAX_EARLIER_SECONDS = 10L * 365 * 24 * 3600
        /** Clocks of devices may be a little ahead. */
        const val FUTURE_TOLERANCE_MS = 5 * 60_000L

        private val idPattern = Regex("[A-Za-z0-9-]{1,64}")

        fun valid(c: PlayDataChanges, known: Set<String>, now: Long): Boolean {
            if (c.size > PAGE) return false
            val latest = now + FUTURE_TOLERANCE_MS
            for (s in c.sessions) {
                if (!idPattern.matches(s.id) || s.gameId !in known || s.startedAt !in 0..latest) return false
                val max = when (s.kind) {
                    PlaySessionData.KIND_PLAY -> MAX_SESSION_SECONDS
                    PlaySessionData.KIND_EARLIER -> MAX_EARLIER_SECONDS
                    else -> return false
                }
                if (s.seconds !in 1..max) return false
            }
            for (f in c.flags) {
                if (f.gameId !in known || f.field !in PlayFlagData.fields || f.changedAt !in 0..latest) return false
                val ok = when (f.field) {
                    PlayFlagData.FIELD_RATING -> f.value == null || f.value in setOf("1", "2", "3", "4", "5")
                    else -> f.value == "0" || f.value == "1"
                }
                if (!ok) return false
            }
            for (m in c.memberships) {
                val name = m.collection.trim()
                if (m.gameId !in known || name.isEmpty() || name.length > 100 || m.changedAt !in 0..latest) return false
            }
            return true
        }
    }
}
