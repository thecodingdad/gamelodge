package io.github.thecodingdad.moonlightlauncher.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class ItemKind { GAME, APP }

/** Whether [ItemKind] was decided by the classifier or pinned by the user. */
enum class KindSource { AUTO, MANUAL }

enum class MetadataState {
    /** Not looked up yet (or no API keys configured). */
    NONE,
    /** Lookup ran, nothing usable found. */
    NOT_FOUND,
    /** Confident match applied automatically. */
    OK,
    /** Low-confidence match: shown in the "please review" list. */
    REVIEW,
    /** Chosen manually by the user; never overwritten automatically. */
    LOCKED,
}

enum class TargetType { LOCAL, REMOTE }

@Entity(
    tableName = "library_items",
    indices = [Index("normalizedTitle"), Index("kind")],
)
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val normalizedTitle: String,
    val kind: ItemKind,
    val kindSource: KindSource = KindSource.AUTO,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val addedAt: Long,
    val lastPlayedAt: Long? = null,
    val playtimeSeconds: Long = 0,
    // --- metadata (1:1, embedded for simple queries) ---
    val metadataState: MetadataState = MetadataState.NONE,
    val matchConfidence: Float = 0f,
    val igdbId: Long? = null,
    val sgdbId: Long? = null,
    val description: String? = null,
    val genres: String? = null,
    val developer: String? = null,
    val releaseYear: Int? = null,
    val rating: Float? = null,
    val coverUrl: String? = null,
    val heroUrl: String? = null,
    val logoUrl: String? = null,
    val iconUrl: String? = null,
    val animatedCoverUrl: String? = null,
    val animatedHeroUrl: String? = null,
    /** Title used for metadata lookup when it differs from [title] (manual search). */
    val metadataQuery: String? = null,
    // --- added in schema v2 ---
    /** Platform shown/filtered on, e.g. "PC", "Nintendo Switch", "Android". */
    val platform: String? = null,
    val platformSource: PlatformSource? = null,
    /** Playnite categories (comma separated) and library plugin, via the Vibepollo API. */
    val playniteCategories: String? = null,
    val playnitePlugin: String? = null,
)

/** Where [LibraryItemEntity.platform] came from; higher sources are never overwritten by lower ones. */
enum class PlatformSource { SUGGESTED, IGDB, PLAYNITE, MANUAL }

enum class FilterKind { BUILTIN, SMART, GROUP }

enum class FilterScope { GAMES, APPS, BOTH }

/**
 * A library filter. SMART filters match rules (the default filters too: they keep their key in
 * [builtin] for the localized name), GROUP filters are manual groups whose members live in
 * item_collections (id = collection id). BUILTIN is the old form of default filters (migrated).
 */
@Entity(tableName = "filters")
data class FilterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: FilterKind,
    /** Default filter key (all, local, hosts, favorites …), null for own filters. */
    val builtin: String? = null,
    /** JSON-encoded FilterRules for SMART filters. */
    val rules: String? = null,
    val scope: FilterScope = FilterScope.GAMES,
    val showInLibrary: Boolean = true,
    val showOnHome: Boolean = false,
    val showInApps: Boolean = false,
    val sortOrder: Int = 0,
)

/** One way to start an item: an installed Android app or an app on a streaming host. */
@Entity(
    tableName = "launch_targets",
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId"), Index("hostUuid")],
)
data class LaunchTargetEntity(
    /** `local:<package>` or `remote:<hostUuid>:<appId>` */
    @PrimaryKey val id: String,
    val itemId: String,
    val type: TargetType,
    val name: String,
    val packageName: String? = null,
    val hostUuid: String? = null,
    val hostName: String? = null,
    val appId: Int? = null,
    val appUuid: String? = null,
    val hdrSupported: Boolean = false,
    /** Local file with the host-provided box art (fallback cover). */
    val boxArtPath: String? = null,
    val lastSeenAt: Long,
)

@Entity(
    tableName = "play_sessions",
    indices = [Index("itemId"), Index("startedAt")],
)
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val targetId: String,
    val profileName: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "item_collections",
    primaryKeys = ["itemId", "collectionId"],
    foreignKeys = [
        ForeignKey(LibraryItemEntity::class, ["id"], ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CollectionEntity::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collectionId")],
)
data class ItemCollectionCrossRef(
    val itemId: String,
    val collectionId: String,
)

enum class WakeMethod { NONE, WOL, HOME_ASSISTANT, BOTH }

/** Launcher-side settings per streaming host (Artemis keeps address/pairing itself). */
@Entity(tableName = "host_config")
data class HostConfigEntity(
    @PrimaryKey val hostUuid: String,
    val wakeMethod: WakeMethod = WakeMethod.WOL,
    /** Home Assistant action used to wake the host, e.g. `script.start_gaming_vm`. */
    val haWakeEntity: String? = null,
    val haShutdownEntity: String? = null,
    val haRestartEntity: String? = null,
    val haSleepEntity: String? = null,
    /** Minutes after a stream ends before the host is put to sleep; null = never. */
    val autoSleepMinutes: Int? = null,
    /** Optional Vibepollo web API base URL, e.g. `https://192.168.1.10:47990`. */
    val vibepolloUrl: String? = null,
)

enum class HaTileType { SWITCH, LIGHT, BUTTON, SENSOR, COVER, CLIMATE }

@Entity(tableName = "ha_tiles")
data class HaTileEntity(
    @PrimaryKey val entityId: String,
    val type: HaTileType,
    val label: String? = null,
    val sortOrder: Int,
    val showOnHome: Boolean = false,
)

/** An item with all its launch targets, as consumed by the UI. */
data class LibraryEntry(
    @Embedded val item: LibraryItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val targets: List<LaunchTargetEntity>,
) {
    val localTarget: LaunchTargetEntity? get() = targets.firstOrNull { it.type == TargetType.LOCAL }
    val remoteTargets: List<LaunchTargetEntity> get() = targets.filter { it.type == TargetType.REMOTE }
    val boxArtPath: String? get() = targets.firstNotNullOfOrNull { it.boxArtPath }
}
