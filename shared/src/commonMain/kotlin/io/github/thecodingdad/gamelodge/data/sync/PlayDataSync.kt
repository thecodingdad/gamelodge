package io.github.thecodingdad.gamelodge.data.sync

import io.github.thecodingdad.gamelodge.data.companion.CollectionMemberData
import io.github.thecodingdad.gamelodge.data.companion.PlayDataChanges
import io.github.thecodingdad.gamelodge.data.companion.PlayFlagData
import io.github.thecodingdad.gamelodge.data.companion.PlaySessionData
import io.github.thecodingdad.gamelodge.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.LibraryItemEntity
import io.github.thecodingdad.gamelodge.data.db.SyncFlagEntity
import io.github.thecodingdad.gamelodge.data.db.SyncMemberEntity
import io.github.thecodingdad.gamelodge.data.db.SyncSessionEntity
import io.github.thecodingdad.gamelodge.data.db.SyncStateEntity
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** How a device reaches a host's play data (over the network, or the host's own store). */
interface PlayDataTransport {
    /** Changes after [after]; null when the host can't be reached. */
    suspend fun pull(after: Long): PlayDataChanges?
    /** False when the host can't be reached or refused them. */
    suspend fun push(changes: PlayDataChanges): Boolean
}

/**
 * One host to sync with: [hostKey] names it in the local copy, [games] maps its game ids to this
 * device's library items, [gameOfTarget] the launch target of a local session to the host game.
 */
class SyncHost(
    val hostKey: String,
    val transport: PlayDataTransport,
    val games: Map<String, String>,
    val gameOfTarget: Map<String, String>,
)

/**
 * Keeps this device's play data of host games in step with the hosts (docs/PLAYDATA_SYNC_PLAN.md):
 * sends new sessions and changed flags / collection memberships, reads everything the other devices
 * sent, and sets playtime and last played of those items to the total of all devices.
 */
class PlayDataSync(
    private val db: LauncherDatabase,
    /** Stable id of this installation (not part of backups). */
    private val installId: () -> String,
    /** Creates a collection (group) named so; returns its id. */
    private val createCollection: suspend (String) -> String,
    /** This device's name, as the hosts list it (pairing). */
    private val deviceName: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val dao get() = db.playDataDao()
    private val library get() = db.libraryDao()

    suspend fun sync(host: SyncHost): Boolean = mutex.withLock {
        runCatching { syncLocked(host) }.onFailure { Log.w("Play data sync with ${host.hostKey} failed", it) }.getOrDefault(false)
    }

    private suspend fun syncLocked(host: SyncHost): Boolean {
        val now = clock()
        val state = dao.state(host.hostKey)
        val syncStart = state?.syncStart ?: now
        var after = state?.lastSeq ?: 0
        suspend fun pullAll(keep: Boolean = true): Boolean {
            while (true) {
                val changes = host.transport.pull(after) ?: return false
                apply(host, changes)
                after = changes.seq
                if (keep) dao.upsertState(SyncStateEntity(host.hostKey, syncStart, after))
                if (!changes.more) return true
            }
        }
        // The first time the host's data comes first: a device of this name may have sent its playtime
        // before (a new installation restored from a backup without sync state). The state is kept only
        // once the earlier playtime is sent (else the next sync is the first one again).
        val first = state == null
        if (first && !pullAll(keep = false)) return false
        // Read after that: what the host's data changed here isn't a change of this device.
        val items = host.games.mapValues { (_, itemId) -> library.getItem(itemId) }.filterValues { it != null }.mapValues { it.value!! }

        // 1. What changed here.
        val sessions = outgoingSessions(host, items, first, syncStart)
        val flagSnapshot = dao.flags(host.hostKey).associateBy { it.gameId to it.field }
        val flags = outgoingFlags(items, flagSnapshot, now)
        val memberSnapshot = dao.members(host.hostKey).associateBy { it.collection to it.gameId }
        val members = outgoingMembers(host, memberSnapshot, now)
        val out = PlayDataChanges(sessions = sessions, flags = flags, memberships = members)
        if (!out.isEmpty) {
            // In pages; everything has to arrive before the copy counts it as agreed.
            for (page in pages(out)) if (!host.transport.push(page)) return false
            dao.upsertFlags(flags.map { SyncFlagEntity(host.hostKey, it.gameId, it.field, it.value) })
            dao.upsertMembers(members.map { SyncMemberEntity(host.hostKey, it.collection, it.gameId, it.member) })
        }
        dao.upsertState(SyncStateEntity(host.hostKey, syncStart, after))

        // 2. What changed elsewhere (own changes come back too: they are the host's now).
        if (!pullAll()) return false

        // 3. Playtime and last played as all devices played.
        host.games.forEach { (gameId, itemId) -> dao.linkSessions(host.hostKey, gameId, itemId) }
        val linked = dao.linkedSessions().groupBy { it.itemId!! }
        for (itemId in host.games.values.toSet()) {
            val list = linked[itemId] ?: continue
            val item = library.getItem(itemId) ?: continue
            val last = listOfNotNull(list.maxOfOrNull { it.startedAt }.takeIf { list.any { s -> s.kind == PlaySessionData.KIND_PLAY } }, item.lastPlayedAt).maxOrNull()
            library.setPlayStats(itemId, list.sumOf { it.seconds }, last)
        }
        return true
    }

    private suspend fun outgoingSessions(host: SyncHost, items: Map<String, LibraryItemEntity>, first: Boolean, syncStart: Long): List<PlaySessionData> {
        val known = dao.sessionIds(host.hostKey).toHashSet()
        val out = mutableListOf<PlaySessionData>()
        // The first time: this device's playtime so far, once per game (the sessions behind it aren't sent).
        // Not for games a device of the same name already has sessions of: that is this device before,
        // and the playtime here came from it (a backup, or the total it showed after syncing).
        if (first) {
            val name = deviceName().trim()
            val sent = if (name.isEmpty()) emptySet() else dao.gamesPlayedBy(host.hostKey, name).toHashSet()
            items.forEach { (gameId, item) ->
                if (item.playtimeSeconds > 0 && gameId !in sent) {
                    out += PlaySessionData(id("earlier:${host.hostKey}:$gameId"), gameId, item.lastPlayedAt ?: clock(), item.playtimeSeconds, PlaySessionData.KIND_EARLIER)
                }
            }
        }
        for (s in db.sessionDao().finishedSince(syncStart)) {
            val gameId = host.gameOfTarget[s.targetId] ?: continue
            val seconds = ((s.endedAt ?: continue) - s.startedAt) / 1000
            // Like the local playtime: shorter starts were accidental.
            if (seconds < MIN_SESSION_SECONDS) continue
            val id = id("session:${s.id}:${s.startedAt}")
            if (id in known) continue
            out += PlaySessionData(id, gameId, s.startedAt, seconds.coerceAtMost(PlayDataHub.MAX_SESSION_SECONDS))
        }
        return out
    }

    private fun outgoingFlags(items: Map<String, LibraryItemEntity>, snapshot: Map<Pair<String, String>, SyncFlagEntity>, now: Long): List<PlayFlagData> {
        val out = mutableListOf<PlayFlagData>()
        items.forEach { (gameId, item) ->
            val agreedHidden = snapshot[gameId to PlayFlagData.FIELD_HIDDEN]?.value
            val local = mapOf(
                PlayFlagData.FIELD_RATING to item.userRating?.toString(),
                PlayFlagData.FIELD_FAVORITE to if (item.favorite) "1" else "0",
                // Hidden on this device only: what all devices agreed stays.
                PlayFlagData.FIELD_HIDDEN to when {
                    !item.hidden -> "0"
                    item.hiddenHere -> agreedHidden ?: "0"
                    else -> "1"
                },
            )
            local.forEach { (field, value) ->
                val agreed = snapshot[gameId to field]
                when {
                    agreed != null && agreed.value != value -> out += PlayFlagData(gameId, field, value, now)
                    // Never agreed: only what was set here, and older than any change on another device.
                    agreed == null && value != null && value != "0" -> out += PlayFlagData(gameId, field, value, INITIAL_TIME)
                }
            }
        }
        return out
    }

    private suspend fun outgoingMembers(host: SyncHost, snapshot: Map<Pair<String, String>, SyncMemberEntity>, now: Long): List<CollectionMemberData> {
        val itemToGame = host.games.entries.associate { (gameId, itemId) -> itemId to gameId }
        val names = db.collectionDao().getCollections().associate { it.id to it.name.trim() }
        val local = db.collectionDao().getMemberships().mapNotNull { m ->
            val gameId = itemToGame[m.itemId] ?: return@mapNotNull null
            val name = names[m.collectionId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to gameId
        }.toSet()
        val out = mutableListOf<CollectionMemberData>()
        for ((name, gameId) in local) {
            val agreed = snapshot[name to gameId]
            if (agreed == null) out += CollectionMemberData(name, gameId, true, INITIAL_TIME)
            else if (!agreed.member) out += CollectionMemberData(name, gameId, true, now)
        }
        for ((key, agreed) in snapshot) {
            if (agreed.member && key !in local && key.second in host.games) out += CollectionMemberData(key.first, key.second, false, now)
        }
        return out
    }

    private suspend fun apply(host: SyncHost, c: PlayDataChanges) {
        if (c.sessions.isNotEmpty()) {
            dao.upsertSessions(c.sessions.map { s ->
                SyncSessionEntity(s.id, host.hostKey, s.gameId, host.games[s.gameId], s.deviceId.orEmpty(), s.deviceName.orEmpty(), s.startedAt, s.seconds, s.kind)
            })
        }
        for (f in c.flags) {
            dao.upsertFlags(listOf(SyncFlagEntity(host.hostKey, f.gameId, f.field, f.value)))
            val itemId = host.games[f.gameId] ?: continue
            when (f.field) {
                PlayFlagData.FIELD_RATING -> library.setUserRating(itemId, f.value?.toIntOrNull()?.coerceIn(1, 5))
                PlayFlagData.FIELD_FAVORITE -> library.setFavorite(itemId, f.value == "1")
                PlayFlagData.FIELD_HIDDEN -> {
                    val item = library.getItem(itemId) ?: continue
                    library.setHiddenState(itemId, f.value == "1" || item.hiddenHere, item.hiddenHere)
                }
            }
        }
        if (c.memberships.isNotEmpty()) {
            val collections = db.collectionDao()
            for (m in c.memberships) {
                dao.upsertMembers(listOf(SyncMemberEntity(host.hostKey, m.collection, m.gameId, m.member)))
                val itemId = host.games[m.gameId] ?: continue
                val existing = collections.getCollections().firstOrNull { it.name.trim().equals(m.collection, ignoreCase = true) }
                if (m.member) {
                    val id = existing?.id ?: createCollection(m.collection)
                    if (collections.getMemberships().none { it.itemId == itemId && it.collectionId == id }) {
                        collections.addItem(ItemCollectionCrossRef(itemId, id, collections.maxPosition(id) + 1))
                    }
                } else if (existing != null) {
                    collections.removeItem(ItemCollectionCrossRef(itemId, existing.id))
                }
            }
        }
    }

    private fun pages(c: PlayDataChanges): List<PlayDataChanges> {
        val all: List<Any> = c.sessions + c.flags + c.memberships
        return all.chunked(PlayDataHub.PAGE).map { chunk ->
            PlayDataChanges(
                sessions = chunk.filterIsInstance<PlaySessionData>(),
                flags = chunk.filterIsInstance<PlayFlagData>(),
                memberships = chunk.filterIsInstance<CollectionMemberData>(),
            )
        }
    }

    /** A stable id for [key] on this installation (the same session always gets the same id). */
    private fun id(key: String): String = UUID.nameUUIDFromBytes("${installId()}:$key".toByteArray()).toString()

    companion object {
        /** The play sync of a container: install id from [secrets], new collections as groups of [filters]. */
        fun create(
            db: LauncherDatabase,
            secrets: io.github.thecodingdad.gamelodge.data.settings.SecretStore,
            deviceName: () -> String,
            filters: () -> io.github.thecodingdad.gamelodge.data.library.FilterRepository,
        ) = PlayDataSync(
            db,
            installId = {
                secrets.get(io.github.thecodingdad.gamelodge.data.settings.SecretStore.Keys.PLAY_SYNC_ID)
                    ?: UUID.randomUUID().toString().also { secrets.put(io.github.thecodingdad.gamelodge.data.settings.SecretStore.Keys.PLAY_SYNC_ID, it) }
            },
            createCollection = { name -> filters().createGroup(name, io.github.thecodingdad.gamelodge.data.db.FilterScope.BOTH).id },
            deviceName = deviceName,
        )

        /** Device id of streams the host counted itself (clients without GameLodge). */
        const val OTHER_CLIENTS = "other"

        /** Like the local playtime (PlaytimeTracker): shorter starts don't count. */
        const val MIN_SESSION_SECONDS = 60L
        /** Changed at "the beginning": loses against every change made on a device. */
        const val INITIAL_TIME = 1L
    }
}
