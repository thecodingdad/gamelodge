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
    val rating: Double? = null,
    val updated: Long = 0,
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
