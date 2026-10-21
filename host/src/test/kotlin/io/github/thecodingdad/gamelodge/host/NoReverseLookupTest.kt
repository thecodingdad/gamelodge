package io.github.thecodingdad.gamelodge.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NoReverseLookupTest {
    @Test
    fun addressesKeepTheirNumberAndNamesStillResolve() {
        // 127.0.0.1 has a name everywhere ("localhost"), so a real reverse lookup would find it.
        assertEquals("127.0.0.1", InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)).hostName)
        assertEquals("192.168.1.77", InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 77)).hostName)
        assertTrue(InetAddress.getByName("localhost").isLoopbackAddress)
    }
}
