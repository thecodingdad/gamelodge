package io.github.thecodingdad.gamelodge.stream

import android.content.Context
import androidx.preference.PreferenceManager
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import java.util.UUID

/**
 * Reads and writes stream options of one profile. `uuid == null` edits the base settings
 * (Artemis' default shared preferences); otherwise the profile's option map.
 */
class ProfileStore(private val context: Context, val uuid: UUID?) : ProfileValues {
    private val manager get() = ProfilesManager.getInstance()
    private val base get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val profile: SettingsProfile? get() = uuid?.let { id -> manager.profiles.firstOrNull { it.uuid == id } }

    private fun raw(key: String): Any? = if (uuid == null) base.all[key] else profile?.options?.get(key)

    override fun getBoolean(key: String, default: Boolean): Boolean = raw(key) as? Boolean ?: default
    override fun getString(key: String, default: String?): String? = raw(key)?.toString() ?: default
    override fun getInt(key: String, default: Int): Int = (raw(key) as? Number)?.toInt() ?: (raw(key) as? String)?.toIntOrNull() ?: default

    override fun set(key: String, value: Any) {
        if (uuid == null) {
            base.edit().apply {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> putString(key, value.toString())
                }
            }.apply()
            return
        }
        val p = profile ?: return
        val options = HashMap(p.options ?: emptyMap())
        options[key] = value
        p.options = options
        p.modifiedUtc = System.currentTimeMillis()
        manager.update(p)
    }

    companion object {
        /** New profile starting from a snapshot of the base settings (like Artemis does). */
        fun create(context: Context, name: String): UUID {
            val now = System.currentTimeMillis()
            val snapshot = HashMap<String, Any>(PreferenceManager.getDefaultSharedPreferences(context).all.filterValues { it != null }.mapValues { it.value!! })
            val profile = SettingsProfile(UUID.randomUUID(), name, now, now, snapshot)
            ProfilesManager.getInstance().add(profile)
            return profile.uuid
        }

        /**
         * Options that now hold for every profile ([keys], the controller options): the active
         * profile's values become the base settings, the profiles drop their own. Once per key set.
         */
        fun moveToBase(context: Context, keys: Collection<String>) {
            val prefs = context.getSharedPreferences("gamelodge_migrations", Context.MODE_PRIVATE)
            if (prefs.getBoolean("profile_gamepad_to_base", false)) return
            val manager = ProfilesManager.getInstance()
            val base = ProfileStore(context, null)
            manager.active?.options?.forEach { (key, value) -> if (key in keys && value != null) base.set(key, value) }
            manager.profiles.forEach { p ->
                val options = p.options ?: return@forEach
                if (options.keys.none { it in keys }) return@forEach
                p.options = HashMap(options).apply { keys.forEach { remove(it) } }
                p.modifiedUtc = System.currentTimeMillis()
                manager.update(p)
            }
            prefs.edit().putBoolean("profile_gamepad_to_base", true).apply()
        }

        /** Back to 2D wherever Artemis' AI 3D render mode was chosen (GameLodge leaves it out). */
        fun resetRenderMode(context: Context) {
            val key = StreamSettingsSchema.RENDER_MODE_KEY
            if ((ProfileStore(context, null).getString(key, "0") ?: "0") != "0") ProfileStore(context, null).set(key, "0")
            ProfilesManager.getInstance().profiles
                .filter { (it.options?.get(key)?.toString() ?: "0") != "0" }
                .forEach { ProfileStore(context, it.uuid).set(key, "0") }
        }

        fun rename(uuid: UUID, name: String) {
            val manager = ProfilesManager.getInstance()
            manager.profiles.firstOrNull { it.uuid == uuid }?.let {
                it.name = name
                it.modifiedUtc = System.currentTimeMillis()
                manager.update(it)
            }
        }

        fun delete(uuid: UUID) {
            val manager = ProfilesManager.getInstance()
            if (manager.active?.uuid == uuid) manager.setActive(null)
            manager.delete(uuid)
        }
    }
}

/** Artemis' profiles for the shared profile screens. */
class AndroidProfileEditing(private val context: Context) : ProfileEditing {
    @androidx.compose.runtime.Composable
    override fun rememberSchema(): List<StreamOptionCategory> = androidx.compose.runtime.remember { StreamSettingsSchema.profileSchema(context) }

    override val essentialKeys get() = StreamSettingsSchema.essentialKeys

    override fun values(uuid: UUID?): ProfileValues = ProfileStore(context, uuid)

    override fun create(name: String) = ProfileStore.create(context, name)

    override fun rename(uuid: UUID, name: String) = ProfileStore.rename(uuid, name)

    override fun delete(uuid: UUID) = ProfileStore.delete(uuid)

    // Bitrate has no static default: Artemis derives it from the profile's resolution and FPS.
    override fun dynamicDefault(key: String, values: ProfileValues): Int? = if (key == "seekbar_bitrate_kbps") {
        com.limelight.preferences.PreferenceConfiguration.getDefaultBitrate(
            values.getString("list_resolution", "1280x720") ?: "1280x720",
            values.getString("list_fps", "60") ?: "60",
        )
    } else null
}
