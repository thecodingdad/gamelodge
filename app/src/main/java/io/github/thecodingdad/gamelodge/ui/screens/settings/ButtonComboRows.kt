package io.github.thecodingdad.gamelodge.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.input.ButtonCombos
import io.github.thecodingdad.gamelodge.input.ComboAccessibilityService
import io.github.thecodingdad.gamelodge.input.ComboAction
import io.github.thecodingdad.gamelodge.ui.components.ButtonRow
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import io.github.thecodingdad.gamelodge.resources.*

/** Rows for the button combinations plus the optional system-wide service. */
@Composable
fun ButtonComboRows(settings: LauncherSettings, update: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val serviceOn = remember(tick) { ComboAccessibilityService.isEnabled(context) }
    val restricted = remember(tick) { ComboAccessibilityService.isRestricted(context) }

    ComboActionRows(settings, update)
    SwitchRow(
        stringResource(Res.string.combo_forward), settings.comboForward, { on -> update { it.copy(comboForward = on) } },
        subtitle = stringResource(Res.string.combo_forward_sub),
    )
    // Only the general list could be opened: say what to look for there.
    var generalList by remember { mutableStateOf(false) }
    NavRow(
        stringResource(Res.string.combo_service), {
            if (!ComboAccessibilityService.openSettings(context)) generalList = true
        },
        subtitle = stringResource(Res.string.combo_service_sub),
        trailing = stringResource(if (serviceOn) Res.string.combo_service_on else Res.string.off),
    )
    if (!serviceOn) {
        // Without the service the combinations only work in GameLodge and its stream.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.combo_service_hint), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            if (generalList) Text(stringResource(Res.string.combo_service_where), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            if (restricted) Text(stringResource(Res.string.combo_service_restricted), style = MaterialTheme.typography.bodySmall, color = LauncherTheme.colors.onSurfaceMuted)
            io.github.thecodingdad.gamelodge.ui.components.ButtonRow {
                LauncherButton(stringResource(Res.string.combo_service_enable), onClick = {
                    if (!ComboAccessibilityService.openSettings(context)) generalList = true
                }, icon = Icons.Filled.Accessibility)
                if (restricted) LauncherButton(stringResource(Res.string.combo_service_app_info), onClick = {
                    ComboAccessibilityService.openAppInfo(context)
                }, icon = Icons.Filled.Info)
            }
        }
    }
}
