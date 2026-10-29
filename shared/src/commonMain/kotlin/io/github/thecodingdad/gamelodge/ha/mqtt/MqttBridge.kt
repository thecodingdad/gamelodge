package io.github.thecodingdad.gamelodge.ha.mqtt

import io.github.thecodingdad.gamelodge.LauncherContainer
import io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.db.LibraryEntry
import io.github.thecodingdad.gamelodge.data.db.MetadataState
import io.github.thecodingdad.gamelodge.data.db.TargetType
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.SecretStore
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.stream.LaunchCoordinator.NowPlaying
import io.github.thecodingdad.gamelodge.ui.Battery
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.ArtworkPlatform
import io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl
import io.github.thecodingdad.gamelodge.util.Log
import io.github.thecodingdad.gamelodge.vpn.NoVpn
import io.github.thecodingdad.gamelodge.vpn.VpnStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.io.File
import java.time.Instant
import java.util.UUID

enum class MqttStatus { OFF, CONNECTING, CONNECTED, BAD_LOGIN, FAILED, CERT_UNTRUSTED, CERT_CHANGED }

/** [fingerprint]: the broker's certificate to confirm (CERT_UNTRUSTED / CERT_CHANGED). */
data class MqttState(val status: MqttStatus, val error: String? = null, val fingerprint: String? = null)

/**
 * Sends the launcher's status to Home Assistant over MQTT: one device per install, found by MQTT
 * discovery, with library counts, playtime, the running or last played title (with its cover and
 * hero image) and what the launcher does. Only sends: nothing coming in changes the launcher.
 * Values are retained, so HA keeps the last ones while the device sleeps; the broker sets the
 * status to "offline" (the last will) when the connection is lost.
 */
class MqttBridge(private val c: LauncherContainer) {
    private val _state = MutableStateFlow(MqttState(MqttStatus.OFF))
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val restarts = MutableStateFlow(0)
    private var job: Job? = null
    @Volatile private var connection: MqttConnection? = null
    /** The device was just removed from HA: its connection ends without sending "offline" again. */
    @Volatile private var removed = false

    private data class Config(
        val host: String, val port: Int, val tls: Boolean, val user: String, val deviceName: String,
        val allowSelfSigned: Boolean, val certPin: String?,
    )

    private fun config(s: LauncherSettings): Config? =
        if (!s.mqttEnabled || s.mqttHost.isBlank()) null
        else Config(
            s.mqttHost.trim(), s.mqttPort, s.mqttTls, s.mqttUser.trim(), s.mqttDeviceName.trim().ifEmpty { c.platform.deviceName },
            s.mqttTls && s.mqttAllowSelfSigned, s.mqttCertPin.ifEmpty { null },
        )

    fun start() {
        if (job != null) return
        job = c.scope.launch {
            combine(c.settings.settings.map(::config).distinctUntilChanged(), restarts) { cfg, _ -> cfg }.collectLatest { cfg ->
                if (cfg == null) _state.value = MqttState(MqttStatus.OFF) else connectLoop(cfg)
            }
        }
    }

    /** Connects again (e.g. after the password changed). */
    fun restart() {
        restarts.value++
    }

    /**
     * Removes this device and its values from Home Assistant (empty retained messages) and switches
     * MQTT off, else the next connection would add it again. False when not connected.
     */
    suspend fun removeFromHa(): Boolean {
        val conn = connection ?: return false
        val id = deviceId()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                conn.publish(discoveryTopic(id), ByteArray(0), retain = true)
                Topic.entries.forEach { conn.publish(it.of(base(id)), ByteArray(0), retain = true) }
            }.isSuccess
        }
        if (ok) {
            removed = true
            c.settings.update { it.copy(mqttEnabled = false) }
        }
        return ok
    }

    /** Outcome of [test]. */
    sealed interface TestResult {
        data object Ok : TestResult
        data object BadLogin : TestResult
        data class Certificate(val fingerprint: String, val changed: Boolean) : TestResult
        data class Failed(val message: String) : TestResult
    }

    /**
     * Connects once with these (not yet saved) settings and leaves again; the running connection
     * isn't touched. [password] null: the saved one.
     */
    suspend fun test(
        host: String, port: Int, tls: Boolean, user: String, password: String?, allowSelfSigned: Boolean, pin: String?,
    ): TestResult = withContext(Dispatchers.IO) {
        val options = MqttOptions(
            host = host.trim(), port = port, tls = tls, username = user.trim().ifEmpty { null },
            password = password ?: c.secrets.get(SecretStore.Keys.MQTT_PASSWORD),
            // Its own client id: the broker would otherwise drop the running connection.
            clientId = "gamelodge-test-" + UUID.randomUUID().toString().take(8),
            keepAliveSeconds = 10, allowSelfSigned = tls && allowSelfSigned, pinnedCertificate = pin,
        )
        try {
            MqttConnection.open(options).close()
            TestResult.Ok
        } catch (e: MqttCertificateException) {
            TestResult.Certificate(e.fingerprint, e.changed)
        } catch (e: MqttRefusedException) {
            if (e.badLogin) TestResult.BadLogin else TestResult.Failed(e.message.orEmpty())
        } catch (e: Exception) {
            TestResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun connectLoop(cfg: Config) {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            var connected = false
            try {
                session(cfg) { connected = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is MqttCertificateException) {
                    // Waits until the user confirms the certificate (or changes the settings).
                    _state.value = MqttState(if (e.changed) MqttStatus.CERT_CHANGED else MqttStatus.CERT_UNTRUSTED, fingerprint = e.fingerprint)
                    return
                }
                if (e is MqttRefusedException && e.badLogin) {
                    // Trying again with the same login won't help: wait for new settings.
                    _state.value = MqttState(MqttStatus.BAD_LOGIN)
                    return
                }
                Log.w("MQTT: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = MqttState(MqttStatus.FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                connection = null
            }
            if (connected) backoff = MIN_BACKOFF_MS
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** One connection: publishes until it breaks (throws) or is cancelled. */
    private suspend fun session(cfg: Config, onConnected: () -> Unit) = coroutineScope {
        _state.value = MqttState(MqttStatus.CONNECTING)
        val id = deviceId()
        val base = base(id)
        val options = MqttOptions(
            host = cfg.host, port = cfg.port, tls = cfg.tls,
            username = cfg.user.ifEmpty { null }, password = c.secrets.get(SecretStore.Keys.MQTT_PASSWORD),
            clientId = "gamelodge-$id", keepAliveSeconds = KEEP_ALIVE_SECONDS,
            will = MqttWill(Topic.STATE.of(base), OFFLINE),
            allowSelfSigned = cfg.allowSelfSigned, pinnedCertificate = cfg.certPin,
        )
        val conn = withContext(Dispatchers.IO) { MqttConnection.open(options) }
        removed = false
        // Closing the socket also ends the blocking read below when this session is cancelled. A clean
        // disconnect drops the last will: "offline" is sent here then (not after removing the device).
        launch {
            try {
                awaitCancellation()
            } finally {
                if (!removed) runCatching { conn.publish(Topic.STATE.of(base), OFFLINE, retain = true) }
                conn.close()
            }
        }
        connection = conn
        onConnected()
        _state.value = MqttState(MqttStatus.CONNECTED)

        // Home Assistant announces its (re)start here: then it wants the discovery again.
        val haStarts = MutableStateFlow(0)
        withContext(Dispatchers.IO) { conn.subscribe(HA_STATUS_TOPIC) }
        launch(Dispatchers.IO) {
            conn.readLoop { m -> if (m.topic == HA_STATUS_TOPIC && m.payload.decodeToString() == "online") haStarts.value++ }
        }
        launch(Dispatchers.IO) {
            while (true) {
                delay(KEEP_ALIVE_SECONDS * 500L)
                conn.ping()
            }
        }

        val sent = HashMap<String, String>()
        var discoveryFor = -1
        var imagesFor: ImageKey? = null
        combine(snapshots(), haStarts) { s, n -> s to n }.collect { (s, n) ->
            withContext(Dispatchers.IO) {
                if (n != discoveryFor) {
                    conn.publish(discoveryTopic(id), discovery(id, base, cfg).toString(), retain = true)
                    discoveryFor = n
                    sent.clear()
                    imagesFor = null
                }
                fun put(topic: Topic, value: String?) {
                    if (value == null || sent[topic.name] == value) return
                    conn.publish(topic.of(base), value, retain = true)
                    sent[topic.name] = value
                }
                put(Topic.STATE, s.status)
                put(Topic.LIBRARY, s.library)
                put(Topic.TITLE, s.title)
                put(Topic.VPN, s.vpn)
                put(Topic.BATTERY, s.battery)
                put(Topic.VERSION, c.platform.versionName)
                if (s.image != null && s.image != imagesFor) {
                    val cover = runCatching { cover(s.image.entry) }.onFailure { Log.w("MQTT cover: ${it.message}") }.getOrNull()
                    val hero = runCatching { hero(s.image.entry) }.onFailure { Log.w("MQTT hero: ${it.message}") }.getOrNull() ?: cover
                    cover?.let { conn.publish(Topic.COVER.of(base), it, retain = true) }
                    hero?.let { conn.publish(Topic.HERO.of(base), it, retain = true) }
                    imagesFor = s.image
                }
            }
        }
    }

    // --- What is sent ---------------------------------------------------------------------------

    /** The title the images belong to; a new cover or hero address sends them again. */
    private data class ImageKey(val id: String, val cover: String?, val hero: String?, val boxArt: String?) {
        /** Not part of the equality: only the addresses decide whether the images are sent again. */
        lateinit var entry: LibraryEntry
    }

    private data class Snapshot(
        val status: String,
        val library: String,
        val title: String?,
        val vpn: String?,
        val battery: String?,
        val image: ImageKey?,
    )

    private fun snapshots() = run {
        val vpn = c.vpn.state.map { it.status == VpnStatus.UP }
        // Battery level and charging, read once a minute.
        val battery = flow {
            while (true) {
                emit(PlatformUi.current.battery())
                delay(60_000)
            }
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
        combine(c.library.allEntries, c.launcher.nowPlaying, ScreensaverControl.active, vpn, battery) { entries, now, screensaver, vpnUp, power ->
            snapshot(entries, now, screensaver, vpnUp, power)
        }.distinctUntilChanged()
    }

    private fun snapshot(entries: List<LibraryEntry>, now: NowPlaying?, screensaver: Boolean, vpnUp: Boolean, power: Battery?): Snapshot {
        val visible = entries.filterNot { it.item.hidden }
        val library = buildJsonObject {
            put("games", visible.count { it.item.kind == ItemKind.GAME })
            put("apps", visible.count { it.item.kind == ItemKind.APP })
            put("playtime_hours", hours(entries.sumOf { it.item.playtimeSeconds }))
            put("review", visible.count { it.item.metadataState == MetadataState.REVIEW })
        }.toString()
        // After a restart nothing was started yet: the last played title (Android's usage data included).
        val current = now ?: entries.filter { it.item.lastPlayedAt != null }.maxByOrNull { it.item.lastPlayedAt!! }
            ?.let { NowPlaying(it, it.targets.firstOrNull() ?: return@let null, it.item.lastPlayedAt!!, running = false) }
        val running = current?.running == true
        val status = when {
            running && current!!.target.type == TargetType.REMOTE -> "streaming"
            running -> "playing"
            screensaver -> "screensaver"
            else -> "idle"
        }
        // The entry of the start may be older than the library's (playtime, artwork).
        val entry = current?.let { cur -> entries.firstOrNull { it.item.id == cur.entry.item.id } ?: cur.entry }
        val title = if (current == null || entry == null) null else buildJsonObject {
            put("title", entry.item.title)
            put("kind", if (entry.item.kind == ItemKind.GAME) "game" else "app")
            // Only known for what this launcher started.
            if (now != null) {
                put("source", when (current.target.type) { TargetType.LOCAL -> "local"; TargetType.ROM -> "emulator"; TargetType.REMOTE -> "host" })
                current.target.hostName?.let { put("host", it) }
            }
            entry.item.platform?.let { put("platform", it) }
            put("started_at", Instant.ofEpochMilli(current.startedAt).toString())
            put("playtime_hours", hours(entry.item.playtimeSeconds))
            put("running", running)
        }.toString()
        val image = entry?.let { e ->
            ImageKey(e.item.id, e.item.coverUrl, e.item.heroUrl, e.boxArtPath).also { it.entry = e }
        }
        val battery = power?.let { buildJsonObject { put("level", it.percent); put("charging", it.charging) }.toString() }
        return Snapshot(status, library, title, if (c.vpn === NoVpn) null else if (vpnUp) "ON" else "OFF", battery, image)
    }

    private fun hours(seconds: Long) = Math.round(seconds / 360.0) / 10.0

    // --- Images ---------------------------------------------------------------------------------

    private suspend fun cover(e: LibraryEntry): ByteArray? {
        e.item.coverUrl?.let(::imageBytes)?.let { jpegOf(it, COVER_SIZE) }?.let { return it }
        e.boxArtPath?.let(::imageBytes)?.let { jpegOf(it, COVER_SIZE) }?.let { return it }
        val pkg = e.localTarget?.packageName ?: return null
        val art = ArtworkPlatform.current
        return (art.generatedCover(pkg) ?: art.appIcon(pkg))?.let { jpegOf(it, COVER_SIZE) }
    }

    private suspend fun hero(e: LibraryEntry): ByteArray? {
        e.item.heroUrl?.let(::imageBytes)?.let { jpegOf(it, HERO_SIZE) }?.let { return it }
        val pkg = e.localTarget?.packageName ?: return null
        return ArtworkPlatform.current.generatedHero(pkg)?.let { jpegOf(it, HERO_SIZE) }
    }

    /** The image at [url]: the launcher's own file, the stored download, else fetched now. */
    private fun imageBytes(url: String): ByteArray? {
        if (url.startsWith("/")) return File(url).takeIf { it.isFile }?.readBytes()
        if (!url.startsWith("http")) return null
        (ArtworkFiles.model(url) as? File)?.takeIf { it.isFile }?.let { return it.readBytes() }
        val request = okhttp3.Request.Builder().url(url).build()
        return http.newCall(request).execute().use { r ->
            val body = r.body
            if (!r.isSuccessful || body == null || body.contentLength() > MAX_DOWNLOAD) null
            else body.bytes().takeIf { it.size <= MAX_DOWNLOAD }
        }
    }

    private val http by lazy { okhttp3.OkHttpClient() }

    // --- Discovery ------------------------------------------------------------------------------

    private enum class Topic(val path: String) {
        STATE("state"), LIBRARY("library"), TITLE("title"), VPN("vpn"), BATTERY("battery"), VERSION("version"), COVER("cover"), HERO("hero");

        fun of(base: String) = "$base/$path"
    }

    private fun base(id: String) = "gamelodge/$id"
    private fun discoveryTopic(id: String) = "homeassistant/device/gamelodge_$id/config"

    /** Device discovery: the device and all its entities in one retained message. */
    private suspend fun discovery(id: String, base: String, cfg: Config): JsonObject {
        val version = c.platform.versionName
        val hasBattery = withContext(Dispatchers.IO) { PlatformUi.current.battery() } != null
        val model = if (System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)) "Android" else "Windows"
        suspend fun entity(key: String, platform: String, name: StringResource, topic: Topic, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
            key to buildJsonObject {
                put("p", platform)
                put("unique_id", "gamelodge_${id}_$key")
                put("name", getString(name))
                if (platform == "image") {
                    put("image_topic", topic.of(base))
                    put("content_type", "image/jpeg")
                } else {
                    put("state_topic", topic.of(base))
                }
                extra()
            }
        val components = listOfNotNull(
            entity("online", "binary_sensor", Res.string.mqtt_entity_online, Topic.STATE) {
                put("device_class", "connectivity")
                put("value_template", "{{ 'ON' if value != '$OFFLINE' else 'OFF' }}")
                put("entity_category", "diagnostic")
            },
            entity("playing", "binary_sensor", Res.string.mqtt_entity_playing, Topic.STATE) {
                put("device_class", "running")
                put("value_template", "{{ 'ON' if value in ['playing', 'streaming'] else 'OFF' }}")
                put("icon", "mdi:gamepad-variant")
            },
            entity("status", "sensor", Res.string.mqtt_entity_status, Topic.STATE) {
                put("device_class", "enum")
                putJsonArray("options") { listOf(OFFLINE, "idle", "playing", "streaming", "screensaver").forEach { add(JsonPrimitive(it)) } }
                put("icon", "mdi:controller")
            },
            entity("title", "sensor", Res.string.mqtt_entity_title, Topic.TITLE) {
                put("value_template", "{{ value_json.title }}")
                put("json_attributes_topic", Topic.TITLE.of(base))
                put("icon", "mdi:gamepad-square")
            },
            entity("games", "sensor", Res.string.mqtt_entity_games, Topic.LIBRARY) {
                put("value_template", "{{ value_json.games }}")
                put("state_class", "measurement")
                put("icon", "mdi:gamepad-variant-outline")
            },
            entity("apps", "sensor", Res.string.mqtt_entity_apps, Topic.LIBRARY) {
                put("value_template", "{{ value_json.apps }}")
                put("state_class", "measurement")
                put("icon", "mdi:apps")
            },
            entity("playtime", "sensor", Res.string.mqtt_entity_playtime, Topic.LIBRARY) {
                put("value_template", "{{ value_json.playtime_hours }}")
                put("device_class", "duration")
                put("unit_of_measurement", "h")
                put("state_class", "total_increasing")
                put("suggested_display_precision", 1)
            },
            entity("review", "sensor", Res.string.mqtt_entity_review, Topic.LIBRARY) {
                put("value_template", "{{ value_json.review }}")
                put("state_class", "measurement")
                put("entity_category", "diagnostic")
                put("icon", "mdi:magnify-scan")
                put("enabled_by_default", false)
            },
            entity("version", "sensor", Res.string.mqtt_entity_version, Topic.VERSION) {
                put("entity_category", "diagnostic")
                put("icon", "mdi:information-outline")
                put("enabled_by_default", false)
            },
            if (c.vpn === NoVpn) null else entity("vpn", "binary_sensor", Res.string.mqtt_entity_vpn, Topic.VPN) {
                put("device_class", "connectivity")
                put("icon", "mdi:vpn")
                put("enabled_by_default", false)
            },
            if (!hasBattery) null else entity("battery", "sensor", Res.string.mqtt_entity_battery, Topic.BATTERY) {
                put("device_class", "battery")
                put("unit_of_measurement", "%")
                put("state_class", "measurement")
                put("value_template", "{{ value_json.level }}")
                put("enabled_by_default", false)
            },
            if (!hasBattery) null else entity("charging", "binary_sensor", Res.string.mqtt_entity_charging, Topic.BATTERY) {
                put("device_class", "battery_charging")
                put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}")
                put("enabled_by_default", false)
            },
            entity("cover", "image", Res.string.mqtt_entity_cover, Topic.COVER) {},
            entity("hero", "image", Res.string.mqtt_entity_hero, Topic.HERO) {},
        )
        return buildJsonObject {
            putJsonObject("dev") {
                put("ids", buildJsonArray { add(JsonPrimitive("gamelodge_$id")) })
                put("name", cfg.deviceName)
                put("mf", "GameLodge")
                put("mdl", model)
                put("sw", version)
            }
            putJsonObject("o") {
                put("name", "GameLodge")
                put("sw", version)
            }
            putJsonObject("cmps") { components.forEach { (key, value) -> put(key, value) } }
        }
    }

    /** Id of this install (random, made once, not part of backups). */
    private fun deviceId(): String = c.secrets.get(SecretStore.Keys.MQTT_DEVICE_ID)
        ?: UUID.randomUUID().toString().replace("-", "").take(12).also { c.secrets.put(SecretStore.Keys.MQTT_DEVICE_ID, it) }

    private companion object {
        const val KEEP_ALIVE_SECONDS = 60
        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val HA_STATUS_TOPIC = "homeassistant/status"
        const val OFFLINE = "offline"
        const val COVER_SIZE = 512
        const val HERO_SIZE = 960
        const val MAX_DOWNLOAD = 20L * 1024 * 1024
    }
}
