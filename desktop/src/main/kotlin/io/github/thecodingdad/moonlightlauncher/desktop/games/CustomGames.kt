package io.github.thecodingdad.moonlightlauncher.desktop.games

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A game or app added by hand: a program (or link) with its arguments. [cover]: an own image
 * file of the launcher (copied in, so nothing outside is needed).
 */
@Serializable
data class CustomGame(
    val id: String,
    val name: String,
    /** Program path, or a link (steam://, https:// …). */
    val path: String,
    val arguments: String? = null,
    val workingDir: String? = null,
    /** An app (Apps tab) instead of a game. */
    val app: Boolean = false,
    val cover: String? = null,
) {
    val isLink: Boolean get() = Regex("^[a-zA-Z][a-zA-Z0-9+.-]+://").containsMatchIn(path)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private val listSerializer = ListSerializer(serializer())

        fun decode(raw: String?): List<CustomGame> =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

        fun encode(list: List<CustomGame>): String = json.encodeToString(listSerializer, list)
    }
}

/** The games and apps added by hand, as a source of the PC library. */
class CustomGameScanner(private val games: () -> List<CustomGame>) : GameScanner {
    override val source = "custom"

    override suspend fun scan(): List<GameEntry> = games().map { g ->
        val dir = if (g.isLink) null else WinPath.directory(g.path)
        GameEntry(
            id = "custom:${g.id}", name = g.name, source = source, game = !g.app,
            installed = g.isLink || File(g.path).exists(),
            launch = g.path, launchArgs = g.arguments?.ifBlank { null }, workingDir = g.workingDir?.ifBlank { null } ?: dir,
            processDir = dir, processNames = if (g.isLink) emptyList() else listOf(WinPath.fileName(g.path)),
            covers = listOfNotNull(g.cover),
        )
    }
}
