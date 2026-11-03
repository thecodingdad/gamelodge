package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.data.settings.GamePadSettings
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.PadStreamSettings
import io.github.thecodingdad.gamelodge.ui.screens.settings.MotionMode
import io.github.thecodingdad.gamelodge.ui.screens.settings.controllerDefaults
import io.github.thecodingdad.gamelodge.ui.screens.settings.controllerIdsFromName
import io.github.thecodingdad.gamelodge.ui.screens.settings.layoutFor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Each controller's stream settings for Moonlight-Qt (MOONLIGHT_LAUNCHER_PADS / "pads …"), by
 * its SDL name: its own settings, else its model's defaults; the streamed game's choices win.
 */
object StreamPads {
    /** The settings of one controller as Moonlight-Qt takes them. */
    data class Effective(
        val deadzonePercent: Int,
        val rumblePercent: Int,
        val motion: Boolean,
        val battery: Boolean,
        val swap: Boolean,
        val touchpadMouse: Boolean,
        val attachOnUse: Boolean,
    )

    fun effective(name: String, settings: LauncherSettings, game: GamePadSettings? = null): Effective {
        val (vendor, product) = controllerIdsFromName(name)
        val d = controllerDefaults(vendor, product)
        val own = settings.controllerStream[name] ?: PadStreamSettings()
        val layout = game?.layout ?: settings.layoutFor(name, d.layout)
        val rumble = own.rumblePercent ?: d.vibrationStrength
        val motion = own.motion ?: (d.motion != MotionMode.OFF)
        return Effective(
            deadzonePercent = own.deadzonePercent ?: d.deadzonePercent,
            // The game's own choice wins: off, or on (at least the controller's strength, else full).
            rumblePercent = when (game?.rumble) {
                false -> 0
                true -> rumble.takeIf { it > 0 } ?: 100
                null -> rumble
            },
            motion = game?.motion ?: motion,
            battery = own.batteryReport ?: d.batteryReport,
            swap = layout == ButtonLayout.NINTENDO,
            touchpadMouse = game?.touchpadMouse ?: own.touchpadMouse ?: d.touchpadAsMouse,
            attachOnUse = own.attachOnUse ?: false,
        )
    }

    /** The JSON for [names] (connected controllers and the ones with settings). */
    fun json(names: Collection<String>, settings: LauncherSettings, game: GamePadSettings? = null): String {
        val all: JsonObject = buildJsonObject {
            for (name in names.distinct()) {
                val e = effective(name, settings, game)
                put(name, buildJsonObject {
                    put("deadzone", e.deadzonePercent)
                    put("rumble", e.rumblePercent)
                    put("motion", e.motion)
                    put("battery", e.battery)
                    put("swap", e.swap)
                    put("touchpadMouse", e.touchpadMouse)
                    put("attachOnUse", e.attachOnUse)
                })
            }
        }
        return all.toString()
    }

    /** The controllers to describe: connected ones and every one with own settings. */
    fun names(connected: Collection<String>, settings: LauncherSettings): Set<String> =
        connected.toSet() + settings.controllerStream.keys + settings.controllerLayouts.keys

    /** The streamed game's own controller settings (set when its stream starts). */
    @Volatile var game: GamePadSettings? = null
}
