package io.github.thecodingdad.moonlightlauncher.desktop.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** What a paired device may do. */
object Permissions {
    /** SteamGridDB / IGDB through this PC. */
    const val METADATA = "metadata"
    /** Read the PC's games. */
    const val HOST_GAMES = "hostgames"
    /** Send metadata corrections of the PC's games. */
    const val HOST_GAMES_WRITE = "hostgames.write"

    val all = setOf(METADATA, HOST_GAMES, HOST_GAMES_WRITE)
}

@Serializable
data class Device(
    val id: String,
    val name: String,
    /** SHA-256 of the device's client certificate (upper-case hex). */
    val fingerprint: String,
    /** Unix seconds. */
    val paired: Long,
    val lastSeen: Long? = null,
    val lastAddress: String? = null,
    val permissions: Set<String> = emptySet(),
) {
    fun may(permission: String) = permission in permissions
}

/**
 * Paired devices, known by the fingerprint of their client certificate. Removing a device revokes
 * it: its next request is rejected.
 */
class DeviceRegistry(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    fun find(fingerprint: String): Device? = _devices.value.firstOrNull { it.fingerprint == fingerprint }

    @Synchronized
    fun add(name: String, fingerprint: String, permissions: Set<String>, address: String?): Device {
        val now = System.currentTimeMillis() / 1000
        val device = Device(UUID.randomUUID().toString().replace("-", ""), clean(name), fingerprint, now, now, address, permissions intersect Permissions.all)
        // Paired again with the same certificate: the new entry replaces the old one.
        save(_devices.value.filter { it.fingerprint != fingerprint } + device)
        return device
    }

    @Synchronized
    fun rename(id: String, name: String) = update(id) { it.copy(name = clean(name)) }

    @Synchronized
    fun setPermissions(id: String, permissions: Set<String>) = update(id) { it.copy(permissions = permissions intersect Permissions.all) }

    @Synchronized
    fun remove(id: String) = save(_devices.value.filter { it.id != id })

    /** Last contact, written at most once a minute per device. */
    @Synchronized
    fun touch(device: Device, address: String?) {
        val now = System.currentTimeMillis() / 1000
        if (device.lastSeen != null && now - device.lastSeen < 60 && device.lastAddress == address) return
        update(device.id) { it.copy(lastSeen = now, lastAddress = address) }
    }

    /** Takes over devices paired with the companion (moving from the companion to the launcher). */
    @Synchronized
    fun import(devices: List<Device>) = save((_devices.value + devices).distinctBy { it.fingerprint })

    private fun update(id: String, change: (Device) -> Device) = save(_devices.value.map { if (it.id == id) change(it) else it })

    private fun load(): List<Device> = runCatching { json.decodeFromString<List<Device>>(file.readText()) }.getOrDefault(emptyList())

    private fun save(devices: List<Device>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(devices))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _devices.value = devices.sortedBy { it.name.lowercase() }
    }

    private fun clean(name: String): String {
        val cleaned = name.filterNot { it.isISOControl() }.trim().take(64)
        return cleaned.ifEmpty { "Device" }
    }
}
