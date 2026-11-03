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
 * runs that much later (dropped if the next game starts first). [hostStarts]: also when another
 * device starts a game through this PC as streaming host.
 */
@Serializable
data class GameAction(
    val action: String,
    val trigger: GameActionTrigger,
    val delaySeconds: Int = 0,
    val startWaits: Boolean = true,
    val hostStarts: Boolean = false,
)

/**
 * Game actions of the launcher: [global] for every game; a game listed in [games] uses its own
 * list instead (also an empty one: no actions for it). [hostStarts]: the old switch for all
 * actions at once (read once into each action's own [GameAction.hostStarts]).
 */
@Serializable
data class GameActionSettings(
    val global: List<GameAction> = emptyList(),
    val games: Map<String, List<GameAction>> = emptyMap(),
    val hostStarts: Boolean = false,
) {
    fun actionsFor(itemId: String?): List<GameAction> = itemId?.let { games[it] } ?: global

    /** Only the actions that also run for starts through this PC as streaming host. */
    fun forHostStarts(): GameActionSettings =
        copy(global = global.filter { it.hostStarts }, games = games.mapValues { (_, list) -> list.filter { it.hostStarts } })

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String): GameActionSettings = if (text.isBlank()) GameActionSettings() else runCatching {
            val s = json.decodeFromString<GameActionSettings>(text)
            // The old switch for all actions: each action gets it.
            if (!s.hostStarts) s else GameActionSettings(
                global = s.global.map { it.copy(hostStarts = true) },
                games = s.games.mapValues { (_, list) -> list.map { it.copy(hostStarts = true) } },
            )
        }.getOrDefault(GameActionSettings())
        fun encode(settings: GameActionSettings): String = json.encodeToString(settings)
    }
}

/**
 * Runs an action after a delay, also while the app is in the background (Android freezes hidden
 * apps: the platform uses WorkManager there). [endOfGame] actions are dropped by [cancelEndOfGame].
 */
interface DelayedActions {
    fun schedule(action: String, delaySeconds: Int, endOfGame: Boolean)
    fun cancelEndOfGame()
}

/** Waits inside the app (desktop: it keeps running in the background). */
class CoroutineDelayedActions(private val scope: CoroutineScope, private val run: suspend (String) -> Unit) : DelayedActions {
    private val pendingEnd = mutableListOf<Job>()

    override fun schedule(action: String, delaySeconds: Int, endOfGame: Boolean) {
        val job = scope.launch { delay(delaySeconds * 1000L); run(action) }
        if (endOfGame) synchronized(pendingEnd) { pendingEnd += job }
    }

    override fun cancelEndOfGame() {
        synchronized(pendingEnd) { pendingEnd.forEach { it.cancel() }; pendingEnd.clear() }
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
    /** Where delayed actions wait; the platform may replace it (Android: WorkManager). */
    var delayed: DelayedActions = CoroutineDelayedActions(scope) { run(it) }

    /** The game whose start ran its actions; its end runs the end actions once. */
    @Volatile private var running: String? = null

    /**
     * Before starting [itemId]: runs its start actions and returns once the start may go on
     * ([countdown] gets the seconds still to wait). Cancelling the caller cancels the wait.
     */
    suspend fun beforeStart(itemId: String?, countdown: (Int) -> Unit = {}) {
        delayed.cancelEndOfGame()
        running = itemId ?: NO_ITEM
        val actions = settings().actionsFor(itemId).filter { it.trigger == GameActionTrigger.BEFORE_START }
        if (actions.isEmpty()) return
        var wait = 0
        actions.forEach { a ->
            if (a.startWaits || a.delaySeconds <= 0) {
                run(a.action)
                if (a.startWaits) wait = maxOf(wait, a.delaySeconds)
            } else {
                // After the start the game is in front: the launcher may be frozen meanwhile.
                delayed.schedule(a.action, a.delaySeconds, endOfGame = false)
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
                if (a.delaySeconds <= 0) run(a.action)
                else delayed.schedule(a.action, a.delaySeconds, endOfGame = true)
            }
        }
    }

    private suspend fun run(action: String) {
        if (!runAction(action)) Log.w("Game action $action failed")
    }

    private companion object {
        const val NO_ITEM = "-"
    }
}
