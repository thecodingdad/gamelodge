package io.github.thecodingdad.gamelodge.data.backup

import io.github.thecodingdad.gamelodge.data.db.CollectionEntity
import io.github.thecodingdad.gamelodge.data.db.FilterEntity
import io.github.thecodingdad.gamelodge.data.db.FilterKind
import io.github.thecodingdad.gamelodge.data.db.FilterScope
import io.github.thecodingdad.gamelodge.data.db.PlatformSource
import io.github.thecodingdad.gamelodge.data.db.HaTileEntity
import io.github.thecodingdad.gamelodge.data.db.HaTileType
import io.github.thecodingdad.gamelodge.data.db.HostConfigEntity
import io.github.thecodingdad.gamelodge.data.db.ItemCollectionCrossRef
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.KindSource
import io.github.thecodingdad.gamelodge.data.db.LauncherDatabase
import io.github.thecodingdad.gamelodge.data.db.MetadataState
import io.github.thecodingdad.gamelodge.data.db.WakeMethod
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.data.settings.SettingsRepository
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
import java.util.Base64
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
    /** Landscape and square tiles from SteamGridDB (own images are files on the device and stay there). */
    val tileUrl: String? = null,
    val squareUrl: String? = null,
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
    val wakeTimeoutSeconds: Int = 120,
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

/** Stream settings live with the platform's streaming code (Artemis on Android, Moonlight-Qt on the PC). */
interface StreamSettingsBackup {
    /** Base settings (option key → value) every profile starts from. */
    fun baseSettings(): Map<String, JsonElement>

    fun profiles(): List<ProfileBackup>

    /** Applies [base] and adds the [profiles] not there yet (by name). */
    fun restore(base: Map<String, JsonElement>, profiles: List<ProfileBackup>)
}

/** Export/import of the launcher setup as JSON, optionally with password-encrypted credentials. */
class BackupManager(
    private val db: LauncherDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val stream: StreamSettingsBackup,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun create(password: String?): BackupFile = withContext(Dispatchers.IO) {
        val items = db.libraryDao().getAllItems()
        val byId = items.associateBy { it.id }
        val collections = db.collectionDao().observeCollections().first()
        val refs = db.collectionDao().observeMemberships().first()
        BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = settings.current(),
            items = items
                // Only what the user changed or what took effort to fetch.
                .filter {
                    it.favorite || it.hidden || it.kindSource == KindSource.MANUAL || it.playtimeSeconds > 0 ||
                        it.metadataState != MetadataState.NONE || it.platformSource == PlatformSource.MANUAL ||
                        it.tileUrl?.startsWith("http") == true || it.squareUrl?.startsWith("http") == true
                }
                .map {
                    ItemBackup(
                        it.normalizedTitle, it.title, it.kind, it.kindSource, it.favorite, it.hidden, it.playtimeSeconds,
                        it.lastPlayedAt, it.metadataState, it.metadataQuery, it.igdbId, it.sgdbId, it.description, it.genres,
                        it.developer, it.releaseYear, it.rating, it.coverUrl, it.heroUrl, it.logoUrl, it.iconUrl,
                        it.animatedCoverUrl, it.animatedHeroUrl,
                        manualPlatform = it.platform.takeIf { _ -> it.platformSource == PlatformSource.MANUAL },
                        tileUrl = it.tileUrl?.takeIf { url -> url.startsWith("http") },
                        squareUrl = it.squareUrl?.takeIf { url -> url.startsWith("http") },
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
                HostConfigBackup(it.hostUuid, it.wakeMethod, it.haWakeEntity, it.haShutdownEntity, it.haRestartEntity, it.haSleepEntity, it.autoSleepMinutes, it.wakeTimeoutSeconds)
            },
            baseStreamSettings = stream.baseSettings(),
            profiles = stream.profiles(),
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
        // Home app and backup folder belong to the device, not to the backup.
        settings.update {
            backup.settings.copy(
                launcherMode = it.launcherMode, backupFolderUri = it.backupFolderUri, autoBackup = it.autoBackup,
                backupKeepCount = it.backupKeepCount,
            )
        }

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
                    tileUrl = b.tileUrl ?: existing.tileUrl,
                    squareUrl = b.squareUrl ?: existing.squareUrl,
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
            c.members.mapNotNull { byTitle[it]?.id }.forEachIndexed { i, item -> collectionDao.addItem(ItemCollectionCrossRef(item, id, i)) }
        }
        restoreFilters(backup)

        db.haTileDao().upsert(backup.haTiles.mapIndexed { i, t -> HaTileEntity(t.entityId, t.type, t.label, i, t.showOnHome) })
        backup.hostConfigs.forEach {
            db.hostConfigDao().upsert(
                HostConfigEntity(it.hostUuid, it.wakeMethod, it.haWakeEntity, it.haShutdownEntity, it.haRestartEntity, it.haSleepEntity, it.autoSleepMinutes, it.wakeTimeoutSeconds),
            )
        }

        stream.restore(backup.baseStreamSettings, backup.profiles)
    }

    // --- credentials, encrypted with a key derived from the user's password ---

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun encryptSecrets(password: String): EncryptedSecrets {
        // The device's own identity (pairing keys) stays with the device.
        val values = secrets.keys().filterNot { it.contains("_identity") }.associateWith { secrets.get(it) }.filterValues { it != null }
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

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val FILE_PREFIX = "gamelodge-"

        /** "gamelodge-20261016-143000.json": sorts by time. */
        fun fileName(now: Date = Date()): String = FILE_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".json"

        /** Backups of the app's former name ("Moonlight Launcher") count too. */
        private const val OLD_PREFIX = "moonlight-launcher-"

        fun isBackupName(name: String) = (name.startsWith(FILE_PREFIX) || name.startsWith(OLD_PREFIX)) && name.endsWith(".json")

        /** When a backup named by [fileName] was made (null: other name). */
        fun createdAt(name: String): Long? =
            runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(name.removePrefix(FILE_PREFIX).removePrefix(OLD_PREFIX).removeSuffix(".json"))?.time }.getOrNull()
    }
}

/** Plain values (settings stores) as JSON, for the stream settings in backups. */
fun Map<String, *>.toJsonMap(): Map<String, JsonElement> = mapNotNull { (k, v) ->
    when (v) {
        is Boolean -> k to JsonPrimitive(v)
        is Number -> k to JsonPrimitive(v)
        is String -> k to JsonPrimitive(v)
        null -> k to JsonNull
        else -> null // string sets etc. are not used by stream settings
    }
}.toMap()

fun JsonElement.toAny(): Any? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return if (p.isString) p.content else p.booleanOrNull ?: p.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: p.doubleOrNull
}
