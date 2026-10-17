package io.github.thecodingdad.gamelodge.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaClientTest {
    private fun client(): HaClient {
        val http = OkHttpClient()
        return HaClient(http, HaAuth({ }, http, io.github.thecodingdad.gamelodge.testing.memorySecrets()), { null }, CoroutineScope(Dispatchers.Unconfined))
    }

    private fun event(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `added, changed and removed entities are applied`() {
        val c = client()
        c.applyEntityEvent(
            event(
                """{"a":{
                    "light.desk":{"s":"on","a":{"friendly_name":"Desk","brightness":128}},
                    "switch.vm":{"s":"off","a":{"friendly_name":"Gaming VM"}}}}""",
            ),
        )
        assertEquals(50, c.entities.value["light.desk"]?.brightnessPercent)
        assertFalse(c.entities.value["switch.vm"]!!.isOn)

        c.applyEntityEvent(event("""{"c":{"switch.vm":{"+":{"s":"on"}},"light.desk":{"+":{"a":{"brightness":255}}}}}"""))
        assertTrue(c.entities.value["switch.vm"]!!.isOn)
        assertEquals(100, c.entities.value["light.desk"]?.brightnessPercent)
        assertEquals("Desk", c.entities.value["light.desk"]?.name)

        c.applyEntityEvent(event("""{"c":{"light.desk":{"+":{"s":"off"},"-":{"a":["brightness"]}}},"r":["switch.vm"]}"""))
        assertNull(c.entities.value["light.desk"]?.brightnessPercent)
        assertNull(c.entities.value["switch.vm"])
    }

    @Test
    fun `base url is normalised`() {
        assertEquals("http://homeassistant.local:8123", HaAuth.normalizeBaseUrl(" homeassistant.local:8123/ "))
        assertEquals("https://ha.example.com", HaAuth.normalizeBaseUrl("https://ha.example.com/"))
    }
}
