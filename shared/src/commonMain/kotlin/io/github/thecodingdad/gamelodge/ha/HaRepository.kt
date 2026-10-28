package io.github.thecodingdad.gamelodge.ha

import io.github.thecodingdad.gamelodge.data.db.HaTileEntity
import io.github.thecodingdad.gamelodge.data.db.HaTileType
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive

/** Home Assistant features of the launcher: tiles, actions and launcher events. */
class HaRepository(
    val client: HaClient,
    val auth: HaAuth,
    private val db: LauncherDatabase,
) {
    val tiles: Flow<List<HaTileEntity>> = db.haTileDao().observeAll()

    suspend fun addTile(entity: HaEntity) {
        val order = (db.haTileDao().getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        db.haTileDao().upsert(listOf(HaTileEntity(entity.id, tileTypeFor(entity.domain), null, order, showOnHome = false)))
    }

    suspend fun removeTile(entityId: String) = db.haTileDao().delete(entityId)

    suspend fun setShowOnHome(tile: HaTileEntity, show: Boolean) = db.haTileDao().upsert(listOf(tile.copy(showOnHome = show)))

    /** The tiles in the order of [entityIds] (dragged or moved in the settings). */
    suspend fun reorder(entityIds: List<String>) {
        val byId = db.haTileDao().getAll().associateBy { it.entityId }
        db.haTileDao().upsert(entityIds.mapNotNull { byId[it] }.mapIndexed { index, t -> t.copy(sortOrder = index) })
    }

    /** Moves a tile one position up (-1) or down (+1). */
    suspend fun move(tile: HaTileEntity, delta: Int) {
        val list = db.haTileDao().getAll().toMutableList()
        val i = list.indexOfFirst { it.entityId == tile.entityId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        db.haTileDao().upsert(list.mapIndexed { index, t -> t.copy(sortOrder = index) })
    }

    /** Primary action of a tile (A button). */
    suspend fun primaryAction(entity: HaEntity) = guarded {
        when (entity.domain) {
            "light", "switch", "input_boolean", "fan", "media_player", "automation" ->
                client.callService("homeassistant", "toggle", entity.id)
            "cover" -> client.callService("cover", "toggle", entity.id)
            "lock" -> client.callService("lock", if (entity.state == "locked") "unlock" else "lock", entity.id)
            "climate" -> client.callService("climate", if (entity.state == "off") "turn_on" else "turn_off", entity.id)
            else -> runAction(entity.id)
        }
    }

    /** Runs a stored action ([HaAction]: entity id, for switches with on or off, for lights with brightness). */
    suspend fun runAction(action: String) = guarded {
        val entityId = HaAction.entity(action)
        if (HaAction.on(action) == false) {
            client.callService("homeassistant", "turn_off", entityId)
            return@guarded
        }
        HaAction.brightness(action)?.let {
            client.callService("light", "turn_on", entityId, mapOf("brightness_pct" to JsonPrimitive(it)))
            return@guarded
        }
        when (entityId.substringBefore('.')) {
            "script" -> client.callService("script", "turn_on", entityId)
            "scene" -> client.callService("scene", "turn_on", entityId)
            "button" -> client.callService("button", "press", entityId)
            "input_button" -> client.callService("input_button", "press", entityId)
            "automation" -> client.callService("automation", "trigger", entityId)
            else -> client.callService("homeassistant", "turn_on", entityId)
        }
    }

    suspend fun setBrightness(entity: HaEntity, percent: Int) = guarded {
        if (percent <= 0) client.callService("light", "turn_off", entity.id)
        else client.callService("light", "turn_on", entity.id, mapOf("brightness_pct" to JsonPrimitive(percent.coerceIn(1, 100))))
    }

    suspend fun setCoverPosition(entity: HaEntity, position: Int) = guarded {
        client.callService("cover", "set_cover_position", entity.id, mapOf("position" to JsonPrimitive(position.coerceIn(0, 100))))
    }

    suspend fun setTemperature(entity: HaEntity, temperature: Double) = guarded {
        client.callService("climate", "set_temperature", entity.id, mapOf("temperature" to JsonPrimitive(temperature)))
    }

    /** Launcher events for HA automations (e.g. dim the lights when a stream starts). */
    suspend fun fireLauncherEvent(name: String, data: Map<String, String?>) {
        if (!auth.isLoggedIn) return
        guarded { client.fireEvent("moonlight_launcher_$name", data.filterValues { it != null }.mapValues { JsonPrimitive(it.value) }) }
    }

    private suspend fun guarded(block: suspend () -> Unit): Boolean =
        runCatching { block(); true }.onFailure { Log.w("Home Assistant action failed", it) }.getOrDefault(false)

    companion object {
        val supportedDomains = setOf(
            "light", "switch", "input_boolean", "fan", "script", "scene", "button", "input_button",
            "sensor", "binary_sensor", "cover", "climate", "lock", "media_player", "automation",
        )

        fun tileTypeFor(domain: String): HaTileType = when (domain) {
            "light" -> HaTileType.LIGHT
            "switch", "input_boolean", "fan", "lock", "media_player", "automation" -> HaTileType.SWITCH
            "script", "scene", "button", "input_button" -> HaTileType.BUTTON
            "cover" -> HaTileType.COVER
            "climate" -> HaTileType.CLIMATE
            else -> HaTileType.SENSOR
        }
    }
}
