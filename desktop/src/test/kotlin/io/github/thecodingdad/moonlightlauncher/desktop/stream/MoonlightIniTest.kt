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

class ServerInfoTest {
    @Test
    fun vibepolloServerInfo() {
        // As Vibepollo answers (shortened).
        val xml = """<?xml version="1.0" encoding="utf-8"?><root status_code="200"><hostname>Win11VM</hostname><appversion>7.1.431.-1</appversion><uniqueid>13C2351E</uniqueid><HttpsPort>47984</HttpsPort><VirtualDisplayCapable>true</VirtualDisplayCapable><mac>00:00:00:00:00:00</mac><Permission>0</Permission><PairStatus>0</PairStatus><currentgame>0</currentgame><state>SUNSHINE_SERVER_FREE</state></root>"""
        val info = NvHttp.parseServerInfo(xml)
        assertEquals("Win11VM", info.name)
        assertEquals(true, info.virtualDisplay)
        assertEquals(null, info.mac)
        assertEquals(0, info.permission)
    }

    @Test
    fun unknownCertificateIsStatus401() {
        // HTTPS with a certificate the host doesn't know: HTTP 200, status 401 in the XML.
        val e = runCatching { NvHttp.parseServerInfo("""<root status_code="401" status_message="The client is not authorized. Certificate verification failed."/>""") }.exceptionOrNull()
        assertEquals(401, (e as NvHttpException).status)
    }
}

class MoonlightIniAddressTest {
    @Test
    fun lanIpBeforeHostName() {
        val id = StreamIdentity.create()
        val host = StoredHost("U", "PC", "Win11VM", serverCert = id.certificatePem, localIp = "192.168.168.68")
        val out = MoonlightIni.update("", id, listOf(host))
        assertTrue(out.contains("1\\localaddress=\"192.168.168.68\""))
        assertTrue(out.contains("1\\manualaddress=\"Win11VM\""))
    }
}
