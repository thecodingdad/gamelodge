package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.data.settings.GamePadSettings
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.PadStreamSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPadsTest {
    private val xbox = "Xbox Series X Controller"

    @Test
    fun modelDefaultsWithoutOwnSettings() {
        val e = StreamPads.effective(xbox, LauncherSettings())
        assertEquals(5, e.deadzonePercent)
        assertEquals(100, e.rumblePercent)
        assertEquals(true, e.motion)
        assertEquals(false, e.swap)
        assertEquals(false, e.attachOnUse)
    }

    @Test
    fun ownSettingsAndTheGamesChoices() {
        val settings = LauncherSettings(
            controllerStream = mapOf(xbox to PadStreamSettings(deadzonePercent = 10, rumblePercent = 50, motion = false, attachOnUse = true)),
            controllerLayouts = mapOf(xbox to ButtonLayout.NINTENDO),
        )
        val own = StreamPads.effective(xbox, settings)
        assertEquals(10, own.deadzonePercent)
        assertEquals(50, own.rumblePercent)
        assertEquals(false, own.motion)
        assertEquals(true, own.swap)
        assertEquals(true, own.attachOnUse)
        // The game wants vibration off and motion on.
        val game = StreamPads.effective(xbox, settings, GamePadSettings(rumble = false, motion = true))
        assertEquals(0, game.rumblePercent)
        assertEquals(true, game.motion)
        // Vibration on for a game while the controller's is off: full strength.
        val off = settings.copy(controllerStream = mapOf(xbox to PadStreamSettings(rumblePercent = 0)))
        assertEquals(100, StreamPads.effective(xbox, off, GamePadSettings(rumble = true)).rumblePercent)
    }

    @Test
    fun jsonByControllerName() {
        val json = Json.parseToJsonElement(StreamPads.json(listOf(xbox, "Say \"hi\""), LauncherSettings())).jsonObject
        assertEquals(5, json.getValue(xbox).jsonObject.getValue("deadzone").jsonPrimitive.int)
        assertEquals(true, json.getValue("Say \"hi\"").jsonObject.getValue("battery").jsonPrimitive.boolean)
    }
}
