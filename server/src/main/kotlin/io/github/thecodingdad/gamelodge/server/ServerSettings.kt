package io.github.thecodingdad.gamelodge.server

import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.host.MetadataKeys
import io.github.thecodingdad.gamelodge.host.ServerSettingsForm
import io.github.thecodingdad.gamelodge.host.restrictToOwner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Name and metadata provider keys of the server, changed in the web admin. The name and the IGDB
 * client id are kept in settings.json, the key and the secret encrypted in the secret store.
 */
class ServerSettings(private val file: File, private val secrets: SecretStore) : MetadataKeys, ServerSettingsForm {
    @Serializable
    private data class Data(val name: String = DEFAULT_NAME, val igdbClientId: String? = null)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    @Volatile private var data: Data = runCatching { json.decodeFromString<Data>(file.readText()) }.getOrDefault(Data())

    override val name: String get() = data.name
    override val steamGridDbKey: String? get() = secrets.get(SGDB)
    override val igdbClientId: String? get() = data.igdbClientId
    override val igdbClientSecret: String? get() = secrets.get(IGDB_SECRET)
    override val steamGridDbKeySet: Boolean get() = steamGridDbKey != null
    override val igdbClientSecretSet: Boolean get() = igdbClientSecret != null

    @Synchronized
    override fun save(name: String?, steamGridDbKey: String?, igdbClientId: String?, igdbClientSecret: String?) {
        steamGridDbKey?.let { secrets.put(SGDB, clean(it, 256)) }
        igdbClientSecret?.let { secrets.put(IGDB_SECRET, clean(it, 256)) }
        data = data.copy(
            name = name?.let { clean(it, 64) } ?: data.name,
            igdbClientId = if (igdbClientId != null) clean(igdbClientId, 256) else data.igdbClientId,
        )
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Data.serializer(), data))
        restrictToOwner(file)
    }

    private fun clean(value: String, max: Int): String? = value.trim().take(max).ifEmpty { null }

    companion object {
        const val DEFAULT_NAME = "GameLodge Metadata Server"
        private const val SGDB = "sgdb_api_key"
        private const val IGDB_SECRET = "igdb_client_secret"
    }
}
