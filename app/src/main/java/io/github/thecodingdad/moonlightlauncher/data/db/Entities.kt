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
    /** Groups (comma separated) and store/library name, from the companion data of the host API. */
    val playniteCategories: String? = null,
    val playnitePlugin: String? = null,
    // --- added in schema v3 ---
    val publisher: String? = null,
    /** Franchise or collection, e.g. "Super Mario". */
    val series: String? = null,
    /** Game modes / features, comma separated ("Single player, Co-operative"). */
    val features: String? = null,
    /** Age ratings of all systems, "PEGI:12;USK:12;ESRB:T". */
    val ageRatings: String? = null,
    /** First release (UTC millis); [releaseYear] stays the filterable year. */
    val releaseDate: Long? = null,
    /** Fields edited by hand ([MetaField] names, comma separated): never overwritten automatically. */
    val manualFields: String? = null,
    /** 1 = the IGDB fields of schema v3 were fetched for this match. */
    val metadataVersion: Int = 0,
    /** Apps: landscape tile for the start tab (SteamGridDB, own image); null = TV banner or generated. */
    val tileUrl: String? = null,
) {
    val manual: Set<MetaField> get() = manualFields.orEmpty().split(',').mapNotNull { n -> MetaField.entries.firstOrNull { it.name == n } }.toSet()
}

/** Metadata fields that can be edited (and then are protected against automatic updates). */
enum class MetaField { TITLE, DESCRIPTION, GENRES, DEVELOPER, PUBLISHER, SERIES, FEATURES, RELEASE, RATING, AGE_RATING }

/** Where [LibraryItemEntity.platform] came from; higher sources are never overwritten by lower ones. */
/** Trust order (later wins): PLAYNITE is the old Vibepollo/Playnite source, COMPANION the host API data. */
enum class PlatformSource { SUGGESTED, IGDB, PLAYNITE, COMPANION, MANUAL }

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
    /** False for an owned game the host entry only installs (companion data). */
    val installed: Boolean = true,
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
    /** Manual order inside the group; new members go to the end. */
    @androidx.room.ColumnInfo(defaultValue = "0") val position: Int = 0,
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
    /** Optional host web API base URL, e.g. `https://192.168.1.10:47990` (companion data). */
    val vibepolloUrl: String? = null,
    /** Host software of that API (HostApiType name); null = Vibepollo. */
    val apiType: String? = null,
    /** User name for Apollo / Sunshine logins. */
    val apiUser: String? = null,
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

    /** Only owned: no local app and every host entry would install the game first. */
    val notInstalled: Boolean get() = targets.isNotEmpty() && localTarget == null && remoteTargets.none { it.installed }

    /** "Start with…" only makes sense when the game is installed on more than one device/host. */
    val hasTargetChoice: Boolean get() = targets.count { it.type == TargetType.LOCAL || it.installed } > 1
}

/** Item without its automatic metadata (fields the user edited stay), ready for a new lookup. */
fun LibraryItemEntity.clearedMetadata(): LibraryItemEntity {
    val m = manual
    return copy(
        metadataState = MetadataState.NONE, matchConfidence = 0f, igdbId = null, sgdbId = null, metadataVersion = 0,
        description = description.takeIf { MetaField.DESCRIPTION in m },
        genres = genres.takeIf { MetaField.GENRES in m },
        developer = developer.takeIf { MetaField.DEVELOPER in m },
        publisher = publisher.takeIf { MetaField.PUBLISHER in m },
        series = series.takeIf { MetaField.SERIES in m },
        features = features.takeIf { MetaField.FEATURES in m },
        ageRatings = ageRatings.takeIf { MetaField.AGE_RATING in m },
        releaseYear = releaseYear.takeIf { MetaField.RELEASE in m },
        releaseDate = releaseDate.takeIf { MetaField.RELEASE in m },
        rating = rating.takeIf { MetaField.RATING in m },
        coverUrl = null, heroUrl = null, logoUrl = null, iconUrl = null, animatedCoverUrl = null, animatedHeroUrl = null,
    )
}
