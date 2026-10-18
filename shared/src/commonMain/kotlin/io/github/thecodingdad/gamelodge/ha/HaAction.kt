package io.github.thecodingdad.gamelodge.ha

/**
 * A Home Assistant action as stored for host power: an entity id and, for entities that can be
 * switched both ways, whether to switch it on or off ("switch.gaming_pc#off"). Without the
 * suffix the entity runs its usual way (script, button, scene; switches on).
 */
object HaAction {
    /** Domains whose action is switching on or off. */
    val switchable = setOf("switch", "input_boolean", "light", "fan")

    fun encode(entityId: String, on: Boolean?): String = if (on == null) entityId else "$entityId#${if (on) "on" else "off"}"

    fun entity(value: String): String = value.substringBefore('#')

    /** On or off for switchable entities, null when the action has no direction. */
    fun on(value: String): Boolean? = when (value.substringAfter('#', "")) {
        "on" -> true
        "off" -> false
        else -> null
    }
}
