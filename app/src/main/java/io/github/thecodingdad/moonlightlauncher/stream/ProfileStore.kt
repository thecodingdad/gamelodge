package io.github.thecodingdad.moonlightlauncher.stream

import android.content.Context
import androidx.preference.PreferenceManager
import com.limelight.profiles.ProfilesManager
import com.limelight.profiles.SettingsProfile
import java.util.UUID

/**
 * Reads and writes stream options of one profile. `uuid == null` edits the base settings
 * (Artemis' default shared preferences); otherwise the profile's option map.
 */
class ProfileStore(private val context: Context, val uuid: UUID?) {
    private val manager get() = ProfilesManager.getInstance()
    private val base get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val profile: SettingsProfile? get() = uuid?.let { id -> manager.profiles.firstOrNull { it.uuid == id } }

    private fun raw(key: String): Any? = if (uuid == null) base.all[key] else profile?.options?.get(key)

    fun getBoolean(key: String, default: Boolean): Boolean = raw(key) as? Boolean ?: default
    fun getString(key: String, default: String?): String? = raw(key)?.toString() ?: default
    fun getInt(key: String, default: Int): Int = (raw(key) as? Number)?.toInt() ?: (raw(key) as? String)?.toIntOrNull() ?: default

    fun set(key: String, value: Any) {
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
