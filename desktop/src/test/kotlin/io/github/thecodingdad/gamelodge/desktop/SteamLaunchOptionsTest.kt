package io.github.thecodingdad.gamelodge.desktop

import io.github.thecodingdad.gamelodge.desktop.games.SteamLaunchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Steam's launch options from appinfo.vdf (formats 28 and 29, built here like Steam writes them). */
class SteamLaunchOptionsTest {
    /** A key/value tree: maps are nodes, strings are values. */
    private fun kv(tree: Map<String, Any>, keys: MutableList<String>?): ByteArray {
        val out = ByteArrayOutputStream()
        fun key(k: String) {
            if (keys == null) {
                out.write(k.toByteArray()); out.write(0)
            } else {
                val i = keys.indexOf(k).takeIf { it >= 0 } ?: keys.size.also { keys += k }
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array())
            }
        }
        fun node(m: Map<String, Any>) {
            m.forEach { (k, v) ->
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is Map<*, *> -> { out.write(0x00); key(k); node(v as Map<String, Any>) }
                    else -> { out.write(0x01); key(k); out.write(v.toString().toByteArray()); out.write(0) }
                }
            }
            out.write(0x08)
        }
        node(tree)
        return out.toByteArray()
    }

    private fun appinfo(version: Int, apps: Map<Int, Map<String, Any>>): ByteArray {
        val keys = if (version >= 0x29) mutableListOf<String>() else null
        val body = ByteArrayOutputStream()
        apps.forEach { (id, tree) ->
            val data = kv(tree, keys)
            val header = 4 + 4 + 8 + 20 + 4 + (if (version >= 0x28) 20 else 0)
            val b = ByteBuffer.allocate(8 + header).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(id).putInt(header + data.size)
            b.position(8 + header)
            body.write(b.array())
            body.write(data)
        }
        body.write(ByteArray(4)) // end
        val headerSize = 8 + if (keys != null) 8 else 0
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN).putInt(0x07564400 or version).putInt(1)
        if (keys != null) h.putLong((headerSize + body.size()).toLong())
        out.write(h.array())
        out.write(body.toByteArray())
        if (keys != null) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(keys.size).array())
            keys.forEach { out.write(it.toByteArray()); out.write(0) }
        }
        return out.toByteArray()
    }

    private val game = mapOf(
        "appinfo" to mapOf(
            "appid" to "100",
            "config" to mapOf(
                "launch" to mapOf(
                    "0" to mapOf("executable" to "game.exe", "type" to "default", "config" to mapOf("oslist" to "windows")),
                    "1" to mapOf(
                        "executable" to "bin/game_dx11.exe", "arguments" to "-dx11", "description" to "Play DX11",
                        "description_loc" to mapOf("german" to "DX11 spielen"), "type" to "option1",
                    ),
                    "2" to mapOf("executable" to "game.sh", "description" to "Linux", "config" to mapOf("oslist" to "linux")),
                    "3" to mapOf("executable" to "Launcher.exe", "description" to "Settings", "type" to "option2"),
                    "4" to mapOf("executable" to "game.exe", "description" to "Review build", "config" to mapOf("oslist" to "windows", "BetaKey" to "review")),
                ),
            ),
        ),
    )

    @Test
    fun readsWindowsOptionsBesidesTheDefault() {
        for (version in listOf(0x28, 0x29)) {
            val other = mapOf("appinfo" to mapOf("appid" to "200"))
            val parsed = SteamLaunchOptions.parse(appinfo(version, mapOf(200 to other, 100 to game)), setOf(100))
            val options = parsed.getValue(100)
            assertEquals("format $version", listOf("Play DX11", "Settings"), options.map { it.description })
            assertEquals("bin\\game_dx11.exe", options[0].executable)
            assertEquals("-dx11", options[0].arguments)
        }
    }

    @Test
    fun usesTheTranslationAndSkipsOtherApps() {
        val parsed = SteamLaunchOptions.parse(appinfo(0x29, mapOf(100 to game)), setOf(100), "german")
        assertEquals("DX11 spielen", parsed.getValue(100).first().description)
        assertTrue(SteamLaunchOptions.parse(appinfo(0x29, mapOf(100 to game)), setOf(999)).isEmpty())
    }

    @Test
    fun actionsPointIntoTheInstallFolder() {
        val option = SteamLaunchOptions.parse(appinfo(0x29, mapOf(100 to game)), setOf(100)).getValue(100)
        val action = SteamLaunchOptions.actions(100, "C:\\Games\\Game", option).first()
        assertEquals("steam:100:1", action.id)
        assertTrue(action.launch.endsWith("game_dx11.exe"))
        assertEquals(listOf("game_dx11.exe"), action.processNames)
    }
}
