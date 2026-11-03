package io.github.thecodingdad.gamelodge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import io.github.thecodingdad.gamelodge.CurrentActivity
import io.github.thecodingdad.gamelodge.MainActivity
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.system.TvSession
import io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerInfo
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.shell.SystemStatus
import io.github.thecodingdad.gamelodge.ui.shell.TileModel
import io.github.thecodingdad.gamelodge.ui.shell.TopCutout
import io.github.thecodingdad.gamelodge.util.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.util.Date
import java.util.UUID

/** Android's side of the shared UI: system state, controllers, intents and the Android-only settings. */
class AndroidPlatformUi(private val context: Context) : PlatformUi {
    override val localIcon = Icons.Filled.PhoneAndroid

    override fun battery(): io.github.thecodingdad.gamelodge.ui.Battery? {
        // The sticky broadcast holds the last battery state; no receiver needs to stay registered.
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!intent.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true)) return null
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        return io.github.thecodingdad.gamelodge.ui.Battery(level * 100 / scale, charging)
    }

    @Composable
    override fun rememberSystemStatus(): SystemStatus = io.github.thecodingdad.gamelodge.ui.shell.rememberAndroidSystemStatus()

    @Composable
    override fun rememberTopCutout(): TopCutout = io.github.thecodingdad.gamelodge.ui.shell.rememberAndroidTopCutout()

    /**
     * Like the system clock: the device's language and its 12 / 24 hour setting, not the
     * launcher's own language (an English launcher on a German device still shows 14:05).
     */
    override fun formatTime(millis: Long): String {
        val locale = android.content.res.Resources.getSystem().configuration.locales[0]
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocales(android.os.LocaleList(locale)) }
        val system = systemContext?.takeIf { it.first == locale }?.second
            ?: context.createConfigurationContext(config).also { systemContext = locale to it }
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (android.text.format.DateFormat.is24HourFormat(system)) "Hm" else "hm")
        return java.text.SimpleDateFormat(pattern, locale).format(Date(millis))
    }

    private var systemContext: Pair<java.util.Locale, android.content.Context>? = null

    @Composable
    override fun rememberControllers(): List<ControllerInfo> = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberAndroidControllers()

    override fun vibrateController(id: Int) = io.github.thecodingdad.gamelodge.ui.screens.settings.vibrateAndroidController(id)

    @Composable
    override fun ControllerDetailDialog(controller: ControllerInfo?, onDismiss: () -> Unit) =
        io.github.thecodingdad.gamelodge.ui.screens.settings.AndroidControllerDetailDialog(controller, onDismiss)

    override fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun bringToFront() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Composable
    override fun rememberSystemTiles(ids: List<String>, revision: Int): Map<String, List<TileModel>> =
        io.github.thecodingdad.gamelodge.ui.shell.rememberAndroidSystemTiles(ids, revision)

    override fun quickTileAvailable(id: String): Boolean = io.github.thecodingdad.gamelodge.ui.shell.androidQuickTileAvailable(context, id)

    override val hasSystemControls get() = true

    @Composable
    override fun SystemControls(visible: Boolean) = io.github.thecodingdad.gamelodge.ui.shell.AndroidSystemControls(visible)

    override val hasQuickAccessEditor get() = true

    override val hasQuickMenuSettings get() = true

    override val hasGamePadExtras get() = true

    @Composable
    override fun QuickMenuSettingsTab() = io.github.thecodingdad.gamelodge.ui.screens.settings.QuickMenuSettingsTab()

    @Composable
    override fun QuickAccessEditor(visible: Boolean, onDismiss: () -> Unit) =
        io.github.thecodingdad.gamelodge.ui.screens.settings.QuickAccessEditor(visible, onDismiss)

    override fun appLabel(packageName: String): String = io.github.thecodingdad.gamelodge.ui.shell.androidAppLabel(context, packageName)

    @Composable
    override fun rememberImagePicker(onPicked: (String?) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(uri?.toString()) }
        return { launcher.launch(arrayOf("image/*")) }
    }

    override suspend fun copyImage(address: String, dir: File, baseName: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val uri = if (address.startsWith("/")) Uri.fromFile(File(address)) else Uri.parse(address)
            val ext = context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 } ?: "img"
            val out = File(dir, "$baseName.$ext")
            context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }?.let { out }
        }.getOrNull()
    }

    @Composable
    override fun rememberOnTv(): Boolean {
        val configuration = LocalConfiguration.current
        val activity = LocalActivity.current
        return remember(configuration) { activity?.let(TvSession::isOnTv) == true }
    }

    @Composable
    override fun rememberScreenKeeper(): (Boolean) -> Unit {
        val view = LocalView.current
        return remember(view) { { on: Boolean -> view.keepScreenOn = on } }
    }

    override fun openAppInfo(packageName: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private val profileEditing by lazy { io.github.thecodingdad.gamelodge.stream.AndroidProfileEditing(context) }

    override fun hasPage(id: SettingsPageId): Boolean = id in PAGES

    override val hasButtonCombos = true

    @Composable
    override fun PlatformSettingsPage(id: SettingsPageId, vm: LauncherViewModel, openProfile: (UUID?) -> Unit, openStats: () -> Unit) {
        val settings by vm.settings.collectAsState()
        when (id) {
            SettingsPageId.USAGE -> io.github.thecodingdad.gamelodge.ui.screens.settings.UsageSettings(openStats)
            SettingsPageId.EMULATORS -> io.github.thecodingdad.gamelodge.ui.screens.settings.EmulatorSettings(vm)
            SettingsPageId.PROFILES -> {
                val profiles by vm.profiles.collectAsState()
                val active by vm.activeProfile.collectAsState()
                io.github.thecodingdad.gamelodge.ui.screens.profiles.ProfilesList(profileEditing, profiles, active, openProfile)
            }
            SettingsPageId.LAUNCHER -> io.github.thecodingdad.gamelodge.ui.screens.settings.LauncherModeSettings(settings, vm::updateSettings)
            SettingsPageId.VPN -> io.github.thecodingdad.gamelodge.ui.screens.settings.VpnSettings(settings, vm::updateSettings)
            else -> Unit
        }
    }

    override val hasStreamKeyboard get() = true

    @Composable
    override fun DeviceInputRows(group: io.github.thecodingdad.gamelodge.ui.DeviceInputGroup, initialFocus: Boolean) {
        // Artemis' input options that depend on this device, the same for every profile (base settings).
        val options = androidx.compose.runtime.remember(group) {
            val schema = io.github.thecodingdad.gamelodge.stream.StreamSettingsSchema
            schema.deviceInputOptions(context).filter {
                val of = when (it.key) {
                    in schema.deviceKeyboardKeys -> io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.KEYBOARD
                    in schema.deviceMouseKeys -> io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.MOUSE
                    else -> io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.TOUCH
                }
                of == group
            }
        }
        if (options.isEmpty()) return
        // Artemis' title doesn't say it's about the mouse in the stream.
        val navTitle = stringResource(Res.string.input_mouse_nav_stream)
        val navSub = stringResource(Res.string.input_mouse_nav_stream_sub)
        val shown = options.map { o ->
            if (o.key == "checkbox_mouse_nav_buttons" && o is io.github.thecodingdad.gamelodge.stream.StreamOption.Toggle) o.copy(title = navTitle, summary = navSub) else o
        }
        if (group == io.github.thecodingdad.gamelodge.ui.DeviceInputGroup.TOUCH) {
            io.github.thecodingdad.gamelodge.ui.screens.settings.SectionHeader(stringResource(Res.string.input_touch_section))
            androidx.compose.material3.Text(
                stringResource(Res.string.input_touch_sub), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.onSurfaceMuted,
            )
        }
        val base = androidx.compose.runtime.remember { io.github.thecodingdad.gamelodge.stream.ProfileStore(context, null) }
        var revision by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        shown.forEachIndexed { i, option ->
            io.github.thecodingdad.gamelodge.ui.screens.profiles.OptionRow(option, base, revision, initialFocus && i == 0, profileEditing) { revision++ }
        }
    }

    @Composable
    override fun ControllersScreen() = io.github.thecodingdad.gamelodge.ui.screens.settings.ControllersScreen()

    override val controllerSections = io.github.thecodingdad.gamelodge.ui.screens.settings.ANDROID_CONTROLLER_SECTIONS

    @Composable
    override fun ControllerSectionScreen(section: io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection) =
        io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSectionScreen(section)

    @Composable
    override fun ProfileEditor(uuid: UUID?, name: String, onDeleted: () -> Unit) =
        io.github.thecodingdad.gamelodge.ui.screens.profiles.ProfileEditor(profileEditing, uuid, name, onDeleted)

    @Composable
    override fun SystemSettingsRow() {
        NavRow(
            stringResource(Res.string.settings_android), {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            stringResource(Res.string.settings_android_sub), Icons.Filled.Android,
        )
    }

    /** Android deletes the app's data (databases, settings, keys) and ends the process. */
    override fun resetAllData() {
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
    }

    override val wizardSteps get() = io.github.thecodingdad.gamelodge.ui.screens.wizard.AndroidWizard.steps
    override val wizardDeviceSteps get() = io.github.thecodingdad.gamelodge.ui.screens.wizard.AndroidWizard.deviceSteps

    @Composable
    override fun WizardStepContent(step: io.github.thecodingdad.gamelodge.ui.screens.wizard.WizardStep) =
        io.github.thecodingdad.gamelodge.ui.screens.wizard.AndroidWizard.Content(step)

    override fun applyLanguage(tag: String) {
        AppLanguage.apply(context, tag)
        // Before Android 13 the activity takes the language when it starts again.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) CurrentActivity.get()?.recreate()
    }

    private companion object {
        val PAGES = setOf(
            SettingsPageId.USAGE, SettingsPageId.EMULATORS, SettingsPageId.PROFILES,
            SettingsPageId.LAUNCHER, SettingsPageId.VPN,
        )
    }
}
