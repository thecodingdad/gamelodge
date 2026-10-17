package io.github.thecodingdad.gamelodge.desktop.host.sync

import io.github.thecodingdad.gamelodge.host.*

import io.github.thecodingdad.gamelodge.desktop.games.GameEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * What the runner has to do for one host entry: one base64url JSON argument, so paths and
 * arguments with quotes survive the host's command line.
 */
@Serializable
data class LaunchSpec(
    /** "play" or "install". */
    val mode: String,
    val id: String,
    val name: String? = null,
    val source: String? = null,
    val launch: String? = null,
    val args: String? = null,
    val workingDir: String? = null,
    val processDir: String? = null,
    val processNames: List<String> = emptyList(),
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(this).toByteArray())

    /** The entry the runner starts (play) or installs. */
    fun toEntry() = GameEntry(
        id = id, name = name ?: id, source = source ?: "", installed = mode == PLAY,
        launch = if (mode == PLAY) launch else null, install = if (mode == INSTALL) launch else null,
        launchArgs = args, workingDir = workingDir, processDir = processDir, installDir = processDir, processNames = processNames,
    )

    companion object {
        const val PLAY = "play"
        const val INSTALL = "install"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun decode(value: String): LaunchSpec = json.decodeFromString(String(Base64.getUrlDecoder().decode(value)))

        fun of(e: GameEntry) = if (e.installed) {
            LaunchSpec(PLAY, e.id, e.name, e.source, e.launch, e.launchArgs, e.workingDir, e.processDir ?: e.installDir, e.processNames)
        } else {
            LaunchSpec(INSTALL, e.id, e.name, e.source, e.install, processDir = e.installDir)
        }
    }
}

/** One game the sync puts on the host: the scanned entry with the launcher's name, kind, cover candidates and groups. */
data class SyncEntry(
    val entry: GameEntry,
    val name: String,
    val game: Boolean,
    val covers: List<String> = entry.covers,
    val collections: List<String> = emptyList(),
)

/** The host's app entry for a game; our data lives in "x-companion" (the companion's format, so its entries are taken over). */
object AppBuilder {
    const val MARKER = "x-companion"

    /** Keys the sync owns; everything else in an existing entry stays as the user set it. */
    val managedKeys = listOf("name", "cmd", "image-path", "auto-detach", "wait-all", "exit-timeout", MARKER)

    /** Same uuid for the same game across syncs (and as the companion made it), so clients keep their references. */
    fun uuid(id: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest("moonlight-companion:$id".toByteArray())
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        // .NET's Guid(bytes) reads the first three groups little-endian.
        val b = hash.copyOf(16)
        val swapped = byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)
        val bb = ByteBuffer.wrap(swapped)
        return UUID(bb.long, bb.long).toString().uppercase()
    }

    fun build(s: SyncEntry, runnerCommand: String, coverPath: String?): JsonObject = buildJsonObject {
        val e = s.entry
        put("name", s.name)
        put("cmd", "$runnerCommand ${LaunchSpec.of(e).encode()}")
        // The runner exits together with the game, so the host must wait for it.
        put("auto-detach", false)
        put("wait-all", true)
        put("exit-timeout", 5)
        put("uuid", uuid(e.id))
        putJsonObject(MARKER) {
            put("id", e.id)
            put("kind", if (s.game) "game" else "app")
            put("source", e.source)
            put("platform", e.platform)
            put("storeId", e.storeId)
            put("installed", e.installed)
            put("emulator", null as String?)
            putJsonArray("collections") { s.collections.forEach { add(JsonPrimitive(it)) } }
            put("v", 1)
        }
        if (coverPath != null) put("image-path", coverPath)
    }
}

data class SyncChange(val id: String, val name: String, val app: JsonObject)

data class SyncPlan(val adds: List<SyncChange>, val updates: List<SyncChange>, val removes: List<Pair<String, String>>) {
    val isEmpty get() = adds.isEmpty() && updates.isEmpty() && removes.isEmpty()
}

/**
 * Compares the wanted entries with the host's apps. Only apps carrying the marker are touched;
 * the user's own entries stay. Updates keep the user's other settings of an entry.
 */
object SyncPlanner {
    fun plan(wanted: List<SyncEntry>, hostApps: JsonArray, build: (SyncEntry) -> JsonObject, protect: (JsonObject) -> Boolean = { false }): SyncPlan {
        val existing = hostApps.mapNotNull { it as? JsonObject }
            .filter { markerId(it) != null }
            .groupBy { markerId(it)!! }
            .mapValues { it.value.first() }
        val adds = mutableListOf<SyncChange>()
        val updates = mutableListOf<SyncChange>()
        val keep = mutableSetOf<String>()
        for (s in wanted) {
            val current = existing[s.entry.id]
            val desired = build(s)
            keep += s.entry.id
            if (current == null) adds += SyncChange(s.entry.id, s.name, desired)
            else if (!sameManaged(current, desired)) updates += SyncChange(s.entry.id, s.name, mergeOnto(current, desired))
        }
        val removes = existing.filter { (id, app) -> id !in keep && !protect(app) }
            .mapNotNull { (id, app) -> app.str("uuid")?.takeIf { it.isNotEmpty() }?.let { it to (app.str("name") ?: id) } }
        return SyncPlan(adds, updates, removes)
    }

    private fun markerId(app: JsonObject) = (app[AppBuilder.MARKER] as? JsonObject)?.str("id")

    private fun sameManaged(current: JsonObject, desired: JsonObject) = AppBuilder.managedKeys.all { normalize(current[it]) == normalize(desired[it]) }

    // Hosts write numbers and booleans back as they like ("5" / 5, "false" / false).
    private fun normalize(e: JsonElement?): JsonElement? = when (e) {
        is JsonPrimitive -> if (e.isString) JsonPrimitive(e.content) else JsonPrimitive(e.toString())
        is JsonObject -> JsonObject(e.mapValues { normalize(it.value) ?: JsonPrimitive("null") })
        is JsonArray -> JsonArray(e.map { normalize(it) ?: JsonPrimitive("null") })
        else -> e
    }

    /** Existing entry with our keys replaced; uuid and the user's own settings stay. */
    fun mergeOnto(current: JsonObject, desired: JsonObject): JsonObject {
        val merged = current.toMutableMap()
        for (k in AppBuilder.managedKeys) {
            merged.remove(k)
            desired[k]?.let { merged[k] = it }
        }
        merged.remove("index")
        return JsonObject(merged)
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

internal fun JsonElement.objOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.contentOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
