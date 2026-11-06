package io.github.thecodingdad.gamelodge.util

import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * Compose resources pick their language from the process default locale, which Android resets
 * to the device's language on some configuration changes (a display density change, for one) –
 * the launcher then showed German texts with English chosen. The activity's configuration always
 * carries the launcher's own language (per-app language), so the default follows it before
 * [content] composes, and the content is composed anew when that language changes.
 */
@Composable
fun ActivityLocale(content: @Composable () -> Unit) {
    val locales = LocalConfiguration.current.locales
    if (!locales.isEmpty && LocaleList.getDefault() != locales) {
        LocaleList.setDefault(locales)
        Locale.setDefault(locales[0])
    }
    key(locales.toLanguageTags()) { content() }
}
