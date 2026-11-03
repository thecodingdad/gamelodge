package io.github.thecodingdad.gamelodge.metadata

import io.github.thecodingdad.gamelodge.data.library.PlatformResolver
import io.github.thecodingdad.gamelodge.data.library.TitleNormalizer

/**
 * When an automatic match may be taken without asking ("sure") and when it only goes to the
 * review list. A wrong match in a big library is hard to find later, so "sure" is strict:
 *  - the title is exactly the same (after normalising),
 *  - no other game of (almost) the same name is a candidate (original / remake, a reboot of the
 *    same name such as Tomb Raider 1996 / 2013),
 *  - the platform fits when the library knows it,
 *  - the kind fits: a remake or remaster only when the title says so (and an original not when
 *    it does), never a DLC, bundle, pack, episode or update,
 *  - the release year fits (±1) when the library knows it.
 * A store id (Steam app id, GOG id, Android package) that IGDB knows is sure on its own.
 */
object MatchRules {
    /** What IGDB says a game is, roughly. */
    enum class Kind { MAIN, REMAKE, REMASTER, EXTRA, OTHER }

    data class Context(val query: String, val platform: String? = null, val year: Int? = null)

    data class Decision(val game: IgdbGame, val score: Float, val sure: Boolean)

    /** Candidates within this of the best one count as the same name (ambiguous). */
    private const val SAME_NAME = 0.05f

    fun kindOf(game: IgdbGame): Kind {
        game.game_type?.type?.lowercase()?.let { t ->
            return when {
                "remake" in t -> Kind.REMAKE
                "remaster" in t -> Kind.REMASTER
                "main" in t -> Kind.MAIN
                listOf("dlc", "expansion", "bundle", "pack", "episode", "season", "update", "addon").any { it in t } -> Kind.EXTRA
                else -> Kind.OTHER
            }
        }
        return when (game.category) {
            null, 0 -> Kind.MAIN
            8 -> Kind.REMAKE
            9 -> Kind.REMASTER
            1, 2, 3, 6, 7, 13, 14 -> Kind.EXTRA
            else -> Kind.OTHER
        }
    }

    private val remakeWords = Regex("\\bremake\\b", RegexOption.IGNORE_CASE)
    private val remasterWords = Regex("\\b(remaster(ed)?|definitive edition|enhanced edition|anniversary( edition)?|hd)\\b", RegexOption.IGNORE_CASE)

    /** The kind the library title names ("… Remake", "… Remastered"), MAIN otherwise. */
    fun kindOfTitle(title: String): Kind = when {
        remakeWords.containsMatchIn(title) -> Kind.REMAKE
        remasterWords.containsMatchIn(title) -> Kind.REMASTER
        else -> Kind.MAIN
    }

    private fun kindFits(title: String, game: IgdbGame): Boolean {
        val wanted = kindOfTitle(title)
        return when (kindOf(game)) {
            Kind.EXTRA -> false
            Kind.REMAKE -> wanted == Kind.REMAKE
            Kind.REMASTER -> wanted == Kind.REMASTER
            // The title names a new version, the candidate is the original: not sure.
            Kind.MAIN, Kind.OTHER -> wanted == Kind.MAIN
        }
    }

    fun platformFits(platform: String?, game: IgdbGame): Boolean =
        platform == null || game.platforms.isEmpty() || game.platforms.any { PlatformResolver.normalizeIgdb(it.name) == platform }

    private fun yearFits(year: Int?, game: IgdbGame): Boolean {
        val released = game.releaseYear ?: return true
        return year == null || kotlin.math.abs(released - year) <= 1
    }

    private fun exactName(query: String, game: IgdbGame): Boolean {
        val q = TitleNormalizer.normalize(query)
        return q.isNotEmpty() && game.allNames.any { TitleNormalizer.normalize(it) == q }
    }

    /**
     * The best of [scored] (games with their score for [context]'s title) and whether it is sure;
     * null when nothing reaches [MatchScorer.REVIEW].
     */
    fun decide(context: Context, scored: List<Pair<IgdbGame, Float>>): Decision? {
        val ranked = scored.distinctBy { it.first.id }.sortedByDescending { it.second }
        val (best, score) = ranked.firstOrNull() ?: return null
        if (score < MatchScorer.REVIEW) return null
        // Another real game of (almost) the same name on this platform: the user picks.
        val sameName = ranked.drop(1).any { (g, s) ->
            !g.isUnofficial && kindOf(g) != Kind.EXTRA && platformFits(context.platform, g) && s >= score - SAME_NAME
        }
        val sure = score >= MatchScorer.AUTO_ACCEPT &&
            exactName(context.query, best) &&
            !best.isUnofficial &&
            !sameName &&
            platformFits(context.platform, best) &&
            kindFits(context.query, best) &&
            yearFits(context.year, best)
        return Decision(best, score, sure)
    }

    /** IGDB's external game source of a store id ("steam:123" → Steam, 123); null for others. */
    fun storeId(id: String): Pair<Int, String>? {
        val (store, uid) = id.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        if (uid.isBlank()) return null
        return when (store.lowercase()) {
            "steam" -> STEAM to uid
            "gog" -> GOG to uid
            else -> null
        }
    }

    const val STEAM = 1
    const val GOG = 5
    const val ANDROID = 15
}
