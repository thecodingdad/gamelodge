package io.github.thecodingdad.gamelodge.util

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The launcher's own language ("system" or a tag like "en", "de", "es", "fr", "it", "pt-BR"). Android 13+ keeps it per app (also shown in
 * the system's app language setting); older versions get it applied to each activity's context.
 * (AppCompat's setApplicationLocales does nothing here: the activities are no AppCompat ones.)
 */
object AppLanguage {
    // Read before the settings store is available (attachBaseContext), so kept here as well.
    const val PREFS = "app_language"
    const val KEY = "tag"

    fun apply(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val wanted = if (tag == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            if (manager.applicationLocales != wanted) manager.applicationLocales = wanted // recreates the activities
        }
    }

    /**
     * Android 13+: the per-app language Android holds, as one of the launcher's choices
     * ("system" when none is set); null before Android 13, where only the launcher keeps it.
     */
    fun systemTag(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        if (locales.isEmpty) return "system"
        val locale = locales[0]
        val choices = io.github.thecodingdad.gamelodge.ui.screens.settings.LanguageTags
        return choices.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
            ?: choices.firstOrNull { it == locale.language }
            ?: locale.toLanguageTag()
    }

    /** Whether [tag] is what the activities already get. */
    fun isApplied(context: Context, tag: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return stored == tag && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || systemTag(context) == tag)
    }

    /** Before Android 13: [base] with the chosen language (for attachBaseContext). */
    fun wrap(base: Context, tag: String): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || tag == "system") return base
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
