package io.github.thecodingdad.moonlightlauncher.desktop.host.sync

import io.github.thecodingdad.moonlightlauncher.host.*

import io.github.thecodingdad.moonlightlauncher.desktop.games.GameEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private val game = GameEntry(
        "steam:1086940", "Baldur's Gate 3", "steam", storeId = "1086940", installed = true,
        launch = "steam://rungameid/1086940", installDir = "C:\\Games\\BG3",
    )

    private fun build(s: SyncEntry) = AppBuilder.build(s, "\"runner\" --run", null)

    @Test
    fun uuidsMatchTheCompanions() {
        // Computed with the companion's C# code.
        assertEquals("302107F5-2E63-B558-AE33-616E9C767F60", AppBuilder.uuid("steam:1086940"))
        assertEquals("054E0D0E-FCFD-F552-94A7-992F945B5262", AppBuilder.uuid("epic:abc:def"))
    }

    @Test
    fun launchSpecSurvivesTheCommandLine() {
        val spec = LaunchSpec.of(game.copy(launchArgs = "-x \"quoted arg\""))
        val back = LaunchSpec.decode(spec.encode())
        assertEquals(spec, back)
        assertTrue(spec.encode().all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals("steam://rungameid/1086940", back.toEntry().launch)
    }

    @Test
    fun plansOnlyOwnEntriesAndKeepsUserSettings() {
        val manual = Json.parseToJsonElement("""{"name":"Desktop","uuid":"U1"}""") as JsonObject
        val gone = Json.parseToJsonElement("""{"name":"Old","uuid":"U2","x-companion":{"id":"epic:old","source":"epic"}}""") as JsonObject
        val failedSource = Json.parseToJsonElement("""{"name":"Xbox game","uuid":"U3","x-companion":{"id":"xbox:1","source":"xbox"}}""") as JsonObject
        val existing = JsonObject(
            build(SyncEntry(game, "Old name", true)) + ("prep-cmd" to Json.parseToJsonElement("[1]")) + ("uuid" to Json.parseToJsonElement("\"U4\"")),
        )
        val plan = SyncPlanner.plan(listOf(SyncEntry(game, "Baldur's Gate 3", true)), JsonArray(listOf(manual, gone, failedSource, existing)), ::build) {
            (it["x-companion"] as JsonObject)["source"]!!.jsonPrimitive.content == "xbox"
        }
        assertEquals(0, plan.adds.size)
        assertEquals(listOf("U2" to "Old"), plan.removes)
        val updated = plan.updates.single().app
        assertEquals("Baldur's Gate 3", updated["name"]!!.jsonPrimitive.content)
        // The user's own settings and the uuid stay.
        assertEquals("U4", updated["uuid"]!!.jsonPrimitive.content)
        assertTrue("prep-cmd" in updated)
        // Nothing changed: nothing to do.
        assertTrue(SyncPlanner.plan(listOf(SyncEntry(game, "Baldur's Gate 3", true)), JsonArray(listOf(updated)), ::build).isEmpty)
    }

    @Test
    fun coversBecomePng() {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(1200, 1800)
        val jpeg = surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG)!!.bytes
        val png = HostSync.toPng(jpeg)
        val image = org.jetbrains.skia.Image.makeFromEncoded(png)
        assertEquals(600, image.width)
        assertEquals(900, image.height)
        assertEquals(0x89.toByte(), png[0])
    }
}
