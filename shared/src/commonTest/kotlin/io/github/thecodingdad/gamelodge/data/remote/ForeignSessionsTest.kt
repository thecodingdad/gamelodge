package io.github.thecodingdad.gamelodge.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignSessionsTest {
    private fun synthetic(id: Int, name: String, n: Int) = HostApp(id, name, "9a1c5a25-58fe-40e0-b9aa-7d3f0000000$n", false)
    private val desktop = HostApp(1, "Desktop", "4B2E1A10-0000-0000-0000-000000000001", false)
    private val steam = HostApp(2, "Steam Big Picture", "4B2E1A10-0000-0000-0000-000000000002", false)
    private val copy = synthetic(1073741825, "     Desktop", 5)
    private val resume = synthetic(90001, "  Resume", 1)
    private val terminate = synthetic(90002, " Terminate", 2)
    private val input = synthetic(90003, "Remote Input", 3)
    private val monitor = synthetic(90004, "Remote Monitor", 4)

    private fun state(running: Int) = HostState("h", "Host", HostStatus.ONLINE, true, running, null, null, false, emptyList(), -1)
    private fun remote(vararg apps: HostApp) = apps.map { RemoteApp("h", "Host", it) }

    @Test
    fun anotherClientsGameIsFoundThroughItsCopy() {
        val found = ForeignSessions.running(listOf(desktop, copy, resume, terminate, steam, input, monitor))
        assertEquals(desktop to copy, found)
    }

    @Test
    fun noCopyNoForeignGame() {
        assertNull(ForeignSessions.running(listOf(desktop, steam, input, monitor)))
    }

    @Test
    fun controlsAndTheCopyAreHiddenRemoteInputStays() {
        assertTrue(ForeignSessions.isHidden(copy))
        assertTrue(ForeignSessions.isHidden(resume))
        assertTrue(ForeignSessions.isHidden(terminate))
        assertFalse(ForeignSessions.isHidden(input))
        assertFalse(ForeignSessions.isHidden(monitor))
        assertFalse(ForeignSessions.isHidden(desktop))
    }

    @Test
    fun hostStateTakesTheForeignGameAndDropsItAgain() {
        val foreign = state(0).withForeignSession(remote(desktop, copy, resume, terminate))
        assertEquals(1, foreign.runningAppId)
        assertEquals(copy, foreign.foreignCopy)
        // The other client quit: the copy is gone, so is the running game.
        val after = foreign.withForeignSession(remote(desktop, steam))
        assertEquals(0, after.runningAppId)
        assertNull(after.foreignCopy)
        // This client's own game wins.
        assertEquals(2, state(2).withForeignSession(remote(desktop, copy)).runningAppId)
        assertNull(state(2).withForeignSession(remote(desktop, copy)).foreignCopy)
    }
}
