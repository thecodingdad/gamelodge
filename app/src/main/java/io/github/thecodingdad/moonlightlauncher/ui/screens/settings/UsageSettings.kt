package io.github.thecodingdad.moonlightlauncher.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
import io.github.thecodingdad.moonlightlauncher.resources.*

/** Play time source (Android usage data) and the way to the statistics. */
@Composable
fun UsageSettings(openStats: () -> Unit) {
    val context = LocalContext.current
    val usage = context.container.usage
    // Usage access is granted outside the app: re-check on every resume.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val granted = remember(tick) { usage.granted }
    SettingsPage(stringResource(Res.string.settings_usage)) {
        Text(stringResource(Res.string.usage_intro), style = MaterialTheme.typography.bodyMedium, color = LauncherTheme.colors.onSurfaceMuted)
        NavRow(
            stringResource(Res.string.usage_access),
            {
                val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                    .onFailure { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            },
            subtitle = stringResource(if (granted) Res.string.usage_access_on else Res.string.usage_access_off),
            icon = Icons.Filled.QueryStats,
            initialFocus = true,
        )
        NavRow(stringResource(Res.string.stats_title), openStats, stringResource(Res.string.usage_stats_sub), Icons.Filled.BarChart)
    }
}
