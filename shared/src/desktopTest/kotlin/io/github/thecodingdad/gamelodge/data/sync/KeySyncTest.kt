package io.github.thecodingdad.gamelodge.data.sync

import io.github.thecodingdad.gamelodge.data.companion.SharedKey
import io.github.thecodingdad.gamelodge.data.companion.SharedKeys
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.platform.SecretBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySyncTest {
    private var now = 1_000_000L

    private fun store() = SecretStore(object : SecretBackend {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value.isNullOrEmpty()) map.remove(key) else map[key] = value }
        override fun keys() = map.keys.toSet()
    })

    @Test
    fun `the newest change wins, keys from before sharing lose against every change`() {
        val hostSecrets = store()
        hostSecrets.put(SecretStore.Keys.SGDB_API_KEY, "old")
        val host = KeySync(hostSecrets) { now }
        val device = KeySync(store()) { now }
        assertEquals(SharedKey(KeySync.INITIAL, listOf("old")), host.local().sgdb)
        assertNull(device.local().sgdb)

        // A device without keys takes the host's.
        assertTrue(device.merge(host.local()))
        assertEquals(listOf("old"), device.local().sgdb!!.values)

        now += 1000
        device.setSgdb("new")
        device.setIgdb("id", "secret")
        assertTrue(host.merge(device.local()))
        assertEquals(listOf("new"), host.local().sgdb!!.values)
        assertEquals(listOf("id", "secret"), host.local().igdb!!.values)
        // The same again changes nothing.
        assertFalse(host.merge(device.local()))

        // Removed on the host later: removed on the device too.
        now += 1000
        host.setIgdb(null, null)
        assertTrue(device.merge(host.local()))
        assertEquals(emptyList<String>(), device.local().igdb!!.values)
    }

    @Test
    fun `only sensible keys are valid`() {
        assertTrue(SharedKeys(sgdb = SharedKey(now, listOf("abc")), igdb = SharedKey(now, emptyList())).isValid(now))
        assertFalse(SharedKeys(sgdb = SharedKey(now, listOf("a", "b"))).isValid(now))
        assertFalse(SharedKeys(igdb = SharedKey(now, listOf("id"))).isValid(now))
        assertFalse(SharedKeys(sgdb = SharedKey(now, listOf("with space"))).isValid(now))
        assertFalse(SharedKeys(sgdb = SharedKey(now + 3_600_000, listOf("abc"))).isValid(now))
        assertFalse(SharedKeys(sgdb = SharedKey(0, listOf("abc"))).isValid(now))
    }
}
