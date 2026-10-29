package io.github.thecodingdad.gamelodge.data.companion

import kotlinx.serialization.Serializable

/** One game or app on the host as the companion delivers it (Companion.Protocol HostGame). */
@Serializable
data class HostGame(
    val id: String,
    val appUuid: String? = null,
    val name: String,
    val kind: String = "game",
    val platform: String? = null,
    val source: String? = null,
    val installed: Boolean = true,
    val emulator: String? = null,
    val collections: List<String> = emptyList(),
    /** Absolute URL or "image:<id>" (served by the companion). */
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    /** Animated cover / background (WebP or GIF), when there is one. */
    val animatedCover: String? = null,
    val animatedHero: String? = null,
    val igdbId: Long? = null,
    val steamGridDbId: Long? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val series: List<String> = emptyList(),
    val gameModes: List<String> = emptyList(),
    /** Unix seconds. */
    val releaseDate: Long? = null,
    /** Critic score 0–100. */
    val rating: Double? = null,
    /** Community score 0–100 (IGDB users). */
    val communityRating: Double? = null,
    val updated: Long = 0,
    /** The game's start actions on the host (GameLodge as host; "play" is the game itself). */
    val actions: List<HostGameAction> = emptyList(),
    /** The action a start uses unless another one is asked for; null = "play". */
    val defaultAction: String? = null,
    /**
     * Fields set by hand (on any device; names as in [HostGameCorrection.reset]): launchers take
     * these over their own values, everything else only fills what they don't have yet.
     */
    val manual: List<String> = emptyList(),
) {
    val isGame: Boolean get() = kind != "app"

    /** Display name of the store for the library filter ("Steam", "Epic Games" …). */
    val sourceLabel: String?
        get() = when (source) {
            null -> null
            "steam" -> "Steam"
            "epic" -> "Epic Games"
            "gog" -> "GOG"
            "xbox" -> "Xbox"
            "ea" -> "EA app"
            "ubisoft" -> "Ubisoft Connect"
            "battlenet" -> "Battle.net"
            "amazon" -> "Amazon Games"
            "emulator" -> emulator ?: "Emulator"
            "playnite" -> "Playnite"
            else -> source
        }
}

/** One way to start a game on the host (its own program, a launcher, a mod manager …). */
@Serializable
data class HostGameAction(val id: String, val name: String)

@Serializable
data class HostGamesList(val hostId: String? = null, val games: List<HostGame> = emptyList())

/**
 * A correction for the companion: only the fields set are changed and count as picked by hand;
 * [reset] hands fields back to the automatic choice (Companion.Protocol HostGameCorrection).
 */
@Serializable
data class HostGameCorrection(
    val igdbId: Long? = null,
    val steamGridDbId: Long? = null,
    val name: String? = null,
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    val animatedCover: String? = null,
    val animatedHero: String? = null,
    val summary: String? = null,
    val genres: List<String>? = null,
    val developers: List<String>? = null,
    val publishers: List<String>? = null,
    val releaseDate: Long? = null,
    val kind: String? = null,
    /** "name", "cover", "hero", "logo", "icon", "animatedCover", "animatedHero", "summary", "genres", "developers", "publishers", "release". */
    val reset: List<String>? = null,
    /** Fields chosen by hand to stay empty ("animatedCover", "animatedHero"). */
    val clear: List<String>? = null,
)

/**
 * A launcher streams from this PC's streaming host (POST /v1/stream): while active (repeated
 * every 30 s, expires after 90 s) the PC leaves controller buttons such as Guide to the client.
 * [resume]: the stream continues a running game, whose window the PC brings to the front.
 */
@Serializable
data class StreamNotice(
    val active: Boolean,
    val resume: Boolean = false,
    /** The streamed app ([HostGame.appUuid]) and the start action asked for ([HostGameAction.id]). */
    val appUuid: String? = null,
    val action: String? = null,
)
