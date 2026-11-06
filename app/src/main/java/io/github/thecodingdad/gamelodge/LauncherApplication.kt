package io.github.thecodingdad.gamelodge

import io.github.thecodingdad.gamelodge.data.settings.combo
import io.github.thecodingdad.gamelodge.input.handle
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.limelight.ArtemisApplication
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Extends Artemis' application so its global state (stream profiles) is initialised
 * exactly as in a standalone Artemis install.
 */
class LauncherApplication : ArtemisApplication(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDefaultLocale()
    }

    /**
     * The launcher's own language as the process default: the shared texts follow the default
     * locale, which Android resets to the device's language on configuration changes and which a
     * fresh process sometimes still has at the device's (German texts with English chosen).
     */
    private fun applyDefaultLocale() {
        val locales = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: the per-app language is what Android gives the activities' resources
            // (also when it was changed in the system's app language setting), so the default
            // follows it; the launcher's own setting may be older ("system" with English set there).
            val manager = getSystemService(android.app.LocaleManager::class.java) ?: return
            manager.applicationLocales.takeIf { !it.isEmpty } ?: manager.systemLocales
        } else {
            val tag = getSharedPreferences(io.github.thecodingdad.gamelodge.util.AppLanguage.PREFS, MODE_PRIVATE)
                .getString(io.github.thecodingdad.gamelodge.util.AppLanguage.KEY, null)
            if (tag == null || tag == "system") return
            android.os.LocaleList.forLanguageTags(tag)
        }.takeIf { !it.isEmpty } ?: return
        if (java.util.Locale.getDefault() == locales[0]) return
        android.os.LocaleList.setDefault(locales)
        java.util.Locale.setDefault(locales[0])
    }

    override fun onCreate() {
        super.onCreate()
        applyDefaultLocale()
        registerActivityLifecycleCallbacks(CurrentActivity)
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = applyDefaultLocale()
            override fun onActivityResumed(activity: android.app.Activity) = applyDefaultLocale()
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        container = AppContainer(this)
        // Always offered as a home app (earlier versions switched the alias off).
        io.github.thecodingdad.gamelodge.system.LauncherMode(this).ensureOffered()
        io.github.thecodingdad.gamelodge.stream.ProfileStore.resetRenderMode(this)
        io.github.thecodingdad.gamelodge.stream.ProfileStore.moveToBase(
            this, io.github.thecodingdad.gamelodge.stream.StreamSettingsSchema.gamepadOptions(this).map { it.key },
        )
        // The device's touch and keyboard options hold for every profile too.
        io.github.thecodingdad.gamelodge.stream.ProfileStore.moveToBase(
            this, io.github.thecodingdad.gamelodge.stream.StreamSettingsSchema.deviceInputKeys, "profile_device_input_to_base",
        )
        io.github.thecodingdad.gamelodge.stream.ProfileStore.moveToBase(
            this, io.github.thecodingdad.gamelodge.stream.StreamSettingsSchema.deviceMouseKeys + "checkbox_enable_commit_text", "profile_device_input_to_base_2",
        )
        // Every controller setting is per controller now: pin the gamepad options GameLodge no
        // longer offers and turn the former global values into the known controllers' own, once.
        io.github.thecodingdad.gamelodge.stream.ControllerPrefsMigration.enforce(this)
        io.github.thecodingdad.gamelodge.stream.ControllerPrefsMigration.migrate(
            this, container.controllerSettings, io.github.thecodingdad.gamelodge.stream.ControllerOrderStore(this),
        )
        // Right after boot the system is busy (on the Odin the low-memory killer then stops the
        // heaviest foreground app): the start tasks wait a moment, and run one after the other.
        if (android.os.SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            io.github.thecodingdad.gamelodge.util.StartupSequence.holdFor(BOOT_PAUSE_MS)
        }
        io.github.thecodingdad.gamelodge.data.artwork.ArtworkFiles.init(filesDir)
        io.github.thecodingdad.gamelodge.ui.components.ArtworkPlatform.current = io.github.thecodingdad.gamelodge.ui.components.AndroidArtwork(this)
        io.github.thecodingdad.gamelodge.ui.PlatformUi.current = io.github.thecodingdad.gamelodge.ui.AndroidPlatformUi(this)
        // Artwork first in line: missing covers are what one notices.
        container.artwork.start()
        container.hosts.bind()
        container.library.start()
        container.emulation.start()
        container.mqtt.start()
        container.controllerSettings.install(
            launcher = { currentSettings },
            game = { container.launcher.activeStream.value?.entry?.item?.id },
        )
        io.github.thecodingdad.gamelodge.ui.shell.StreamConnectingOverlay.install(container)
        io.github.thecodingdad.gamelodge.ui.shell.ConnectionWarningOverlay.install(container)
        container.scope.launch { container.filters.ensureDefaults() }
        container.scope.launch {
            io.github.thecodingdad.gamelodge.stream.ControllerPrefsMigration.migrateLauncher(
                this@LauncherApplication, container.controllerSettings,
                io.github.thecodingdad.gamelodge.stream.ControllerOrderStore(this@LauncherApplication), container.settings,
            )
        }
        // Button combinations: needed by the activity and by the accessibility service, which
        // can run without any activity (e.g. after an update).
        container.scope.launch {
            container.controllerSettings.all.collect { all ->
                io.github.thecodingdad.gamelodge.input.ButtonCombos.overrides = all.mapValues { (_, s) ->
                    io.github.thecodingdad.gamelodge.input.ComboAction.entries
                        .mapNotNull { a -> s.combo(a)?.let { a to it.toSet() } }.toMap()
                }.filterValues { it.isNotEmpty() }
                io.github.thecodingdad.gamelodge.input.ButtonCombos.forwardOverrides =
                    all.mapNotNull { (descriptor, s) -> s.comboForward?.let { descriptor to it } }.toMap()
                // Dead zone and vibration off also apply in GameLodge itself.
                io.github.thecodingdad.gamelodge.input.PadPreferences.deadzones =
                    all.mapNotNull { (descriptor, s) -> s.deadzonePercent?.let { descriptor to it / 100f } }.toMap()
                io.github.thecodingdad.gamelodge.input.PadPreferences.vibrationOff =
                    all.filterValues { it.vibration == io.github.thecodingdad.gamelodge.ui.screens.settings.VibrationMode.OFF }.keys
            }
        }
        // Controllers without their own combinations use the built-in ones.
        io.github.thecodingdad.gamelodge.input.ButtonCombos.forward = io.github.thecodingdad.gamelodge.ui.screens.settings.DefaultCombos.comboForward
        io.github.thecodingdad.gamelodge.input.ButtonCombos.combos = io.github.thecodingdad.gamelodge.input.ComboAction.entries
            .associateWith { a -> io.github.thecodingdad.gamelodge.ui.screens.settings.DefaultCombos.combo(a) }
        io.github.thecodingdad.gamelodge.input.AndroidCombos.installReplay()
        // Back in the stream (and Artemis' other ways to its game menu) opens GameLodge's quick menu.
        com.limelight.StreamControl.menuAction = Runnable { io.github.thecodingdad.gamelodge.QuickMenuActivity.toggle(this) }
        // A stream that didn't get going: the launcher retries it, then reports it in its own design.
        com.limelight.StreamControl.startFailureListener = com.limelight.StreamControl.StartFailureListener { stage, code ->
            val error = if (code == 403) io.github.thecodingdad.gamelodge.stream.LaunchError.NO_LAUNCH_PERMISSION
            else io.github.thecodingdad.gamelodge.stream.LaunchError.START_FAILED
            container.launcher.streamStartFailed(error, listOfNotNull(stage, code.toString()).joinToString(" · "))
        }
        // Motion via the device's sensors is each controller's choice (Controllers › the controller): no toast about it.
        com.limelight.StreamControl.controllerTypeToast = false
        // Button hints with keys also when only a keyboard is connected.
        io.github.thecodingdad.gamelodge.input.KeyboardWatcher.start(this)
        // A game that wants the mouse emulation on (or off) gets it when its stream starts.
        container.scope.launch {
            container.launcher.activeStream.map { it?.entry?.item?.id }.distinctUntilChanged().collect { id ->
                val wanted = id?.let { currentSettings.gamePads[it]?.mouse } ?: return@collect
                io.github.thecodingdad.gamelodge.input.PointerMode.set(wanted)
            }
        }
        // In the stream without the accessibility service (which otherwise sees the buttons first):
        // combinations work there too and their buttons don't reach the host.
        com.limelight.binding.input.KeyInterceptor.interceptor = com.limelight.binding.input.KeyInterceptor.Interceptor { activity, event ->
            io.github.thecodingdad.gamelodge.input.ComboAccessibilityService.instance == null &&
                io.github.thecodingdad.gamelodge.input.ButtonCombos.handle(event) { action, controller ->
                    io.github.thecodingdad.gamelodge.input.AndroidCombos.run(activity, action, controller)
                }
        }
        container.scope.launch {
            container.settings.settings.collect {
                currentSettings = it
                io.github.thecodingdad.gamelodge.input.KeyboardKeys.mapping = it.keyboardKeys
                (container.streamControls as? io.github.thecodingdad.gamelodge.stream.AndroidStreamControls)?.pcKeyboard =
                    it.streamKeyboard == io.github.thecodingdad.gamelodge.data.settings.StreamKeyboard.PC
                io.github.thecodingdad.gamelodge.input.KeyboardKeys.quickMenuShortcut = io.github.thecodingdad.gamelodge.input.KeyShortcut.parse(it.keyboardQuickMenu)
                io.github.thecodingdad.gamelodge.input.PointerMode.speed = it.mouseSpeed / 100f
                io.github.thecodingdad.gamelodge.input.PointerMode.exponent = it.mouseAcceleration.exponent
                com.limelight.StreamControl.mouseSpeed = STREAM_MOUSE_SPEED * it.mouseSpeed / 100f
                com.limelight.StreamControl.mouseExponent = it.mouseAcceleration.exponent
                // Mouse, leaving the stream and its overlay are GameLodge's combinations now.
                com.limelight.StreamControl.builtInCombos = false
            }
        }
        container.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // Never with a TV attached (mirrored it would show there, and nobody touches a TV).
            kotlinx.coroutines.flow.combine(
                container.settings.settings.map { it.quickMenuEdgeHandle },
                io.github.thecodingdad.gamelodge.system.ExternalDisplay.observe(this@LauncherApplication),
            ) { on, tv -> on && tv == null }.distinctUntilChanged().collect {
                io.github.thecodingdad.gamelodge.system.EdgeHandle.update(this@LauncherApplication, it)
            }
        }
        // Host web API logins of older versions aren't used any more.
        container.secrets.keys().filter { it.startsWith(io.github.thecodingdad.gamelodge.data.settings.SecretStore.Keys.LEGACY_HOST_API_PREFIX) }
            .forEach { container.secrets.put(it, null) }
        container.servers.start()
        container.metadata.start()
        // Pairing waits for a PIN typed in another app: a short foreground service keeps us awake.
        io.github.thecodingdad.gamelodge.util.BackgroundHold.start = { tag, text ->
            io.github.thecodingdad.gamelodge.system.KeepAliveService.hold(this, tag, text)
        }
        io.github.thecodingdad.gamelodge.system.OdinMapping(this)
            .watch(container.scope, container.settings.settings.map { it.odinKeepControllers })
        // Host polling is driven by MainActivity's lifecycle: Artemis' stream activity runs in
        // this process too, and polling must not compete with an active stream.
    }

    private companion object {
        const val BOOT_WINDOW_MS = 3 * 60_000L
        const val BOOT_PAUSE_MS = 10_000L
    }

    /** Coil loader with animated WebP/GIF support, sharing the app's HTTP client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .build()
}

/** The latest settings for code that can't suspend (Artemis asks for controller overrides synchronously). */
@Volatile private var currentSettings = io.github.thecodingdad.gamelodge.data.settings.LauncherSettings()

/** Host pixels per second of the stream's mouse at full stick deflection and 100 % speed. */
private const val STREAM_MOUSE_SPEED = 1200f

val Context.container: AppContainer
    get() = (applicationContext as LauncherApplication).container
