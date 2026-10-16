package io.github.thecodingdad.moonlightlauncher.desktop.stream

import io.github.thecodingdad.moonlightlauncher.desktop.restrictToOwner
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfile
import io.github.thecodingdad.moonlightlauncher.stream.StreamProfiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One setting of a profile: a Moonlight-Qt command line option; no value = Moonlight-Qt's own setting. */
data class ProfileOption(val key: String, val values: List<String>, val toggle: Boolean = false)

/** The profile settings, in the editor's order. Values are Moonlight-Qt's own option values. */
object ProfileOptions {
    val RESOLUTION = ProfileOption("resolution", listOf("1280x720", "1920x1080", "2560x1440", "3840x2160"))
    val FPS = ProfileOption("fps", listOf("30", "60", "90", "120", "144"))
    val BITRATE = ProfileOption("bitrate", listOf("5000", "10000", "20000", "30000", "50000", "80000", "100000", "150000"))
    val DISPLAY_MODE = ProfileOption("display-mode", listOf("fullscreen", "borderless", "windowed"))
    val VSYNC = ProfileOption("vsync", emptyList(), toggle = true)
    val FRAME_PACING = ProfileOption("frame-pacing", emptyList(), toggle = true)
    val HDR = ProfileOption("hdr", emptyList(), toggle = true)
    val YUV444 = ProfileOption("yuv444", emptyList(), toggle = true)
    val CODEC = ProfileOption("video-codec", listOf("auto", "H.264", "HEVC", "AV1"))
    val DECODER = ProfileOption("video-decoder", listOf("auto", "hardware", "software"))
    val AUDIO = ProfileOption("audio-config", listOf("stereo", "5.1-surround", "7.1-surround"))
    val AUDIO_ON_HOST = ProfileOption("audio-on-host", emptyList(), toggle = true)
    val MULTI_CONTROLLER = ProfileOption("multi-controller", emptyList(), toggle = true)
    val GAME_OPTIMIZATION = ProfileOption("game-optimization", emptyList(), toggle = true)
    val QUIT_AFTER = ProfileOption("quit-after", emptyList(), toggle = true)
    val OVERLAY = ProfileOption("performance-overlay", emptyList(), toggle = true)
    val SYSTEM_KEYS = ProfileOption("capture-system-keys", listOf("never", "fullscreen", "always"))

    val all = listOf(
        RESOLUTION, FPS, BITRATE, DISPLAY_MODE, VSYNC, FRAME_PACING, HDR, YUV444, CODEC, DECODER,
        AUDIO, AUDIO_ON_HOST, MULTI_CONTROLLER, GAME_OPTIMIZATION, QUIT_AFTER, OVERLAY, SYSTEM_KEYS,
    )
}

@Serializable
data class DesktopProfile(val uuid: String, val name: String, val values: Map<String, String> = emptyMap())

@Serializable
private data class ProfilesFile(val profiles: List<DesktopProfile> = emptyList(), val active: String? = null)

/**
 * Stream profiles of the desktop: named sets of Moonlight-Qt command line options over the base
 * (Moonlight-Qt's own settings). The active one is passed with every stream.
 */
class DesktopProfiles(dataDir: File) : StreamProfiles {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(dataDir, "profiles.json")
    private val state = MutableStateFlow(load())

    val profiles get() = state.value.profiles

    fun profile(uuid: UUID?): DesktopProfile? = uuid?.let { u -> state.value.profiles.firstOrNull { it.uuid == u.toString() } }

    override fun all(defaultName: String) = listOf(StreamProfile(null, defaultName)) + state.value.profiles.map { StreamProfile(UUID.fromString(it.uuid), it.name) }

    override fun active(defaultName: String): StreamProfile {
        val p = state.value.active?.let { a -> state.value.profiles.firstOrNull { it.uuid == a } }
        return if (p == null) StreamProfile(null, defaultName) else StreamProfile(UUID.fromString(p.uuid), p.name)
    }

    override fun setActive(uuid: UUID?) = save(state.value.copy(active = uuid?.toString()))

    override fun changes(): Flow<Unit> = state.map { }

    override fun summary(uuid: UUID?): String {
        val v = profile(uuid)?.values ?: return ""
        return listOfNotNull(
            v[ProfileOptions.RESOLUTION.key]?.substringAfter('x')?.let { "${it}p" },
            v[ProfileOptions.FPS.key]?.let { "$it fps" },
            v[ProfileOptions.BITRATE.key]?.toIntOrNull()?.let { "${it / 1000} Mbit" },
        ).joinToString(" · ")
    }

    fun create(name: String): UUID {
        val uuid = UUID.randomUUID()
        save(state.value.copy(profiles = state.value.profiles + DesktopProfile(uuid.toString(), name)))
        return uuid
    }

    fun rename(uuid: UUID, name: String) = change(uuid) { it.copy(name = name) }

    fun delete(uuid: UUID) = save(
        state.value.copy(
            profiles = state.value.profiles.filter { it.uuid != uuid.toString() },
            active = state.value.active.takeIf { it != uuid.toString() },
        ),
    )

    /** [value] null: back to Moonlight-Qt's own setting. */
    fun set(uuid: UUID, key: String, value: String?) = change(uuid) { p ->
        p.copy(values = if (value == null) p.values - key else p.values + (key to value))
    }

    /** Command line options of the active profile. */
    fun activeOptions(): List<String> = options(state.value.active?.let { a -> state.value.profiles.firstOrNull { it.uuid == a } }?.values.orEmpty())

    private fun change(uuid: UUID, block: (DesktopProfile) -> DesktopProfile) =
        save(state.value.copy(profiles = state.value.profiles.map { if (it.uuid == uuid.toString()) block(it) else it }))

    private fun load(): ProfilesFile = runCatching { json.decodeFromString<ProfilesFile>(file.readText()) }.getOrDefault(ProfilesFile())

    private fun save(next: ProfilesFile) {
        state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(next))
            restrictToOwner(file)
        }
    }

    companion object {
        /** Moonlight-Qt options for [values] (toggles: "--x" / "--no-x"); unknown or invalid values are dropped. */
        fun options(values: Map<String, String>): List<String> = ProfileOptions.all.flatMap { o ->
            val v = values[o.key] ?: return@flatMap emptyList()
            when {
                o.toggle -> listOf(if (v == "true") "--${o.key}" else "--no-${o.key}")
                v in o.values -> listOf("--${o.key}", v)
                else -> emptyList()
            }
        }
    }
}
