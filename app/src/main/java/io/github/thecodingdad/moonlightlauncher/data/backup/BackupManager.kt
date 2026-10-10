package io.github.thecodingdad.moonlightlauncher.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import io.github.thecodingdad.moonlightlauncher.data.db.CollectionEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterEntity
import io.github.thecodingdad.moonlightlauncher.data.db.FilterKind
import io.github.thecodingdad.moonlightlauncher.data.db.FilterScope
import io.github.thecodingdad.moonlightlauncher.data.db.PlatformSource
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileEntity
import io.github.thecodingdad.moonlightlauncher.data.db.HaTileType
import io.github.thecodingdad.moonlightlauncher.data.db.HostConfigEntity
import io.github.thecodingdad.moonlightlauncher.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.moonlightlauncher.data.db.ItemKind
import io.github.thecodingdad.moonlightlauncher.data.db.KindSource
import io.github.thecodingdad.moonlightlauncher.data.db.LauncherDatabase
import io.github.thecodingdad.moonlightlauncher.data.db.MetadataState
import io.github.thecodingdad.moonlightlauncher.data.db.WakeMethod
import io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings
import io.github.thecodingdad.moonlightlauncher.data.settings.SecretStore
import io.github.thecodingdad.moonlightlauncher.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class ItemBackup(
    val normalizedTitle: String,
    val title: String,
    val kind: ItemKind,
    val kindSource: KindSource,
    val favorite: Boolean,
    val hidden: Boolean,
    val playtimeSeconds: Long,
    val lastPlayedAt: Long?,
    val metadataState: MetadataState,
    val metadataQuery: String?,
    val igdbId: Long?,
    val sgdbId: Long?,
    val description: String?,
    val genres: String?,
    val developer: String?,
    val releaseYear: Int?,
    val rating: Float?,
    val coverUrl: String?,
    val heroUrl: String?,
    val logoUrl: String?,
    val iconUrl: String?,
    val animatedCoverUrl: String?,
    val animatedHeroUrl: String?,
    /** Only manual platform choices are kept; automatic ones are detected again. */
    val manualPlatform: String? = null,
)

@Serializable
data class FilterBackup(
    val id: String,
    val name: String,
    val kind: FilterKind,
    val builtin: String? = null,
    val rules: String? = null,
    val scope: FilterScope = FilterScope.GAMES,
    val showInLibrary: Boolean = true,
    val showOnHome: Boolean = false,
    val showInApps: Boolean = false,
)

@Serializable
data class CollectionBackup(val name: String, val members: List<String>)

@Serializable
data class HaTileBackup(val entityId: String, val type: HaTileType, val label: String?, val showOnHome: Boolean)

@Serializable
data class HostConfigBackup(
    val hostUuid: String,
    val wakeMethod: WakeMethod,
    val haWakeEntity: String?,
    val haShutdownEntity: String?,
    val haRestartEntity: String?,
    val haSleepEntity: String?,
    val autoSleepMinutes: Int?,
    val vibepolloUrl: String?,
)

@Serializable
data class ProfileBackup(val name: String, val options: Map<String, JsonElement>, val active: Boolean)

@Serializable
data class EncryptedSecrets(val salt: String, val iv: String, val data: String)

@Serializable
data class BackupFile(
    val format: Int = 1,
    val createdAt: Long,
    val settings: LauncherSettings,
    val items: List<ItemBackup>,
    val collections: List<CollectionBackup>,
    val filters: List<FilterBackup> = emptyList(),
    val haTiles: List<HaTileBackup>,
    val hostConfigs: List<HostConfigBackup>,
    val baseStreamSettings: Map<String, JsonElement>,
    val profiles: List<ProfileBackup>,
    val secrets: EncryptedSecrets? = null,
)

class WrongPasswordException : Exception("Wrong backup password")

/** Export/import of the launcher setup as JSON, optionally with password-encrypted credentials. */
class BackupManager(
    private val context: Context,
    private val db: LauncherDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun create(password: String?): BackupFile = withContext(Dispatchers.IO) {
        val items = db.libraryDao().getAllItems()
        val byId = items.associateBy { it.id }
        val collections = db.collectionDao().observeCollections().first()
        val refs = db.collectionDao().observeMemberships().first()
        val manager = ProfilesManager.getInstance()
        BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = settings.current(),
            items = items
                // Only what the user changed or what took effort to fetch.
                .filter {
                    it.favorite || it.hidden || it.kindSource == KindSource.MANUAL || it.playtimeSeconds > 0 ||
                        it.metadataState != MetadataState.NONE || it.platformSource == PlatformSource.MANUAL
                }
                .map {
                    ItemBackup(
                        it.normalizedTitle, it.title, it.kind, it.kindSource, it.favorite, it.hidden, it.playtimeSeconds,
                        it.lastPlayedAt, it.metadataState, it.metadataQuery, it.igdbId, it.sgdbId, it.description, it.genres,
                        it.developer, it.releaseYear, it.rating, it.coverUrl, it.heroUrl, it.logoUrl, it.iconUrl,
                        it.animatedCoverUrl, it.animatedHeroUrl,
                        manualPlatform = it.platform.takeIf { _ -> it.platformSource == PlatformSource.MANUAL },
                    )
                },
            collections = collections.sortedBy { it.sortOrder }.map { c ->
                CollectionBackup(c.name, refs.filter { it.collectionId == c.id }.mapNotNull { byId[it.itemId]?.normalizedTitle })
            },
            filters = db.filterDao().getAll().sortedBy { it.sortOrder }.map {
                // Groups are matched by name on restore (their ids are collection ids).
                FilterBackup(it.id, it.name, it.kind, it.builtin, it.rules, it.scope, it.showInLibrary, it.showOnHome, it.showInApps)
            },
            haTiles = db.haTileDao().getAll().map { HaTileBackup(it.entityId, it.type, it.label, it.showOnHome) },
            hostConfigs = db.hostConfigDao().observeAll().first().map {
                HostConfigBackup(it.hostUuid, it.wakeMethod, it.haWakeEntity, it.haShutdownEntity, it.haRestartEntity, it.haSleepEntity, it.autoSleepMinutes, it.vibepolloUrl)
            },
            baseStreamSettings = PreferenceManager.getDefaultSharedPreferences(context).all.toJsonMap(),
            profiles = manager.profiles.map { ProfileBackup(it.name, it.options.orEmpty().toJsonMap(), manager.active?.uuid == it.uuid) },
            secrets = password?.takeIf { it.isNotEmpty() }?.let { encryptSecrets(it) },
        )
    }

    private suspend fun restoreFilters(backup: BackupFile) {
        val collectionsByName = db.collectionDao().observeCollections().first().associateBy { it.name }
        val filterDao = db.filterDao()
        if (backup.filters.isEmpty()) {
            // Older backup: every collection becomes a group shown in the library and on home.
            val existing = filterDao.getAll().map { it.id }.toSet()
            filterDao.upsert(
                collectionsByName.values.filter { it.id !in existing }.map {
                    FilterEntity(it.id, it.name, FilterKind.GROUP, scope = FilterScope.GAMES, showOnHome = true, sortOrder = 100 + it.sortOrder)
                },
            )
            return
        }
        filterDao.getAll().forEach { filterDao.delete(it.id) }
        filterDao.upsert(
            backup.filters.mapIndexedNotNull { index, f ->
                val id = when (f.kind) {
                    FilterKind.GROUP -> collectionsByName[f.name]?.id ?: return@mapIndexedNotNull null
                    else -> f.id
                }
                FilterEntity(id, f.name, f.kind, f.builtin, f.rules, f.scope, f.showInLibrary, f.showOnHome, f.showInApps, index)
            },
        )
        settings.update { it.copy(filtersSeeded = true) }
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun decode(text: String): BackupFile = json.decodeFromString(BackupFile.serializer(), text)

    suspend fun restore(backup: BackupFile, password: String?) = withContext(Dispatchers.IO) {
        if (backup.secrets != null && !password.isNullOrEmpty()) decryptSecrets(backup.secrets, password)
        settings.update { backup.settings }

        val dao = db.libraryDao()
        val items = dao.getAllItems()
        val byTitle = items.associateBy { it.normalizedTitle }
        backup.items.forEach { b ->
            val existing = byTitle[b.normalizedTitle] ?: return@forEach
            dao.upsertItem(
                existing.copy(
                    kind = if (b.kindSource == KindSource.MANUAL) b.kind else existing.kind,
                    kindSource = if (b.kindSource == KindSource.MANUAL) KindSource.MANUAL else existing.kindSource,
                    favorite = b.favorite, hidden = b.hidden,
                    playtimeSeconds = maxOf(existing.playtimeSeconds, b.playtimeSeconds),
                    lastPlayedAt = listOfNotNull(existing.lastPlayedAt, b.lastPlayedAt).maxOrNull(),
                    metadataState = b.metadataState, metadataQuery = b.metadataQuery, igdbId = b.igdbId, sgdbId = b.sgdbId,
                    description = b.description, genres = b.genres, developer = b.developer, releaseYear = b.releaseYear,
                    rating = b.rating, coverUrl = b.coverUrl, heroUrl = b.heroUrl, logoUrl = b.logoUrl, iconUrl = b.iconUrl,
                    animatedCoverUrl = b.animatedCoverUrl, animatedHeroUrl = b.animatedHeroUrl,
                    platform = b.manualPlatform ?: existing.platform,
                    platformSource = if (b.manualPlatform != null) PlatformSource.MANUAL else existing.platformSource,
                ),
            )
        }

        val collectionDao = db.collectionDao()
        val existingCollections = collectionDao.observeCollections().first().associateBy { it.name }
        backup.collections.forEachIndexed { index, c ->
            val id = existingCollections[c.name]?.id ?: UUID.randomUUID().toString().also {
                collectionDao.upsert(CollectionEntity(it, c.name, existingCollections.size + index))
            }
            c.members.mapNotNull { byTitle[it]?.id }.forEach { collectionDao.addItem(ItemCollectionCrossRef(it, id)) }
        }
        restoreFilters(backup)

        db.haTileDao().upsert(backup.haTiles.mapIndexed { i, t -> HaTileEntity(t.entityId, t.type, t.label, i, t.showOnHome) })
        backup.hostConfigs.forEach {
            db.hostConfigDao().upsert(
                HostConfigEntity(it.hostUuid, it.wakeMethod, it.haWakeEntity, it.haShutdownEntity, it.haRestartEntity, it.haSleepEntity, it.autoSleepMinutes, it.vibepolloUrl),
            )
        }

        PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
            backup.baseStreamSettings.forEach { (k, v) -> putJson(k, v) }
        }.apply()
        val manager = ProfilesManager.getInstance()
        val existingNames = manager.profiles.map { it.name }.toSet()
        backup.profiles.filter { it.name !in existingNames }.forEach { p ->
            val now = System.currentTimeMillis()
            val profile = SettingsProfile(UUID.randomUUID(), p.name, now, now, HashMap(p.options.mapNotNull { (k, v) -> v.toAny()?.let { k to it } }.toMap()))
            manager.add(profile)
            if (p.active) manager.setActive(profile.uuid)
        }
    }

    /** Writes a timestamped backup into a folder picked with the system file picker; keeps the newest [keep]. */
    suspend fun writeToFolder(treeUri: Uri, keep: Int, password: String? = null) = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: error("Backup folder not accessible")
        val name = "moonlight-launcher-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        val file = folder.createFile("application/json", name) ?: error("Cannot create backup file")
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(encode(create(password)).toByteArray()) }
        folder.listFiles()
            .filter { it.name?.startsWith("moonlight-launcher-") == true && it.name?.endsWith(".json") == true }
            .sortedByDescending { it.name }
            .drop(keep.coerceAtLeast(1))
            .forEach { it.delete() }
    }

    suspend fun writeToUri(uri: Uri, password: String?) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encode(create(password)).toByteArray()) }
    }

    suspend fun readFromUri(uri: Uri): BackupFile = withContext(Dispatchers.IO) {
        decode(context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read file"))
    }

    // --- credentials, encrypted with a key derived from the user's password ---

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun encryptSecrets(password: String): EncryptedSecrets {
        val values = secrets.keys().associateWith { secrets.get(it) }.filterValues { it != null }
        val plain = json.encodeToString(stringMap, values.mapValues { it.value!! })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)) }
        return EncryptedSecrets(b64(salt), b64(iv), b64(cipher.doFinal(plain.toByteArray())))
    }

    private fun decryptSecrets(enc: EncryptedSecrets, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, unb64(enc.salt)), GCMParameterSpec(128, unb64(enc.iv)))
        val plain = runCatching { String(cipher.doFinal(unb64(enc.data))) }.getOrElse { throw WrongPasswordException() }
        val map = json.decodeFromString(stringMap, plain)
        map.forEach { (k, v) -> secrets.put(k, v) }
    }

    private val stringMap = MapSerializer(String.serializer(), String.serializer())

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun Map<String, *>.toJsonMap(): Map<String, JsonElement> = mapNotNull { (k, v) ->
        when (v) {
            is Boolean -> k to JsonPrimitive(v)
            is Number -> k to JsonPrimitive(v)
            is String -> k to JsonPrimitive(v)
            null -> k to JsonNull
            else -> null // string sets etc. are not used by stream settings
        }
    }.toMap()

    private fun JsonElement.toAny(): Any? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return if (p.isString) p.content else p.booleanOrNull ?: p.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: p.doubleOrNull
    }

    private fun android.content.SharedPreferences.Editor.putJson(key: String, value: JsonElement) {
        when (val v = value.toAny()) {
            is Boolean -> putBoolean(key, v)
            is Int -> putInt(key, v)
            is Long -> putLong(key, v)
            is Double -> putFloat(key, v.toFloat())
            is String -> putString(key, v)
        }
    }
}
