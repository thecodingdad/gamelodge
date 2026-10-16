package io.github.thecodingdad.moonlightlauncher.desktop.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightIniTest {
    @Test
    fun keepsOwnSettingsAndWritesIdentityAndHosts() {
        val id = StreamIdentity.create()
        val existing = "[General]\nwidth=1920\ncertificate=old\n\n[gcmapping]\n1\\mapping=x\n\n[hosts]\n1\\hostname=stale\nsize=1\n"
        val host = StoredHost("UUID-1", "Gaming \"PC\"", "192.168.1.5", serverCert = id.certificatePem)
        val out = MoonlightIni.update(existing, id, listOf(host))
        assertTrue(out.contains("width=1920"))
        assertTrue(out.contains("[gcmapping]\n1\\mapping=x"))
        assertTrue(out.contains("certificate=\"@ByteArray(-----BEGIN CERTIFICATE-----\\n"))
        assertTrue(out.contains("uniqueid=${id.uniqueId}"))
        assertTrue(out.contains("1\\hostname=\"Gaming \\\"PC\\\"\""))
        assertTrue(out.contains("1\\uuid=UUID-1"))
        assertTrue(!out.contains("stale"))
        assertTrue(out.contains("size=1"))
    }

    @Test
    fun addresses() {
        assertEquals("192.168.1.5" to 47989, DesktopHosts.parseAddress("192.168.1.5"))
        assertEquals("pc.local" to 48000, DesktopHosts.parseAddress("pc.local:48000"))
        assertEquals("fe80::1" to 47989, DesktopHosts.parseAddress("fe80::1"))
        assertEquals("fe80::1" to 50000, DesktopHosts.parseAddress("[fe80::1]:50000"))
        assertEquals(null, DesktopHosts.parseAddress("a b"))
        assertEquals(null, DesktopHosts.parseAddress("pc:99999"))
    }
}
