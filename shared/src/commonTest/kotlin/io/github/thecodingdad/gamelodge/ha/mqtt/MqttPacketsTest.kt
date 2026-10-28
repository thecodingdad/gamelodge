package io.github.thecodingdad.gamelodge.ha.mqtt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class MqttPacketsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun remainingLength() {
        assertArrayEquals(bytes(0), MqttPackets.remainingLength(0))
        assertArrayEquals(bytes(127), MqttPackets.remainingLength(127))
        assertArrayEquals(bytes(0x80, 0x01), MqttPackets.remainingLength(128))
        assertArrayEquals(bytes(0xFF, 0xFF, 0x7F), MqttPackets.remainingLength(2_097_151))
    }

    @Test
    fun connectWithLoginAndWill() {
        val p = MqttPackets.connect(
            MqttOptions("h", 1883, false, "u", "pw", "id", keepAliveSeconds = 60, will = MqttWill("t", "offline")),
        )
        val read = MqttPackets.read(ByteArrayInputStream(p))
        assertEquals(MqttPackets.CONNECT, read.type)
        val expected = bytes(0, 4, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 4, 0x80 or 0x40 or 0x20 or 0x04 or 0x02, 0, 60) +
            bytes(0, 2, 'i'.code, 'd'.code) + bytes(0, 1, 't'.code) + bytes(0, 7) + "offline".toByteArray() +
            bytes(0, 1, 'u'.code) + bytes(0, 2, 'p'.code, 'w'.code)
        assertArrayEquals(expected, read.body)
    }

    @Test
    fun passwordNeedsUser() {
        val read = MqttPackets.read(ByteArrayInputStream(MqttPackets.connect(MqttOptions("h", 1883, false, null, "pw", "id"))))
        assertEquals(0x02, read.body[7].toInt())
        assertEquals(10 + 4, read.body.size)
    }

    @Test
    fun publishRoundTrip() {
        val p = MqttPackets.publish("gamelodge/x/state", "idle".toByteArray(), retain = true)
        val read = MqttPackets.read(ByteArrayInputStream(p))
        assertEquals(MqttPackets.PUBLISH, read.type)
        val m = MqttPackets.parsePublish(read)
        assertEquals("gamelodge/x/state", m.topic)
        assertEquals("idle", m.payload.decodeToString())
        assertEquals(true, m.retain)
    }

    @Test
    fun publishRejectsWildcards() {
        assertThrows(IllegalArgumentException::class.java) { MqttPackets.publish("a/#", ByteArray(0), false) }
        assertThrows(IllegalArgumentException::class.java) { MqttPackets.publish("a/+/b", ByteArray(0), false) }
    }

    @Test
    fun incomingPublishWithPacketId() {
        // QoS 1 publish "a" with packet id 7 and payload "x".
        val p = MqttPackets.read(ByteArrayInputStream(bytes(0x32, 6, 0, 1, 'a'.code, 0, 7, 'x'.code)))
        val m = MqttPackets.parsePublish(p)
        assertEquals("a", m.topic)
        assertEquals("x", m.payload.decodeToString())
    }

    @Test
    fun rejectsOversizedAndBrokenPackets() {
        // Announces 256 MB: refused before anything is allocated.
        assertThrows(MqttException::class.java) { MqttPackets.read(ByteArrayInputStream(bytes(0x30, 0xFF, 0xFF, 0xFF, 0x7F))) }
        // Five length digits.
        assertThrows(MqttException::class.java) { MqttPackets.read(ByteArrayInputStream(bytes(0x30, 0x80, 0x80, 0x80, 0x80, 0x01))) }
        // Truncated body.
        assertThrows(java.io.EOFException::class.java) { MqttPackets.read(ByteArrayInputStream(bytes(0x30, 5, 0, 1))) }
        // Topic longer than the packet.
        assertThrows(MqttException::class.java) { MqttPackets.parsePublish(MqttPackets.read(ByteArrayInputStream(bytes(0x30, 3, 0, 9, 'a'.code)))) }
    }
}
