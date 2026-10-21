package io.github.thecodingdad.gamelodge.ui.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.LocalContainer
import io.github.thecodingdad.gamelodge.data.backup.BackupEntry
import io.github.thecodingdad.gamelodge.data.backup.WrongPasswordException
import io.github.thecodingdad.gamelodge.data.db.ItemKind
import io.github.thecodingdad.gamelodge.data.settings.AccentMode
import io.github.thecodingdad.gamelodge.data.settings.ButtonLayout
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.data.settings.ThemeMode
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.components.TextKind
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.input.LocalInputDispatcher
import io.github.thecodingdad.gamelodge.ui.screens.settings.AccentColorPicker
import io.github.thecodingdad.gamelodge.ui.screens.settings.AccentSwatchRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad
import io.github.thecodingdad.gamelodge.ui.screens.settings.ChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerLayoutDefaultRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerLayoutOverrideRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.PictureChoiceRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader
import io.github.thecodingdad.gamelodge.ui.screens.settings.SliderRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SwitchRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.ThemePicture
import io.github.thecodingdad.gamelodge.ui.screens.settings.layoutFor
import io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers
import io.github.thecodingdad.gamelodge.ui.screens.settings.vibrateController
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal fun stepInfo(step: WizardStep): StepInfo = when (step) {
    WizardStep.WELCOME -> StepInfo(Icons.Filled.Celebration, Res.string.wizard_welcome_title, Res.string.wizard_welcome_text)
    WizardStep.BACKUP -> StepInfo(Icons.Filled.Restore, Res.string.wizard_backup_title, Res.string.wizard_backup_text)
    WizardStep.HOST_ROLE -> StepInfo(Icons.Filled.Dns, Res.string.wizard_host_role_title, Res.string.wizard_host_role_text)
    WizardStep.CONTROLLER -> StepInfo(Icons.Filled.SportsEsports, Res.string.wizard_controller_title, Res.string.wizard_controller_text)
    WizardStep.GUIDE -> StepInfo(Icons.Filled.Gamepad, Res.string.wizard_guide_title, Res.string.wizard_guide_text)
    WizardStep.APPEARANCE -> StepInfo(Icons.Filled.Palette, Res.string.wizard_appearance_title, Res.string.wizard_appearance_text)
    WizardStep.LAUNCHER_MODE -> StepInfo(Icons.Filled.Home, Res.string.wizard_launcher_title, Res.string.wizard_launcher_text)
    WizardStep.PERMISSIONS -> StepInfo(Icons.Filled.VerifiedUser, Res.string.wizard_permissions_title, Res.string.wizard_permissions_text)
    WizardStep.STARTUP -> StepInfo(Icons.Filled.PowerSettingsNew, Res.string.wizard_startup_title, Res.string.wizard_startup_text)
    WizardStep.GAME_SOURCES -> StepInfo(Icons.Filled.Store, Res.string.wizard_sources_title, Res.string.wizard_sources_text)
    WizardStep.EMULATORS -> StepInfo(Icons.Filled.VideogameAsset, Res.string.wizard_emulators_title, Res.string.wizard_emulators_text)
    WizardStep.HOSTS -> StepInfo(Icons.Filled.Computer, Res.string.wizard_hosts_title, Res.string.wizard_hosts_text)
    WizardStep.COMPANION -> StepInfo(Icons.Filled.Link, Res.string.wizard_companion_title, Res.string.wizard_companion_text)
    WizardStep.METADATA -> StepInfo(Icons.Filled.Image, Res.string.wizard_metadata_title, Res.string.wizard_metadata_text)
    WizardStep.RESTORE -> StepInfo(Icons.Filled.SettingsBackupRestore, Res.string.wizard_restore_title, Res.string.wizard_restore_text)
    WizardStep.HOME_ASSISTANT -> StepInfo(Icons.Filled.Cottage, Res.string.wizard_ha_title, Res.string.wizard_ha_text)
    WizardStep.VPN -> StepInfo(Icons.Filled.VpnLock, Res.string.wizard_vpn_title, Res.string.wizard_vpn_text)
    WizardStep.FINISH -> StepInfo(Icons.Filled.CheckCircle, Res.string.wizard_finish_title, Res.string.wizard_finish_text)
}

private val LocalNav @Composable get() = LocalWizardNav.current!!

// ---- Welcome ----

@Composable
internal fun ColumnScope.WelcomeStep(settings: LauncherSettings) {
    val nav = LocalNav
    ChoiceRow(
        stringResource(Res.string.settings_language), listOf("system", "en", "de"), settings.language,
        label = {
            when (it) {
                "en" -> "English"
                "de" -> "Deutsch"
                else -> stringResource(Res.string.language_system)
            }
        },
        onSelect = { v ->
            nav.update { it.copy(language = v) }
            PlatformUi.current.applyLanguage(v)
        },
        initialFocus = true,
    )
    Spacer(Modifier.height(8.dp))
    Feature(Icons.Filled.VideoLibrary, Res.string.wizard_welcome_library)
    Feature(Icons.Filled.Cast, Res.string.wizard_welcome_streaming)
    Feature(Icons.Filled.Cottage, Res.string.wizard_welcome_smart_home)
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, text: org.jetbrains.compose.resources.StringResource) {
    val colors = LauncherTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    }
}

// ---- Backup ----

@Composable
internal fun ColumnScope.BackupStep(settings: LauncherSettings) {
    val nav = LocalNav
    val container = LocalContainer.current
    val storage = container.backupStorage
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val unreadable = stringResource(Res.string.wizard_backup_unreadable)

    // The backup is only checked here; it is restored after pairing (its games need the hosts).
    fun take(address: String) {
        choosing = null
        checking = true
        error = null
        scope.launch {
            val ok = runCatching { container.backup.decode(storage.read(address)) }.isSuccess
            checking = false
            if (ok) nav.chooseBackup(address) else error = unreadable
        }
    }
    val openFile = storage.rememberOpenFile(::take)
    fun listFolder(folder: String) {
        scope.launch {
            val list = runCatching { storage.list(folder) }.getOrDefault(emptyList())
            if (list.isEmpty()) openFile() else choosing = list
        }
    }
    val pickFolder = storage.rememberFolderPicker { folder ->
        nav.update { it.copy(backupFolderUri = folder) }
        listFolder(folder)
    }
    val folder = settings.backupFolderUri.ifEmpty { storage.defaultFolder.orEmpty() }

    WizardChoiceCard(
        stringResource(Res.string.wizard_backup_new), stringResource(Res.string.wizard_backup_new_sub), Icons.Filled.AutoAwesome,
        onClick = nav.next, initialFocus = true,
    )
    WizardChoiceCard(
        stringResource(Res.string.wizard_backup_restore), stringResource(Res.string.wizard_backup_restore_sub), Icons.Filled.SettingsBackupRestore,
        onClick = { if (folder.isNotEmpty()) listFolder(folder) else openFile() },
    )
    if (checking) CircularProgressIndicator(color = LauncherTheme.colors.accent)
    error?.let { WizardNote(it, LauncherTheme.colors.danger) }

    val list = choosing
    Modal(visible = list != null, onDismiss = { choosing = null }, title = stringResource(Res.string.backup_choose)) {
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
        // Newest first: the one to take in most cases gets the focus.
        list.orEmpty().take(10).forEachIndexed { i, entry ->
            LauncherButton(
                entry.createdAt?.let { format.format(java.util.Date(it)) } ?: entry.name,
                onClick = { take(entry.address) }, icon = Icons.Filled.Description, primary = i == 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        LauncherButton(stringResource(Res.string.backup_other_file), onClick = { choosing = null; openFile() }, icon = Icons.Filled.InsertDriveFile, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        LauncherButton(stringResource(Res.string.wizard_backup_other_folder), onClick = { choosing = null; pickFolder() }, icon = Icons.Filled.Folder, modifier = Modifier.fillMaxWidth())
    }
}

// ---- Restore (backup path) ----

@Composable
internal fun ColumnScope.RestoreStep(settings: LauncherSettings) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalKeyboardController.current
    var state by remember { mutableStateOf<Boolean?>(null) } // null: not yet, true: done, false: failed
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pwTitle = stringResource(Res.string.backup_password)
    val wrongPw = stringResource(Res.string.backup_wrong_password)
    val unreadable = stringResource(Res.string.wizard_backup_unreadable)

    fun restore() {
        busy = true
        message = null
        scope.launch {
            val file = runCatching { container.backup.decode(container.backupStorage.read(settings.setupRestore)) }.getOrNull()
            if (file == null) {
                busy = false
                state = false
                message = unreadable
                return@launch
            }
            suspend fun run(pw: String?) {
                val result = runCatching { container.backup.restore(file, pw) }
                busy = false
                state = result.isSuccess || result.exceptionOrNull() is WrongPasswordException
                message = when (val e = result.exceptionOrNull()) {
                    null -> null
                    is WrongPasswordException -> wrongPw
                    else -> e.message
                }
            }
            if (file.secrets != null) keyboard.open(pwTitle, "", TextKind.PASSWORD) { pw -> scope.launch { run(pw) } }
            else run(null)
        }
    }

    WizardStatusRow(
        stringResource(if (state == true) Res.string.wizard_restore_done else Res.string.wizard_restore_now),
        settings.setupRestore.substringAfterLast('/').substringAfterLast('\\').substringAfterLast("%2F"),
        done = state == true, icon = Icons.Filled.SettingsBackupRestore,
        action = if (state == true) null else stringResource(Res.string.backup_restore),
        onClick = if (state == true || busy) null else ::restore, initialFocus = true,
    )
    if (busy) CircularProgressIndicator(color = LauncherTheme.colors.accent)
    message?.let { WizardNote(it, if (state == true) LauncherTheme.colors.warning else LauncherTheme.colors.danger) }
}

// ---- Controller ----

@Composable
internal fun ColumnScope.ControllerStep(settings: LauncherSettings) {
    val nav = LocalNav
    // B shows in the button test here instead of going back (Back stays on the button below).
    SideEffect { nav.backKey = false }
    val controllers = rememberControllers()
    ControllerLayoutDefaultRow(settings, nav.update, initialFocus = true)
    if (controllers.isEmpty()) {
        WizardNote(stringResource(Res.string.wizard_controller_none))
    }
    controllers.forEach { c ->
        val colors = LauncherTheme.colors
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SportsEsports, null, tint = colors.onSurface)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                    Text(
                        listOfNotNull(
                            stringResource(if (c.external) Res.string.controller_external else Res.string.controller_builtin),
                            c.batteryPercent?.let { stringResource(Res.string.controller_battery, it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
                    )
                }
                if (c.canVibrate) LauncherButton(stringResource(Res.string.controller_vibrate_test), onClick = { vibrateController(c.id) }, icon = Icons.Filled.Vibration)
            }
            ControllerLayoutOverrideRow(c, settings, nav.update)
        }
    }
    SectionHeader(stringResource(Res.string.wizard_controller_test))
    WizardNote(stringResource(Res.string.wizard_controller_test_hint))
    ButtonTest(settings.layoutFor(ActivePad.key))
}

/**
 * The buttons of a pad; pressed ones light up for a moment. Face buttons sit where the layout
 * puts them, so a press of the lower button lighting "A" means the layout fits.
 */
@Composable
private fun ButtonTest(layout: ButtonLayout) {
    val dispatcher = LocalInputDispatcher.current
    val lit = remember { mutableStateMapOf<String, Long>() }
    DisposableEffect(dispatcher) {
        dispatcher.pressObserver = { name -> lit[name] = System.currentTimeMillis() }
        onDispose { dispatcher.pressObserver = null }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            val now = System.currentTimeMillis()
            lit.keys.filter { now - (lit[it] ?: 0) > 700 }.forEach { lit.remove(it) }
        }
    }
    val colors = LauncherTheme.colors
    @Composable
    fun Key(name: String, label: String = name, size: Dp = 44.dp) {
        val on = name in lit
        Box(
            Modifier.size(size).clip(CircleShape).background(if (on) colors.accent else colors.surfaceRaised)
                .border(1.dp, colors.onSurfaceMuted.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.background else colors.onSurface, textAlign = TextAlign.Center)
        }
    }
    @Composable
    fun Pill(name: String, label: String) {
        val on = name in lit
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) colors.accent else colors.surfaceRaised).padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.background else colors.onSurface) }
    }
    // Diamond: top, left, right, bottom.
    val face = if (layout == ButtonLayout.XBOX) listOf("Y", "X", "B", "A") else listOf("X", "Y", "A", "B")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("LT", "LT"); Pill("LB", "LB") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("RB", "RB"); Pill("RT", "RT") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Cross { pos -> Key(listOf("UP", "LEFT", "RIGHT", "DOWN")[pos], listOf("↑", "←", "→", "↓")[pos], 38.dp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("SELECT", "Select"); Pill("START", "Start") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("L3", "L3"); Pill("R3", "R3") }
            }
            Cross { pos -> Key(face[pos]) }
        }
    }
}

/** Four elements as a cross: top, left / right, bottom. */
@Composable
private fun Cross(item: @Composable (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(0)
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) { item(1); item(2) }
        item(3)
    }
}

// ---- Appearance ----

@Composable
internal fun ColumnScope.AppearanceStep(settings: LauncherSettings) {
    val nav = LocalNav
    AppearancePreview()
    PictureChoiceRow(
        stringResource(Res.string.setting_theme), ThemeMode.entries, settings.themeMode,
        label = {
            stringResource(
                when (it) {
                    ThemeMode.DARK -> Res.string.theme_dark
                    ThemeMode.BLACK -> Res.string.theme_black
                    ThemeMode.LIGHT -> Res.string.theme_light
                },
            )
        },
        onSelect = { v -> nav.update { it.copy(themeMode = v) } },
        picture = { ThemePicture(it) },
        initialFocus = true,
    )
    ChoiceRow(
        stringResource(Res.string.setting_accent), AccentMode.entries, settings.accentMode,
        label = { stringResource(if (it == AccentMode.COVER) Res.string.accent_cover else Res.string.accent_fixed) },
        onSelect = { v -> nav.update { it.copy(accentMode = v) } },
    )
    var picker by remember { mutableStateOf(false) }
    AccentSwatchRow(
        stringResource(Res.string.setting_accent_color), settings.accentColor,
        onPick = { c -> nav.update { it.copy(accentColor = c) } }, onMore = { picker = true },
        subtitle = if (settings.accentMode == AccentMode.COVER) stringResource(Res.string.setting_accent_color_cover_sub) else null,
    )
    if (picker) AccentColorPicker(settings.accentColor, { c -> nav.update { it.copy(accentColor = c) } }, { picker = false })
    SliderRow(
        stringResource(Res.string.setting_ui_scale), settings.uiScale, 60..130, 5,
        { v -> nav.update { it.copy(uiScale = v) } }, { "$it %" },
    )
    SwitchRow(
        stringResource(Res.string.wizard_appearance_animations), !settings.reduceMotion,
        { on -> nav.update { it.copy(reduceMotion = !on) } },
        subtitle = stringResource(Res.string.wizard_appearance_animations_sub),
    )
}

/** A sample tile and button in the chosen look (not focusable: only to look at). */
@Composable
private fun AppearancePreview() {
    val colors = LauncherTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // A focused tile: accent frame, slightly raised.
        Box(
            Modifier.width(92.dp).height(124.dp).clip(RoundedCornerShape(LauncherTheme.dimens.cornerRadius))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.55f), colors.surfaceRaised)))
                .border(3.dp, colors.accent, RoundedCornerShape(LauncherTheme.dimens.cornerRadius)),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(stringResource(Res.string.tile_preview_sample_game), style = MaterialTheme.typography.labelMedium, color = colors.onSurface, modifier = Modifier.padding(8.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.wizard_appearance_preview), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(stringResource(Res.string.wizard_appearance_preview_sub), style = MaterialTheme.typography.bodySmall, color = colors.accentText)
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(colors.accent).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(stringResource(Res.string.action_play), style = MaterialTheme.typography.labelLarge, color = colors.background)
            }
        }
    }
}

// ---- Finish ----

@Composable
internal fun ColumnScope.FinishStep() {
    val container = LocalContainer.current
    val entries by container.library.allEntries.collectAsState(emptyList())
    val tasks by io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks.tasks.collectAsState()
    val metadata by container.metadata.progress.collectAsState()
    val visible = entries.filter { !it.item.hidden }
    val games = visible.count { it.item.kind == ItemKind.GAME }
    val apps = visible.count { it.item.kind == ItemKind.APP }
    val colors = LauncherTheme.colors
    val busy = tasks.isNotEmpty() || (metadata?.let { it.total > 0 } == true)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.wizard_finish_library, games, apps), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
        Text(
            stringResource(if (busy) Res.string.wizard_finish_loading else Res.string.wizard_finish_ready),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted,
        )
        tasks.forEach { t ->
            Text(
                listOfNotNull(stringResource(t.title, *t.args.toTypedArray()), t.detail).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted,
            )
            if (t.total > 0) LinearProgressIndicator(progress = { t.done.toFloat() / t.total }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surfaceRaised)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surfaceRaised)
        }
        metadata?.takeIf { it.total > 0 }?.let { m ->
            Text(stringResource(Res.string.host_progress_metadata, m.done, m.total), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            LinearProgressIndicator(progress = { m.done.toFloat() / m.total }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surfaceRaised)
        }
    }
}
