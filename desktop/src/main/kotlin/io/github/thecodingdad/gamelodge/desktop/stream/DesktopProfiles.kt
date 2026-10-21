package io.github.thecodingdad.gamelodge.desktop.stream

import io.github.thecodingdad.gamelodge.desktop.writeAtomically
import androidx.compose.runtime.Composable
import io.github.thecodingdad.gamelodge.data.backup.ProfileBackup
import io.github.thecodingdad.gamelodge.data.backup.StreamSettingsBackup
import io.github.thecodingdad.gamelodge.data.backup.toAny
import io.github.thecodingdad.gamelodge.desktop.restrictToOwner
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.stream.ProfileEditing
import io.github.thecodingdad.gamelodge.stream.ProfileValues
import io.github.thecodingdad.gamelodge.stream.StreamOption
import io.github.thecodingdad.gamelodge.stream.StreamOptionCategory
import io.github.thecodingdad.gamelodge.stream.StreamProfile
import io.github.thecodingdad.gamelodge.stream.StreamProfiles
import io.github.thecodingdad.gamelodge.stream.withNativeDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
data class DesktopProfile(val uuid: String, val name: String, val values: Map<String, String> = emptyMap())

@Serializable
private data class ProfilesFile(
    val base: Map<String, String> = emptyMap(),
    val profiles: List<DesktopProfile> = emptyList(),
    val active: String? = null,
)

/**
 * Stream profiles of the desktop, laid out like Android's (Artemis' option keys, texts and
 * defaults), limited to what Moonlight-Qt can do. Every stream gets the active profile's values
 * as Moonlight-Qt command line options. New profiles start as a copy of the base settings.
 */
class DesktopProfiles(dataDir: File) : StreamProfiles, ProfileEditing, StreamSettingsBackup {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(dataDir, "profiles.json")
    private val state = MutableStateFlow(load())

    // ---- StreamProfiles ----

    override fun all(defaultName: String) = listOf(StreamProfile(null, defaultName)) + state.value.profiles.map { StreamProfile(UUID.fromString(it.uuid), it.name) }

    override fun active(defaultName: String): StreamProfile {
        val p = activeProfile()
        return if (p == null) StreamProfile(null, defaultName) else StreamProfile(UUID.fromString(p.uuid), p.name)
    }

    override fun setActive(uuid: UUID?) = save(state.value.copy(active = uuid?.toString()))

    override fun changes(): Flow<Unit> = state.map { }

    override fun summary(uuid: UUID?): String {
        val v = valuesOf(uuid)
        val res = v.getString(RESOLUTION, DEFAULT_RESOLUTION)!!
        val fps = v.getString(FPS, DEFAULT_FPS)!!
        val bitrate = v.getInt(BITRATE, defaultBitrate(res, fps))
        return "${res.substringAfter('x')}p · $fps fps · ${"%.1f".format(bitrate / 1000f).removeSuffix(".0")} Mbit"
    }

    /** Moonlight-Qt options of the active profile. */
    fun activeOptions(): List<String> = options(valuesOf(activeProfile()?.uuid?.let(UUID::fromString)))

    // ---- ProfileEditing ----

    @Composable
    override fun rememberSchema(): List<StreamOptionCategory> {
        val nativeLabel = stringResource(Res.string.sopt_resolution_prefix_native)
        val fpsSuffix = stringResource(Res.string.sopt_fps_suffix_fps)
        // The primary screen's mode (physical pixels), as Artemis offers "Native".
        val mode = androidx.compose.runtime.remember {
            runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode }.getOrNull()
        }
        fun toggle(key: String, title: String, summary: String?) = StreamOption.Toggle(key, title, summary, toggleDefaults.getValue(key))
        return listOf(
            StreamOptionCategory(
                "category_video_settings", stringResource(Res.string.sopt_category_video_settings),
                listOf(
                    StreamOption.Choice(
                        RESOLUTION, stringResource(Res.string.sopt_title_resolution_list), stringResource(Res.string.sopt_summary_resolution_list),
                        listOf(
                            stringResource(Res.string.sopt_resolution_360p), stringResource(Res.string.sopt_resolution_480p), stringResource(Res.string.sopt_resolution_720p),
                            stringResource(Res.string.sopt_resolution_1080p), stringResource(Res.string.sopt_resolution_1440p), stringResource(Res.string.sopt_resolution_4k),
                        ),
                        listOf("640x360", "854x480", "1280x720", "1920x1080", "2560x1440", "3840x2160"), DEFAULT_RESOLUTION,
                    ),
                    StreamOption.Choice(
                        FPS, stringResource(Res.string.sopt_title_fps_list), stringResource(Res.string.sopt_summary_fps_list),
                        listOf(stringResource(Res.string.sopt_fps_30), stringResource(Res.string.sopt_fps_60), stringResource(Res.string.sopt_fps_90), stringResource(Res.string.sopt_fps_120)),
                        listOf("30", "60", "90", "120"), DEFAULT_FPS,
                    ),
                    StreamOption.Slider(
                        BITRATE, stringResource(Res.string.sopt_title_seekbar_bitrate), stringResource(Res.string.sopt_summary_seekbar_bitrate),
                        min = 500, max = 300_000, step = 500, divisor = 1000, suffix = stringResource(Res.string.sopt_suffix_seekbar_bitrate_mbps), default = null,
                    ),
                    StreamOption.Choice(
                        FRAME_PACING, stringResource(Res.string.sopt_title_frame_pacing), stringResource(Res.string.sopt_summary_frame_pacing),
                        listOf(stringResource(Res.string.sopt_pacing_latency), stringResource(Res.string.sopt_pacing_balanced), stringResource(Res.string.sopt_pacing_smoothness)),
                        listOf("latency", "balanced", "smoothness"), "latency",
                    ),
                    toggle(HDR, stringResource(Res.string.sopt_title_enable_hdr), stringResource(Res.string.sopt_summary_enable_hdr)),
                    StreamOption.Choice(
                        DISPLAY_MODE, stringResource(Res.string.dprofile_display_mode), stringResource(Res.string.dprofile_display_mode_sub),
                        listOf(stringResource(Res.string.dprofile_fullscreen), stringResource(Res.string.dprofile_borderless), stringResource(Res.string.dprofile_windowed)),
                        listOf("fullscreen", "borderless", "windowed"), "fullscreen",
                    ),
                ),
            ),
            StreamOptionCategory(
                "category_audio_settings", stringResource(Res.string.sopt_category_audio_settings),
                listOf(
                    StreamOption.Choice(
                        AUDIO, stringResource(Res.string.sopt_title_audio_config_list), stringResource(Res.string.sopt_summary_audio_config_list),
                        listOf(stringResource(Res.string.sopt_audioconf_stereo), stringResource(Res.string.sopt_audioconf_51surround), stringResource(Res.string.sopt_audioconf_71surround)),
                        listOf("2", "51", "71"), "2",
                    ),
                ),
            ),
            StreamOptionCategory(
                "category_gamepad_settings", stringResource(Res.string.sopt_category_gamepad_settings),
                listOf(
                    toggle(MULTI_CONTROLLER, stringResource(Res.string.sopt_title_checkbox_multi_controller), stringResource(Res.string.sopt_summary_checkbox_multi_controller)),
                    toggle(FLIP_FACE_BUTTONS, stringResource(Res.string.sopt_title_checkbox_flip_face_buttons), stringResource(Res.string.sopt_summary_checkbox_flip_face_buttons)),
                ),
            ),
            StreamOptionCategory(
                "category_input_settings", stringResource(Res.string.sopt_category_input_settings),
                listOf(
                    toggle(ABSOLUTE_MOUSE, stringResource(Res.string.sopt_title_checkbox_absolute_mouse_mode), stringResource(Res.string.sopt_summary_checkbox_absolute_mouse_mode)),
                    StreamOption.Choice(
                        SYSTEM_KEYS, stringResource(Res.string.dprofile_system_keys), stringResource(Res.string.dprofile_system_keys_sub),
                        listOf(stringResource(Res.string.dprofile_never), stringResource(Res.string.dprofile_fullscreen), stringResource(Res.string.dprofile_always)),
                        listOf("never", "fullscreen", "always"), "fullscreen",
                    ),
                ),
            ),
            StreamOptionCategory(
                "category_host_settings", stringResource(Res.string.sopt_category_host_settings),
                listOf(
                    toggle(GAME_OPTIMIZATION, stringResource(Res.string.sopt_title_checkbox_enable_sops), stringResource(Res.string.sopt_summary_checkbox_enable_sops)),
                    toggle(HOST_AUDIO, stringResource(Res.string.sopt_title_checkbox_host_audio), stringResource(Res.string.sopt_summary_checkbox_host_audio)),
                    toggle(QUIT_AFTER, stringResource(Res.string.dprofile_quit_after), stringResource(Res.string.dprofile_quit_after_sub)),
                ),
            ),
            StreamOptionCategory(
                "category_perf_monitor_settings", stringResource(Res.string.sopt_category_perf_monitor_settings),
                listOf(toggle(PERF_OVERLAY, stringResource(Res.string.sopt_title_enable_perf_overlay), stringResource(Res.string.sopt_summary_enable_perf_overlay))),
            ),
            StreamOptionCategory(
                "category_advanced_settings", stringResource(Res.string.sopt_category_advanced_settings),
                listOf(
                    StreamOption.Choice(
                        VIDEO_FORMAT, stringResource(Res.string.sopt_title_video_format), stringResource(Res.string.sopt_summary_video_format),
                        listOf(
                            stringResource(Res.string.sopt_videoformat_auto), stringResource(Res.string.sopt_videoformat_av1always),
                            stringResource(Res.string.sopt_videoformat_hevcalways), stringResource(Res.string.sopt_videoformat_h264always),
                        ),
                        listOf("auto", "forceav1", "forceh265", "neverh265"), "auto",
                    ),
                    StreamOption.Choice(
                        DECODER, stringResource(Res.string.dprofile_decoder), stringResource(Res.string.dprofile_decoder_sub),
                        listOf(stringResource(Res.string.dprofile_auto), stringResource(Res.string.dprofile_hardware), stringResource(Res.string.dprofile_software)),
                        listOf("auto", "hardware", "software"), "auto",
                    ),
                    toggle(YUV444, stringResource(Res.string.dprofile_yuv444), stringResource(Res.string.dprofile_yuv444_sub)),
                ),
            ),
        ).withNativeDisplay(mode?.width ?: 0, mode?.height ?: 0, mode?.refreshRate?.toFloat() ?: 0f, nativeLabel, fpsSuffix)
    }

    override val essentialKeys = listOf(RESOLUTION, FPS, BITRATE, VIDEO_FORMAT, HDR, FRAME_PACING, AUDIO, MULTI_CONTROLLER, PERF_OVERLAY)

    override fun values(uuid: UUID?): ProfileValues = valuesOf(uuid)

    override fun create(name: String): UUID {
        val uuid = UUID.randomUUID()
        // Like Artemis: a new profile starts from the base settings.
        save(state.value.copy(profiles = state.value.profiles + DesktopProfile(uuid.toString(), name, state.value.base)))
        return uuid
    }

    override fun rename(uuid: UUID, name: String) = change(uuid) { it.copy(name = name) }

    override fun delete(uuid: UUID) = save(
        state.value.copy(
            profiles = state.value.profiles.filter { it.uuid != uuid.toString() },
            active = state.value.active.takeIf { it != uuid.toString() },
        ),
    )

    override fun dynamicDefault(key: String, values: ProfileValues): Int? =
        if (key == BITRATE) defaultBitrate(values.getString(RESOLUTION, DEFAULT_RESOLUTION)!!, values.getString(FPS, DEFAULT_FPS)!!) else null

    // ---- StreamSettingsBackup ----

    override fun baseSettings(): Map<String, JsonElement> = state.value.base.mapValues { JsonPrimitive(it.value) }

    override fun profiles(): List<ProfileBackup> =
        state.value.profiles.map { p -> ProfileBackup(p.name, p.values.mapValues { JsonPrimitive(it.value) }, p.uuid == state.value.active) }

    /** Values are kept as text, like the editor stores them (backups from Android carry typed values). */
    override fun restore(base: Map<String, JsonElement>, profiles: List<ProfileBackup>) {
        fun Map<String, JsonElement>.texts() = mapNotNull { (k, v) -> v.toAny()?.let { k to it.toString() } }.toMap()
        val current = state.value
        val names = current.profiles.map { it.name }.toSet()
        val added = profiles.filter { it.name !in names }.map { DesktopProfile(UUID.randomUUID().toString(), it.name, it.options.texts()) }
        val active = profiles.firstOrNull { it.active }?.let { a -> added.firstOrNull { it.name == a.name }?.uuid } ?: current.active
        save(current.copy(base = current.base + base.texts(), profiles = current.profiles + added, active = active))
    }

    // ---- storage ----

    private fun activeProfile() = state.value.active?.let { a -> state.value.profiles.firstOrNull { it.uuid == a } }

    private fun valuesOf(uuid: UUID?): ProfileValues = object : ProfileValues {
        private fun raw(key: String): String? =
            if (uuid == null) state.value.base[key] else state.value.profiles.firstOrNull { it.uuid == uuid.toString() }?.values?.get(key)

        override fun getBoolean(key: String, default: Boolean) = raw(key)?.toBooleanStrictOrNull() ?: default
        override fun getString(key: String, default: String?) = raw(key) ?: default
        override fun getInt(key: String, default: Int) = raw(key)?.toIntOrNull() ?: default
        override fun set(key: String, value: Any) {
            if (uuid == null) save(state.value.copy(base = state.value.base + (key to value.toString())))
            else change(uuid) { it.copy(values = it.values + (key to value.toString())) }
        }
    }

    private fun change(uuid: UUID, block: (DesktopProfile) -> DesktopProfile) =
        save(state.value.copy(profiles = state.value.profiles.map { if (it.uuid == uuid.toString()) block(it) else it }))

    private fun load(): ProfilesFile = runCatching { json.decodeFromString<ProfilesFile>(file.readText()) }.getOrDefault(ProfilesFile())

    private fun save(next: ProfilesFile) {
        state.value = next
        runCatching {
            file.writeAtomically(json.encodeToString(next), ownerOnly = true)
        }
    }

    companion object {
        const val RESOLUTION = "list_resolution"
        const val FPS = "list_fps"
        const val BITRATE = "seekbar_bitrate_kbps"
        const val FRAME_PACING = "frame_pacing"
        const val HDR = "checkbox_enable_hdr"
        const val DISPLAY_MODE = "desktop_display_mode"
        const val AUDIO = "list_audio_config"
        const val MULTI_CONTROLLER = "checkbox_multi_controller"
        const val FLIP_FACE_BUTTONS = "checkbox_flip_face_buttons"
        const val ABSOLUTE_MOUSE = "checkbox_absolute_mouse_mode"
        const val SYSTEM_KEYS = "desktop_capture_system_keys"
        const val GAME_OPTIMIZATION = "checkbox_enable_sops"
        const val HOST_AUDIO = "checkbox_host_audio"
        const val QUIT_AFTER = "desktop_quit_after"
        const val PERF_OVERLAY = "checkbox_enable_perf_overlay"
        const val VIDEO_FORMAT = "video_format"
        const val DECODER = "desktop_video_decoder"
        const val YUV444 = "desktop_yuv444"
        const val DEFAULT_RESOLUTION = "1280x720"
        const val DEFAULT_FPS = "60"

        /** Artemis' defaults (and Moonlight-Qt's for the PC-only options). */
        private val toggleDefaults = mapOf(
            HDR to false, MULTI_CONTROLLER to true, FLIP_FACE_BUTTONS to false, ABSOLUTE_MOUSE to false,
            GAME_OPTIMIZATION to true, HOST_AUDIO to false, QUIT_AFTER to false, PERF_OVERLAY to false, YUV444 to false,
        )

        /** Moonlight-Qt's (and Artemis') default bitrate in kbps for a resolution and frame rate. */
        fun defaultBitrate(resolution: String, fps: String): Int {
            val w = resolution.substringBefore('x').toIntOrNull() ?: 1280
            val h = resolution.substringAfter('x').toIntOrNull() ?: 720
            val f = fps.toFloatOrNull()?.roundToInt() ?: 60
            val frameRateFactor = (if (f <= 60) f.toDouble() else sqrt(f / 60.0) * 60.0) / 30.0
            val pixels = intArrayOf(640 * 360, 854 * 480, 1280 * 720, 1920 * 1080, 2560 * 1440, 3840 * 2160)
            val factors = intArrayOf(1, 2, 5, 10, 20, 40)
            val p = w * h
            val i = pixels.indexOfFirst { p <= it }
            val resolutionFactor = when {
                i == 0 -> factors[0].toFloat()
                i < 0 -> factors.last().toFloat()
                p == pixels[i] -> factors[i].toFloat()
                else -> (p - pixels[i - 1]).toFloat() / (pixels[i] - pixels[i - 1]) * (factors[i] - factors[i - 1]) + factors[i - 1]
            }
            return (resolutionFactor * frameRateFactor).roundToInt() * 1000
        }

        /** Moonlight-Qt command line options for a profile's values (defaults where unset). */
        fun options(v: ProfileValues): List<String> {
            fun flag(name: String, key: String) = if (v.getBoolean(key, toggleDefaults.getValue(key))) "--$name" else "--no-$name"
            val resolution = v.getString(RESOLUTION, DEFAULT_RESOLUTION)!!
            val fps = v.getString(FPS, DEFAULT_FPS)!!
            val pacing = v.getString(FRAME_PACING, "latency")
            return buildList {
                add("--resolution"); add(resolution)
                add("--fps"); add(fps)
                add("--bitrate"); add(v.getInt(BITRATE, defaultBitrate(resolution, fps)).toString())
                // Lowest latency: neither V-Sync nor frame pacing; balanced: V-Sync; smoothest: both.
                add(if (pacing == "latency") "--no-vsync" else "--vsync")
                add(if (pacing == "smoothness") "--frame-pacing" else "--no-frame-pacing")
                add(flag("hdr", HDR))
                add("--display-mode"); add(v.getString(DISPLAY_MODE, "fullscreen")!!)
                add("--audio-config"); add(when (v.getString(AUDIO, "2")) { "51" -> "5.1-surround"; "71" -> "7.1-surround"; else -> "stereo" })
                add(flag("multi-controller", MULTI_CONTROLLER))
                add(flag("swap-gamepad-buttons", FLIP_FACE_BUTTONS))
                add(flag("absolute-mouse", ABSOLUTE_MOUSE))
                add("--capture-system-keys"); add(v.getString(SYSTEM_KEYS, "fullscreen")!!)
                add(flag("game-optimization", GAME_OPTIMIZATION))
                add(flag("audio-on-host", HOST_AUDIO))
                add(flag("quit-after", QUIT_AFTER))
                add(flag("performance-overlay", PERF_OVERLAY))
                add("--video-codec"); add(when (v.getString(VIDEO_FORMAT, "auto")) { "forceav1" -> "AV1"; "forceh265" -> "HEVC"; "neverh265" -> "H.264"; else -> "auto" })
                add("--video-decoder"); add(v.getString(DECODER, "auto")!!)
                add(flag("yuv444", YUV444))
            }
        }
    }
}
