package io.github.thecodingdad.gamelodge.ha

/**
 * A Home Assistant action as stored for host power and game actions: an entity id and, for
 * entities that can be switched both ways, whether to switch it on or off ("switch.gaming_pc#off").
 * Lights switched on may carry a brightness in percent ("light.desk#on@40"). Without the suffix
 * the entity runs its usual way (script, button, scene; switches on).
 */
object HaAction {
    /** Domains whose action is switching on or off. */
    val switchable = setOf("switch", "input_boolean", "light", "fan")

    /** Brightness choices offered for lights switched on (null = keep the light's own). */
    val brightnessSteps = listOf(null, 10, 25, 50, 75, 100)

    fun encode(entityId: String, on: Boolean?, brightness: Int? = null): String = when {
        on == null -> entityId
        on && brightness != null -> "$entityId#on@${brightness.coerceIn(1, 100)}"
        else -> "$entityId#${if (on) "on" else "off"}"
    }

    fun entity(value: String): String = value.substringBefore('#')

    /** On or off for switchable entities, null when the action has no direction. */
    fun on(value: String): Boolean? = when (value.substringAfter('#', "").substringBefore('@')) {
        "on" -> true
        "off" -> false
        else -> null
    }

    /** Brightness in percent for lights switched on, null when the light keeps its own. */
    fun brightness(value: String): Int? =
        if (on(value) == true) value.substringAfter('@', "").toIntOrNull()?.coerceIn(1, 100) else null
}
