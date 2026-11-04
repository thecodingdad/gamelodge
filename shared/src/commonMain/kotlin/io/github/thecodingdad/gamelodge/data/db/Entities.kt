package io.github.thecodingdad.gamelodge.data.db

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

/** LOCAL: installed Android app, REMOTE: app on a streaming host, ROM: game file for an emulator. */
enum class TargetType { LOCAL, REMOTE, ROM }

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
    /** Critic score 0–100 (IGDB's press average; older matches: its total rating). */
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
    /**
     * Landscape (16:9) tile: apps on the start tab and games with the landscape tile format
     * (SteamGridDB, Android TV banner, generated, own image). Null = automatic stand-in.
     */
    val tileUrl: String? = null,
    // --- added in schema v9 ---
    /** Time to beat from IGDB in seconds: main story and completely (100 %). */
    val timeToBeatMain: Int? = null,
    val timeToBeatComplete: Int? = null,
    /** The IGDB match these times belong to (looked up again after a new match). */
    val timeToBeatIgdbId: Long? = null,
    /** When the times were last looked up (IGDB adds them over time). */
    val timeToBeatCheckedAt: Long? = null,
    // --- added in schema v10 ---
    /** Square tile for the square tile format; null = automatic stand-in. */
    val squareUrl: String? = null,
    // --- added in schema v13 ---
    /** Community score 0–100 (IGDB's user rating). */
    val communityRating: Float? = null,
    /** The user's own rating, 1–5 stars (null = not rated). */
    val userRating: Int? = null,
    /** Hidden on this device only (not on the other GameLodges of its host); [hidden] includes it. */
    @androidx.room.ColumnInfo(defaultValue = "0") val hiddenHere: Boolean = false,
) {
    /** Times to beat of the current match only. */
    val timesToBeatCurrent: Boolean get() = igdbId != null && timeToBeatIgdbId == igdbId
    val manual: Set<MetaField> get() = manualFields.orEmpty().split(',').mapNotNull { n -> MetaField.entries.firstOrNull { it.name == n } }.toSet()
}

/** Metadata fields that can be edited (and then are protected against automatic updates). */
enum class MetaField(
    /** Its name in host game corrections / [io.github.thecodingdad.gamelodge.data.companion.HostGame.manual]; null: not shared. */
    val hostName: String? = null,
) {
    TITLE("name"), DESCRIPTION("summary"), GENRES("genres"), DEVELOPER("developers"), PUBLISHER("publishers"),
    SERIES, FEATURES, RELEASE("release"), RATING, AGE_RATING,
    // Images picked by hand.
    COVER("cover"), HERO("hero"), LOGO("logo"), ICON("icon"), ANIMATED_COVER("animatedCover"), ANIMATED_HERO("animatedHero"),
    LANDSCAPE, SQUARE,
}

/** Where [LibraryItemEntity.platform] came from; higher sources are never overwritten by lower ones. */
/** Trust order (later wins): PLAYNITE is the old Vibepollo/Playnite source, COMPANION the companion's host games. */
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
    /** `local:<package>`, `remote:<hostUuid>:<appId>` or `rom:<document uri>` */
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
    // --- added in schema v7 (ROMs) ---
    /** Document URI of the game file (in a folder granted via the storage access framework). */
    val romUri: String? = null,
    /** Real file path when known (needed by emulators that only take paths, e.g. RetroArch). */
    val romPath: String? = null,
    /** Catalog platform id ("snes", "psx" …). */
    val romPlatform: String? = null,
    /** Emulator chosen for this game; null = the platform's default. */
    val emulatorKey: String? = null,
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
    /** How long a stream start waits for the woken host (booting takes longer on some hardware). */
    @androidx.room.ColumnInfo(defaultValue = "120")
    val wakeTimeoutSeconds: Int = 120,
    /** VPN tunnel for streams to this host: null = automatic, [io.github.thecodingdad.gamelodge.vpn.NO_TUNNEL] = none. */
    val vpnTunnel: String? = null,
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
    val romTargets: List<LaunchTargetEntity> get() = targets.filter { it.type == TargetType.ROM }
    val boxArtPath: String? get() = targets.firstNotNullOfOrNull { it.boxArtPath }

    /** Only owned: nothing installed here (PC stores list owned games too) and every host entry would install the game first. */
    val notInstalled: Boolean get() = targets.isNotEmpty() && romTargets.isEmpty() && targets.none { it.installed && it.type != TargetType.ROM }

    /** Installed on this device (an Android app always is; a PC store game may only be owned). */
    val installedLocally: Boolean get() = targets.any { it.type == TargetType.LOCAL && it.installed }

    /** "Start with…" only makes sense when the game can start in more than one way (app, ROM, host). */
    val hasTargetChoice: Boolean get() = targets.count { it.type == TargetType.ROM || it.installed } > 1
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
        coverUrl = coverUrl.takeIf { MetaField.COVER in m }, animatedCoverUrl = animatedCoverUrl.takeIf { MetaField.ANIMATED_COVER in m },
        heroUrl = heroUrl.takeIf { MetaField.HERO in m }, animatedHeroUrl = animatedHeroUrl.takeIf { MetaField.ANIMATED_HERO in m },
        logoUrl = logoUrl.takeIf { MetaField.LOGO in m }, iconUrl = iconUrl.takeIf { MetaField.ICON in m },
        // App tiles were picked by hand before they were tracked as such.
        tileUrl = tileUrl.takeIf { MetaField.LANDSCAPE in m || kind == ItemKind.APP }, squareUrl = squareUrl.takeIf { MetaField.SQUARE in m },
    )
}


// --- Play data shared through a host --------------------------------------------------------------

/** The host's play sessions of its games, from every paired GameLodge and itself ([seq]: change order). */
@Entity(tableName = "hub_sessions", indices = [Index("seq"), Index("gameId")])
data class HubSessionEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val deviceId: String,
    val deviceName: String,
    val startedAt: Long,
    val seconds: Long,
    val kind: String,
    val seq: Long,
)

/** The host's flags of its games (rating, favourite, hidden everywhere); newest change wins. */
@Entity(tableName = "hub_flags", primaryKeys = ["gameId", "field"], indices = [Index("seq")])
data class HubFlagEntity(
    val gameId: String,
    val field: String,
    val value: String?,
    val changedAt: Long,
    val deviceId: String,
    val seq: Long,
)

/** The host's collection memberships of its games by collection name; newest change wins. */
@Entity(tableName = "hub_members", primaryKeys = ["collection", "gameId"], indices = [Index("seq")])
data class HubMemberEntity(
    val collection: String,
    val gameId: String,
    val member: Boolean,
    val changedAt: Long,
    val deviceId: String,
    val seq: Long,
)

/** A device's copy of a host's sessions ([hostKey]: the host; [itemId]: the library item, once known). */
@Entity(tableName = "sync_sessions", indices = [Index("hostKey"), Index("itemId")])
data class SyncSessionEntity(
    @PrimaryKey val id: String,
    val hostKey: String,
    val gameId: String,
    val itemId: String?,
    val deviceId: String,
    val deviceName: String,
    val startedAt: Long,
    val seconds: Long,
    val kind: String,
)

/** The value of a flag as last agreed with the host: a different local value is a change to send. */
@Entity(tableName = "sync_flags", primaryKeys = ["hostKey", "gameId", "field"])
data class SyncFlagEntity(
    val hostKey: String,
    val gameId: String,
    val field: String,
    val value: String?,
)

/** A collection membership as last agreed with the host. */
@Entity(tableName = "sync_members", primaryKeys = ["hostKey", "collection", "gameId"])
data class SyncMemberEntity(
    val hostKey: String,
    val collection: String,
    val gameId: String,
    val member: Boolean,
)

/** Per host: since when sessions are sent one by one (earlier ones went as one sum) and what was read last. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val hostKey: String,
    val syncStart: Long,
    val lastSeq: Long,
)
