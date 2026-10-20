package io.github.thecodingdad.gamelodge.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNetworksTest {
    @Test
    fun normalizesAddressesSubnetsAndRanges() {
        assertEquals("192.168.1.20", HomeNetworks.normalize(" 192.168.1.20 "))
        assertEquals("192.168.1.0/24", HomeNetworks.normalize("192.168.1.77/24"))
        assertEquals("10.0.0.5-10.0.0.9", HomeNetworks.normalize("10.0.0.5 - 10.0.0.9"))
        assertNull(HomeNetworks.normalize("192.168.1"))
        assertNull(HomeNetworks.normalize("192.168.1.300"))
        assertNull(HomeNetworks.normalize("192.168.1.0/33"))
        assertNull(HomeNetworks.normalize("10.0.0.9-10.0.0.5"))
        assertNull(HomeNetworks.normalize("home"))
    }

    @Test
    fun matchesTheDeviceAddresses() {
        val home = listOf("192.168.168.0/24", "10.8.0.7", "172.16.0.10-172.16.0.20")
        assertTrue(HomeNetworks.contains(home, listOf("192.168.168.98")))
        assertTrue(HomeNetworks.contains(home, listOf("100.64.1.2", "10.8.0.7")))
        assertTrue(HomeNetworks.contains(home, listOf("172.16.0.20")))
        assertFalse(HomeNetworks.contains(home, listOf("192.168.169.1", "10.8.0.8", "172.16.0.21")))
        assertFalse(HomeNetworks.contains(emptyList(), listOf("192.168.168.98")))
        assertTrue(HomeNetworks.contains(listOf("0.0.0.0/0"), listOf("8.8.8.8")))
    }

    @Test
    fun subnetOfAnAddress() {
        assertEquals("192.168.168.0/24", HomeNetworks.subnetOf("192.168.168.98", 24))
        assertEquals("10.0.0.0/8", HomeNetworks.subnetOf("10.1.2.3", 8))
    }
}
