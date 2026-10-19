package io.github.thecodingdad.gamelodge.ha

import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** When a game action runs. */
@Serializable
enum class GameActionTrigger { BEFORE_START, AFTER_END }

/**
 * A Home Assistant action ([HaAction] value: entity, for switches with on/off) around a game.
 * [delaySeconds]: before the start either the start waits that long after the action
 * ([startWaits]) or the action follows the start that much later; after the end the action
 * runs that much later (dropped if the next game starts first).
 */
@Serializable
data class GameAction(
    val action: String,
    val trigger: GameActionTrigger,
    val delaySeconds: Int = 0,
    val startWaits: Boolean = true,
)

/**
 * Game actions of the launcher: [global] for every game; a game listed in [games] uses its own
 * list instead (also an empty one: no actions for it). [hostStarts]: also when another device
 * starts a game through this PC as streaming host.
 */
@Serializable
data class GameActionSettings(
    val global: List<GameAction> = emptyList(),
    val games: Map<String, List<GameAction>> = emptyMap(),
    val hostStarts: Boolean = false,
) {
    fun actionsFor(itemId: String?): List<GameAction> = itemId?.let { games[it] } ?: global

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String): GameActionSettings = if (text.isBlank()) GameActionSettings() else runCatching { json.decodeFromString<GameActionSettings>(text) }.getOrDefault(GameActionSettings())
        fun encode(settings: GameActionSettings): String = json.encodeToString(settings)
    }
}

/**
 * Runs the game actions: those before a start (waiting where the start should wait) and, once
 * the game ended, those after it. A new start drops end actions still waiting for their delay.
 */
class GameActionRunner(
    /** Runs one [HaAction] value; false when it failed. */
    private val runAction: suspend (String) -> Boolean,
    private val settings: suspend () -> GameActionSettings,
    private val scope: CoroutineScope,
) {
    private val pendingEnd = mutableListOf<Job>()
    /** The game whose start ran its actions; its end runs the end actions once. */
    @Volatile private var running: String? = null

    /**
     * Before starting [itemId]: runs its start actions and returns once the start may go on
     * ([countdown] gets the seconds still to wait). Cancelling the caller cancels the wait.
     */
    suspend fun beforeStart(itemId: String?, countdown: (Int) -> Unit = {}) {
        synchronized(pendingEnd) { pendingEnd.forEach { it.cancel() }; pendingEnd.clear() }
        running = itemId ?: NO_ITEM
        val actions = settings().actionsFor(itemId).filter { it.trigger == GameActionTrigger.BEFORE_START }
        if (actions.isEmpty()) return
        var wait = 0
        actions.forEach { a ->
            if (a.startWaits || a.delaySeconds <= 0) {
                run(a)
                if (a.startWaits) wait = maxOf(wait, a.delaySeconds)
            } else {
                scope.launch { delay(a.delaySeconds * 1000L); run(a) }
            }
        }
        for (left in wait downTo 1) {
            countdown(left)
            delay(1000)
        }
    }

    /** The game started with [beforeStart] ended (no-op when none is running). */
    fun ended() {
        val itemId = running ?: return
        running = null
        scope.launch {
            settings().actionsFor(itemId.takeIf { it != NO_ITEM }).filter { it.trigger == GameActionTrigger.AFTER_END }.forEach { a ->
                if (a.delaySeconds <= 0) run(a)
                else synchronized(pendingEnd) { pendingEnd += scope.launch { delay(a.delaySeconds * 1000L); run(a) } }
            }
        }
    }

    private suspend fun run(a: GameAction) {
        if (!runAction(a.action)) Log.w("Game action ${a.action} failed")
    }

    private companion object {
        const val NO_ITEM = "-"
    }
}
