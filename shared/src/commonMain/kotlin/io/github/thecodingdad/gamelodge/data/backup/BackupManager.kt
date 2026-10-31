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
import io.github.thecodingdad.gamelodge.data.db.SyncStateEntity
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
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import java.io.File
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
    val communityRating: Float? = null,
    /** The user's own 1–5 stars. */
    val userRating: Int? = null,
    /** Hidden on this device only (not on the other devices of a host). */
    val hiddenHere: Boolean = false,
)

/**
 * Since when this device shares play data with a host: restored, the new installation doesn't send
 * its playtime once more as earlier playtime (it reads everything from the host again).
 */
@Serializable
data class PlaySyncBackup(val hostKey: String, val syncStart: Long)

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
    val vpnTunnel: String? = null,
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
    val playSync: List<PlaySyncBackup> = emptyList(),
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
    /** The app's files dir; own images live in `own-artwork` there, the cache in `artwork`. */
    private val filesDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val ownDir get() = File(filesDir, "own-artwork")

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
                    it.favorite || it.hidden || it.kindSource == KindSource.MANUAL || it.playtimeSeconds > 0 || it.userRating != null ||
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
                        communityRating = it.communityRating,
                        userRating = it.userRating,
                        hiddenHere = it.hiddenHere,
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
                HostConfigBackup(it.hostUuid, it.wakeMethod, it.haWakeEntity, it.haShutdownEntity, it.haRestartEntity, it.haSleepEntity, it.autoSleepMinutes, it.wakeTimeoutSeconds, it.vpnTunnel)
            },
            baseStreamSettings = stream.baseSettings(),
            profiles = stream.profiles(),
            secrets = password?.takeIf { it.isNotEmpty() }?.let { encryptSecrets(it) },
            playSync = db.playDataDao().states().map { PlaySyncBackup(it.hostKey, it.syncStart) },
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

    /** The image references of an item that may be files; animated ones last. */
    private fun ItemBackup.imageValues(): List<String> =
        listOfNotNull(coverUrl, heroUrl, logoUrl, iconUrl, tileUrl, squareUrl)

    private fun ItemBackup.animatedValues(): List<String> = listOfNotNull(animatedCoverUrl, animatedHeroUrl)

    /**
     * The backup as a ZIP: `backup.json` plus the image files it refers to. Own (hand-picked)
     * images are always included; the downloaded cache and the animated images follow the
     * [LauncherSettings.backupArtwork] / [LauncherSettings.backupAnimatedArtwork] settings.
     */
    suspend fun export(password: String?): ByteArray = withContext(Dispatchers.IO) {
        val backup = create(password)
        val s = settings.current()
        val own = LinkedHashMap<String, File>()
        val cache = LinkedHashMap<String, File>()
        backup.items.forEach { item ->
            (item.imageValues() + item.animatedValues()).filterNot { it.startsWith("http") }.forEach { path ->
                File(path).takeIf { it.isFile }?.let { own.putIfAbsent(it.name, it) }
            }
            if (s.backupArtwork) {
                item.imageValues().forEach { url -> ArtworkFiles.cachedFileForUrl(url)?.let { cache.putIfAbsent(it.name, it) } }
                if (s.backupAnimatedArtwork) item.animatedValues().forEach { url -> ArtworkFiles.cachedFileForUrl(url)?.let { cache.putIfAbsent(it.name, it) } }
            }
        }
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(MANIFEST)); zip.write(encode(backup).toByteArray()); zip.closeEntry()
            fun add(prefix: String, files: Map<String, File>) = files.forEach { (name, file) ->
                zip.putNextEntry(java.util.zip.ZipEntry("$prefix$name")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            add(OWN_PREFIX, own)
            add(CACHE_PREFIX, cache)
        }
        out.toByteArray()
    }

    /** The manifest of [bytes] (ZIP or legacy plain JSON), to check for a password before restoring. */
    fun readManifest(bytes: ByteArray): BackupFile =
        if (isZip(bytes)) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == MANIFEST }
                    ?: error("No $MANIFEST in the archive")
                decode(zip.readBytes().decodeToString())
            }
        } else {
            decode(bytes.decodeToString())
        }

    /** Restores from [bytes] (ZIP with images, or legacy plain JSON). */
    suspend fun import(bytes: ByteArray, password: String?) = withContext(Dispatchers.IO) {
        if (!isZip(bytes)) return@withContext restore(decode(bytes.decodeToString()), password)
        var manifest: String? = null
        val ownNames = HashSet<String>()
        val ownTarget = ownDir.apply { mkdirs() }
        // The cache dir is only touched when the archive actually carries cached images.
        fun cacheTarget() = ArtworkFiles.cacheDir().apply { mkdirs() }
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == MANIFEST -> manifest = zip.readBytes().decodeToString()
                    // Flat basenames only: a name with a path separator is rejected (no zip-slip).
                    !entry.isDirectory && name.startsWith(OWN_PREFIX) -> safeName(name, OWN_PREFIX)?.let { bn ->
                        File(ownTarget, bn).outputStream().use { zip.copyTo(it) }; ownNames += bn
                    }
                    !entry.isDirectory && name.startsWith(CACHE_PREFIX) -> safeName(name, CACHE_PREFIX)?.let { bn ->
                        File(cacheTarget(), bn).outputStream().use { zip.copyTo(it) }; ArtworkFiles.registerCached(bn)
                    }
                }
                zip.closeEntry()
            }
        }
        restore(decode(manifest ?: error("No $MANIFEST in the archive")), password, ownNames)
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun safeName(name: String, prefix: String): String? =
        name.removePrefix(prefix).takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && it != ".." }

    suspend fun restore(backup: BackupFile, password: String?, ownNames: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        // Own images extracted from a backup live in this device's own-artwork folder; the paths
        // in the backup come from the source device and are repointed to it here (by file name).
        fun repoint(value: String?): String? = value?.let {
            if (it.startsWith("http") || ownNames.isEmpty()) it
            else File(it).name.takeIf { n -> n in ownNames }?.let { n -> File(ownDir, n).absolutePath } ?: it
        }
        if (backup.secrets != null && !password.isNullOrEmpty()) decryptSecrets(backup.secrets, password)
        // Home app, backup folder and the setup wizard's state belong to the device, not to the backup.
        settings.update {
            backup.settings.copy(
                launcherMode = it.launcherMode, backupFolderUri = it.backupFolderUri, autoBackup = it.autoBackup,
                backupKeepCount = it.backupKeepCount,
                // A restore inside the setup wizard must not end or move it.
                setupDone = it.setupDone, setupStep = it.setupStep, setupRestore = it.setupRestore,
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
                    favorite = b.favorite, hidden = b.hidden, hiddenHere = b.hidden && b.hiddenHere,
                    playtimeSeconds = maxOf(existing.playtimeSeconds, b.playtimeSeconds),
                    lastPlayedAt = listOfNotNull(existing.lastPlayedAt, b.lastPlayedAt).maxOrNull(),
                    metadataState = b.metadataState, metadataQuery = b.metadataQuery, igdbId = b.igdbId, sgdbId = b.sgdbId,
                    description = b.description, genres = b.genres, developer = b.developer, releaseYear = b.releaseYear,
                    rating = b.rating, coverUrl = repoint(b.coverUrl), heroUrl = repoint(b.heroUrl), logoUrl = repoint(b.logoUrl), iconUrl = repoint(b.iconUrl),
                    animatedCoverUrl = repoint(b.animatedCoverUrl), animatedHeroUrl = repoint(b.animatedHeroUrl),
                    platform = b.manualPlatform ?: existing.platform,
                    tileUrl = repoint(b.tileUrl) ?: existing.tileUrl,
                    squareUrl = repoint(b.squareUrl) ?: existing.squareUrl,
                    communityRating = b.communityRating ?: existing.communityRating,
                    userRating = b.userRating ?: existing.userRating,
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
                HostConfigEntity(it.hostUuid, it.wakeMethod, it.haWakeEntity, it.haShutdownEntity, it.haRestartEntity, it.haSleepEntity, it.autoSleepMinutes, it.wakeTimeoutSeconds, it.vpnTunnel),
            )
        }

        // Read from the host again from the start (the copy of the other devices' sessions isn't in the backup).
        val playData = db.playDataDao()
        backup.playSync.forEach { if (playData.state(it.hostKey) == null) playData.upsertState(SyncStateEntity(it.hostKey, it.syncStart, 0)) }

        stream.restore(backup.baseStreamSettings, backup.profiles)
    }

    // --- credentials, encrypted with a key derived from the user's password ---

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun encryptSecrets(password: String): EncryptedSecrets {
        // The device's own identity (pairing keys, the id its play sessions are named by) stays with the device.
        val values = secrets.keys().filterNot { it.contains("_identity") || it == SecretStore.Keys.PLAY_SYNC_ID }.associateWith { secrets.get(it) }.filterValues { it != null }
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
        map.filterKeys { it != SecretStore.Keys.PLAY_SYNC_ID }.forEach { (k, v) -> secrets.put(k, v) }
    }

    private val stringMap = MapSerializer(String.serializer(), String.serializer())

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val FILE_PREFIX = "gamelodge-"

        /** Entry names inside the backup ZIP. */
        private const val MANIFEST = "backup.json"
        private const val OWN_PREFIX = "own/"
        private const val CACHE_PREFIX = "art/"

        /** "gamelodge-20261016-143000.zip": sorts by time. */
        fun fileName(now: Date = Date()): String = FILE_PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".zip"

        /** Backups of the app's former name ("Moonlight Launcher") count too. */
        private const val OLD_PREFIX = "moonlight-launcher-"

        /** New backups are ".zip"; older ".json" ones still restore. */
        fun isBackupName(name: String) =
            (name.startsWith(FILE_PREFIX) || name.startsWith(OLD_PREFIX)) && (name.endsWith(".zip") || name.endsWith(".json"))

        /** When a backup named by [fileName] was made (null: other name). */
        fun createdAt(name: String): Long? = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .parse(name.removePrefix(FILE_PREFIX).removePrefix(OLD_PREFIX).removeSuffix(".zip").removeSuffix(".json"))?.time
        }.getOrNull()
    }
}

/** What a backup contains, for the confirm dialog before restoring. */
data class BackupSummary(
    val createdAt: Long,
    val games: Int,
    val collections: Int,
    val filters: Int,
    val haTiles: Int,
    val hosts: Int,
    val profiles: Int,
    val hasCredentials: Boolean,
)

fun BackupFile.summary() = BackupSummary(
    createdAt = createdAt,
    games = items.size,
    collections = collections.size,
    filters = filters.size,
    haTiles = haTiles.size,
    hosts = hostConfigs.size,
    profiles = profiles.size,
    hasCredentials = secrets != null,
)

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
