package io.github.thecodingdad.gamelodge.ha

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameActionRunnerTest {
    private val ran = mutableListOf<String>()
    private fun TestScope.runner(settings: GameActionSettings) = GameActionRunner({ ran += it; true }, { settings }, backgroundScope)

    @Test
    fun `the start waits for its delay, a delayed action follows the start`() = runTest {
        val r = runner(
            GameActionSettings(
                global = listOf(
                    GameAction("light.desk#off", GameActionTrigger.BEFORE_START, delaySeconds = 5, startWaits = true),
                    GameAction("script.tv", GameActionTrigger.BEFORE_START, delaySeconds = 10, startWaits = false),
                ),
            ),
        )
        val counted = mutableListOf<Int>()
        var started = false
        launch { r.beforeStart("g1") { counted += it }; started = true }
        runCurrent()
        assertEquals(listOf("light.desk#off"), ran)
        advanceTimeBy(4_000); runCurrent()
        assertTrue(!started)
        advanceTimeBy(1_100); runCurrent()
        assertTrue(started)
        assertEquals(listOf(5, 4, 3, 2, 1), counted)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("light.desk#off", "script.tv"), ran)
    }

    @Test
    fun `a delayed end action is dropped when the next game starts first`() = runTest {
        val r = runner(GameActionSettings(global = listOf(GameAction("light.desk#on", GameActionTrigger.AFTER_END, delaySeconds = 300))))
        r.beforeStart("g1")
        r.ended()
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        r.beforeStart("g2")
        advanceTimeBy(600_000); runCurrent()
        assertTrue(ran.isEmpty())
        r.ended()
        advanceTimeBy(301_000); runCurrent()
        assertEquals(listOf("light.desk#on"), ran)
    }

    @Test
    fun `a game's own actions replace the global ones, also when empty`() = runTest {
        val r = runner(
            GameActionSettings(
                global = listOf(GameAction("scene.gaming", GameActionTrigger.BEFORE_START)),
                games = mapOf("quiet" to emptyList(), "loud" to listOf(GameAction("scene.party", GameActionTrigger.BEFORE_START))),
            ),
        )
        r.beforeStart("quiet"); r.beforeStart("loud"); r.beforeStart("other")
        runCurrent()
        assertEquals(listOf("scene.party", "scene.gaming"), ran)
        // An end without a start runs nothing.
        ran.clear(); r.ended(); r.ended(); runCurrent()
        assertTrue(ran.isEmpty())
    }
}
