package io.github.thecodingdad.gamelodge.ui

import io.github.thecodingdad.gamelodge.ui.screens.settings.asHostState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.layout.layout
import io.github.thecodingdad.gamelodge.ui.components.BackHandler
import androidx.savedstate.read
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thecodingdad.gamelodge.data.db.MetadataState
import io.github.thecodingdad.gamelodge.ui.screens.metadata.ArtworkPickerDialog
import io.github.thecodingdad.gamelodge.ui.screens.metadata.FixMetadataDialog
import io.github.thecodingdad.gamelodge.ui.screens.metadata.MetadataSettings
import io.github.thecodingdad.gamelodge.ui.screens.metadata.ReviewScreen
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.thecodingdad.gamelodge.ui.components.ButtonHint
import io.github.thecodingdad.gamelodge.ui.components.ButtonHintBar
import io.github.thecodingdad.gamelodge.ui.components.ControllerKeyboardHost
import io.github.thecodingdad.gamelodge.ui.components.FocusSurface
import io.github.thecodingdad.gamelodge.ui.components.KeyboardController
import io.github.thecodingdad.gamelodge.ui.components.LocalButtonLayout
import io.github.thecodingdad.gamelodge.ui.components.LocalKeyboardController
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.gamelodge.ui.feedback.UiFeedback
import io.github.thecodingdad.gamelodge.ui.input.InputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.LocalInputDispatcher
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.screens.apps.AppsScreen
import io.github.thecodingdad.gamelodge.ui.screens.detail.DetailScreen
import io.github.thecodingdad.gamelodge.ui.screens.ha.HaHomeRow
import io.github.thecodingdad.gamelodge.ui.screens.ha.HaSettings
import io.github.thecodingdad.gamelodge.ui.screens.home.HeroHome
import io.github.thecodingdad.gamelodge.ui.screens.settings.HostConfigScreen
import io.github.thecodingdad.gamelodge.ui.screens.stats.StatsScreen
import io.github.thecodingdad.gamelodge.ui.shell.CollectionsDialog
import io.github.thecodingdad.gamelodge.ui.shell.QuickMenu
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import io.github.thecodingdad.gamelodge.ui.screens.home.HomeCallbacks
import io.github.thecodingdad.gamelodge.ui.screens.home.HomeRows
import io.github.thecodingdad.gamelodge.ui.screens.library.LibraryScreen
import io.github.thecodingdad.gamelodge.ui.screens.settings.AboutSettings
import io.github.thecodingdad.gamelodge.ui.screens.settings.HostsSettings
import io.github.thecodingdad.gamelodge.ui.screens.settings.LanguageSettings
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsHome
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPage
import io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.gamelodge.ui.shell.EntryActions
import io.github.thecodingdad.gamelodge.ui.shell.EntryContextMenu
import io.github.thecodingdad.gamelodge.ui.shell.HintHolder
import io.github.thecodingdad.gamelodge.ui.shell.LaunchOverlay
import io.github.thecodingdad.gamelodge.ui.shell.LocalEntryActions
import io.github.thecodingdad.gamelodge.ui.shell.LocalHintHolder
import io.github.thecodingdad.gamelodge.ui.shell.StatusCluster
import io.github.thecodingdad.gamelodge.ui.shell.TargetPickerDialog
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.screens.settings.layoutFor

/** Maps the stored settings to the feedback engine's configuration. */
fun io.github.thecodingdad.gamelodge.data.settings.LauncherSettings.feedbackConfig() =
    io.github.thecodingdad.gamelodge.ui.feedback.FeedbackConfig(
        soundsEnabled = uiSounds,
        volume = uiSoundVolume / 100f,
        style = runCatching { io.github.thecodingdad.gamelodge.ui.feedback.SoundStyle.valueOf(soundStyle) }
            .getOrDefault(io.github.thecodingdad.gamelodge.ui.feedback.SoundStyle.MUFFLED),
        pitch = soundPitch,
        mutedSounds = mutedSounds.mapNotNull { name ->
            io.github.thecodingdad.gamelodge.ui.feedback.UiSound.entries.firstOrNull { it.name == name }
        }.toSet(),
        hapticsEnabled = haptics,
        hapticStrength = runCatching { io.github.thecodingdad.gamelodge.ui.feedback.HapticStrength.valueOf(hapticStrength) }
            .getOrDefault(io.github.thecodingdad.gamelodge.ui.feedback.HapticStrength.MEDIUM),
        hapticOnFocus = hapticOnFocus,
    )

private object Routes {
    const val MAIN = "main"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings/{page}"
    const val REVIEW = "review"
    const val STATS = "stats"
    const val HOST = "host/{uuid}"
    const val CONTROLLERS = "controllers"
    const val CONTROLLER_SECTION = "controllers/{section}"
    fun controllerSection(section: io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection) = "controllers/${section.name}"
    const val WIZARD = "wizard/{manual}"
    fun wizard(manual: Boolean) = "wizard/$manual"
    fun host(uuid: String) = "host/$uuid"
    const val PROFILE = "profile/{uuid}"
    fun profile(uuid: java.util.UUID?) = "profile/${uuid ?: "base"}"
    fun detail(id: String) = "detail/$id"
    fun settings(page: SettingsPageId) = "settings/${page.name}"
}

/** Main tabs not shown right now (Apps while there are none); their numbers stay the same. */
val LocalHiddenTabs = androidx.compose.runtime.compositionLocalOf { emptySet<MainTab>() }

/** The tab [delta] steps from [current] (by ordinal), skipping hidden ones. */
fun stepTab(current: Int, delta: Int, hidden: Set<MainTab>): Int {
    var i = current
    repeat(MainTab.entries.size) {
        i = (i + delta).mod(MainTab.entries.size)
        if (MainTab.entries[i] !in hidden) return i
    }
    return current
}

enum class MainTab(val title: org.jetbrains.compose.resources.StringResource, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME(Res.string.tab_home, androidx.compose.material.icons.Icons.Filled.Home),
    LIBRARY(Res.string.tab_library, androidx.compose.material.icons.Icons.Filled.VideoLibrary),
    APPS(Res.string.tab_apps, androidx.compose.material.icons.Icons.Filled.Apps),
    SETTINGS(Res.string.tab_settings, androidx.compose.material.icons.Icons.Filled.Settings),
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LauncherApp(
    vm: LauncherViewModel,
    dispatcher: InputDispatcher,
    feedback: UiFeedback,
    /** Passed in where the UI may be rebuilt (desktop language change) and must stay on its page. */
    nav: androidx.navigation.NavHostController = rememberNavController(),
) {
    val settings by vm.settings.collectAsState()
    val keyboard = remember { KeyboardController() }
    val hints = remember { HintHolder() }
    val container = io.github.thecodingdad.gamelodge.LocalContainer.current

    LaunchedEffect(settings) { feedback.configure(settings.feedbackConfig()) }
    // First controller input after touching: continue from the touched element.
    val controllerMode = io.github.thecodingdad.gamelodge.ui.components.isControllerMode()
    LaunchedEffect(controllerMode) { if (controllerMode) io.github.thecodingdad.gamelodge.ui.components.TouchFocus.restore() }

    val entryActions = remember(nav) {
        EntryActions(
            openDetail = { entry -> nav.navigate(Routes.detail(entry.item.id)) },
            launchDirect = { entry, target -> vm.launch(entry, target) },
        )
    }

    var quickMenu by remember { mutableStateOf(false) }
    // The tab the quick menu opens on (status bar host / controller icons), null: its default.
    var quickMenuTab by remember { mutableStateOf<io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab?>(null) }
    fun openQuickMenu(tab: io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab) {
        quickMenuTab = tab
        quickMenu = true
    }
    // After start: the configured start tab (read from storage, not the not-yet-loaded default).
    LaunchedEffect(Unit) {
        if (!vm.startTabApplied) {
            vm.startTabApplied = true
            val before = vm.tab
            val start = MainTab.valueOf(container.settings.settings.first().startTab.name).ordinal
            // Unless the user already switched tabs in the meantime.
            if (vm.tab == before) vm.tab = start
        }
    }
    // A fresh install (or a restart in the middle of it) goes through the setup wizard; Settings ›
    // System runs it again. The stored settings decide, not the defaults before loading.
    LaunchedEffect(Unit) {
        val stored = container.settings.settings.first()
        // Navigating works once the graph is set (the NavHost's first frame).
        nav.currentBackStackEntryFlow.first()
        if (!stored.setupDone) nav.navigate(Routes.wizard(manual = false))
    }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            // Every open popup closes, wherever it was opened.
            io.github.thecodingdad.gamelodge.ui.components.OverlayHost.dismissAll()
            // Home while the launcher is shown: to the configured start tab. (Coming back from
            // another app or a stream, the first Home just shows what was there before.)
            val start = MainTab.valueOf(vm.settings.value.startTab.name).ordinal
            // Already at the untouched start screen: nothing to do (don't reload artwork).
            val alreadyHome = vm.tab == start && nav.currentDestination?.route == Routes.MAIN &&
                (start != MainTab.HOME.ordinal || vm.homeAtStart)
            nav.popBackStack(Routes.MAIN, inclusive = false)
            vm.tab = start
            if (!alreadyHome && start == MainTab.HOME.ordinal) {
                // Home starts at the top again: forget the remembered position of the start tab.
                vm.focusKeys.remove(MainTab.HOME.name)
                vm.homeResets.intValue++
            }
            entryActions.pick = null
            entryActions.closeContext()
            keyboard.close()
            quickMenu = false
        }
    }
    val inputModeManager = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(Unit) {
        vm.quickMenuRequests.collect { open ->
            // Opened by a controller combination: show the focus right away.
            inputModeManager.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            quickMenu = open || !quickMenu
        }
    }
    LaunchedEffect(Unit) { vm.quickMenuTouch.collect { quickMenu = true } }
    LaunchedEffect(Unit) {
        vm.settingsRequests.collect { target ->
            quickMenu = false
            when (target) {
                is io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Page -> nav.navigate(Routes.settings(target.id))
                io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Controllers -> nav.navigate(Routes.CONTROLLERS)
                is io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.ControllerPage -> nav.navigate(Routes.controllerSection(target.section))
                io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Wizard -> nav.navigate(Routes.wizard(manual = true))
                is io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Host -> nav.navigate(Routes.host(target.uuid))
                io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Overview -> nav.popBackStack(Routes.MAIN, inclusive = false)
                io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Review -> nav.navigate(Routes.REVIEW)
            }
        }
    }
    var platformFor by remember { mutableStateOf<io.github.thecodingdad.gamelodge.data.db.LibraryEntry?>(null) }
    var metaEditorFor by remember { mutableStateOf<String?>(null) }
    var fixingFor by remember { mutableStateOf<String?>(null) }
    var artworkFor by remember { mutableStateOf<String?>(null) }
    var appTileFor by remember { mutableStateOf<String?>(null) }
    val metaScope = rememberCoroutineScope()
    // Game whose detail page is on screen (its menu leaves out what the page offers itself).
    // Game whose detail page is on screen (paging through a list changes it without navigating).
    var detailShownId by remember { mutableStateOf<String?>(null) }
    val detailOpenFor = detailShownId
    var collectionsFor by remember { mutableStateOf<io.github.thecodingdad.gamelodge.data.db.LibraryEntry?>(null) }
    // A host game to hide: only here or on every GameLodge of its host.
    var hideChoiceFor by remember { mutableStateOf<io.github.thecodingdad.gamelodge.data.db.LibraryEntry?>(null) }
    var emulatorFor by remember { mutableStateOf<String?>(null) }
    var launchSettingsFor by remember { mutableStateOf<String?>(null) }
    var gameActionsFor by remember { mutableStateOf<String?>(null) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollSpec = remember(density) { io.github.thecodingdad.gamelodge.ui.components.MarginBringIntoViewSpec(density) }

    // Mirrored to a TV the handheld-sized layout looks huge: scale the whole UI down by lowering
    // the density. Running on the TV itself: lay out for ~960 dp width, like on the handheld.
    val external by vm.externalDisplay.collectAsState()
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val onTv = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.rememberOnTv()
    // The TV size setting applies on the TV either way (mirrored or running there).
    val uiScale = (if (settings.tvScaleAuto && (external != null || onTv)) settings.tvScale else settings.uiScale) / 100f
    // Small handhelds: lowered further so a row of covers always fits (see fittedDensity). Measured
    // with medium cards: larger ones must not shrink the whole interface (the start tab limits them).
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val scaledDensity = remember(baseDensity, uiScale, onTv, window) {
        // On the TV the launcher fills the screen: its width in pixels decides the density.
        val base = if (onTv) window.width / 960f else baseDensity.density
        val fitted = io.github.thecodingdad.gamelodge.ui.theme.fittedDensity(base * uiScale, window.width, window.height, io.github.thecodingdad.gamelodge.data.settings.CardSize.MEDIUM)
        androidx.compose.ui.unit.Density(fitted, baseDensity.fontScale)
    }

    val libraryLoaded by vm.libraryLoaded.collectAsState()
    // No apps (once the library is read): no Apps tab.
    val appsForTabs by vm.apps.collectAsState()
    val hiddenTabs = if (libraryLoaded && appsForTabs.isEmpty()) setOf(MainTab.APPS) else emptySet()
    // Running games and apps (marked on their tiles, Y held quits them).
    val runningForTiles by vm.runningSessions.collectAsState()
    val runningItems = io.github.thecodingdad.gamelodge.ui.components.rememberRunningItems(runningForTiles)
    val quitHold = remember { io.github.thecodingdad.gamelodge.ui.components.QuitHold() }
    // The Home Assistant tile focused in the start tab's row (its hints replace the game's).
    val haFocus = remember { io.github.thecodingdad.gamelodge.ui.screens.ha.HaTileFocus() }
    LauncherTheme(settings) {
        CompositionLocalProvider(
            io.github.thecodingdad.gamelodge.ui.components.LocalRunningItems provides runningItems,
            io.github.thecodingdad.gamelodge.ui.screens.library.LocalLibraryLoaded provides libraryLoaded,
            LocalHiddenTabs provides hiddenTabs,
            io.github.thecodingdad.gamelodge.ui.screens.ha.LocalHaTileFocus provides haFocus,
            io.github.thecodingdad.gamelodge.ui.components.LocalQuitHold provides quitHold,
            androidx.compose.ui.platform.LocalDensity provides scaledDensity,
            LocalInputDispatcher provides dispatcher,
            LocalUiFeedback provides feedback,
            LocalKeyboardController provides keyboard,
            LocalHintHolder provides hints,
            LocalEntryActions provides entryActions,
            LocalButtonLayout provides settings.layoutFor(
                io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.key,
                io.github.thecodingdad.gamelodge.ui.screens.settings.ActivePad.detectedLayout,
            ),
            LocalBringIntoViewSpec provides scrollSpec,
            io.github.thecodingdad.gamelodge.ui.components.LocalAnimatedTilesAlways provides settings.animatedTilesAlways,
            io.github.thecodingdad.gamelodge.ui.screens.settings.LocalSettingsNavigator provides { target, highlight ->
                io.github.thecodingdad.gamelodge.ui.screens.settings.SearchHighlight.request(highlight)
                when (target) {
                    is io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Page -> nav.navigate(Routes.settings(target.id))
                    io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Controllers -> nav.navigate(Routes.CONTROLLERS)
                    is io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.ControllerPage -> nav.navigate(Routes.controllerSection(target.section))
                    io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Wizard -> nav.navigate(Routes.wizard(manual = true))
                    is io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Host -> nav.navigate(Routes.host(target.uuid))
                    io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Overview -> nav.popBackStack(Routes.MAIN, inclusive = false)
                    io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Review -> nav.navigate(Routes.REVIEW)
                }
            },
        ) {
            io.github.thecodingdad.gamelodge.ui.input.RightStickScrollDriver()
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // Button hints only make sense with a controller (and can be turned off entirely).
            val hintControllers = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers()
            CompositionLocalProvider(
                io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth provides (maxWidth < 720.dp),
                io.github.thecodingdad.gamelodge.ui.components.LocalShowHintBar provides io.github.thecodingdad.gamelodge.input.KeyboardKeys.hintsShown(settings.showHintBar, hintControllers.size),
                io.github.thecodingdad.gamelodge.ui.components.LocalKeyHints provides io.github.thecodingdad.gamelodge.input.KeyboardKeys.showsKeys(hintControllers.size),
            ) {
            Box(Modifier.fillMaxSize().background(LauncherTheme.colors.background)) {
                // Screensaver after the configured idle time (also protects the OLED panel).
                var idle by remember { mutableStateOf(false) }
                val lastInput by vm.lastInteraction.collectAsState()
                val screensaverAllowed = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.rememberScreensaverAllowed()
                // Shown by hand (Settings › Display): the input that asked for it doesn't end it.
                var shownByHandUntil by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                LaunchedEffect(Unit) {
                    io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.requests.collect {
                        shownByHandUntil = System.currentTimeMillis() + io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.GRACE_MS
                        idle = true
                    }
                }
                LaunchedEffect(lastInput, settings.screensaverMinutes, screensaverAllowed) {
                    if (System.currentTimeMillis() < shownByHandUntil) return@LaunchedEffect
                    idle = false
                    if (settings.screensaverMinutes <= 0 || !screensaverAllowed) return@LaunchedEffect
                    kotlinx.coroutines.delay(settings.screensaverMinutes * 60_000L)
                    idle = true
                }
                // The launcher behind any popup or panel is blurred (Android 12+; older versions just dim).
                val backdropActive = io.github.thecodingdad.gamelodge.ui.components.OverlayHost.anyVisible
                val backdropBlur by androidx.compose.animation.core.animateDpAsState(if (backdropActive) 14.dp else 0.dp, label = "backdropBlur")
                val currentEntry by nav.currentBackStackEntryAsState()
                CompositionLocalProvider(io.github.thecodingdad.gamelodge.ui.shell.LocalCurrentNavEntry provides currentEntry) {
                NavHost(
                    nav, startDestination = Routes.MAIN,
                    modifier = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.backdropBlur(backdropActive, backdropBlur)
                        // Hidden behind the (opaque) screensaver: not drawn at all, every frame of it would draw it too.
                        .then(if (idle) Modifier.drawWithContent { } else Modifier),
                ) {
                    composable(Routes.MAIN) {
                        MainShell(
                            vm, nav, openStats = { nav.navigate(Routes.STATS) },
                            onHostInfo = { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, onControllers = { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) },
                        )
                    }
                    composable(Routes.DETAIL) { backStack ->
                        val startId = backStack.arguments?.read { getStringOrNull("id") }.orEmpty()
                        val browse = remember(startId) { entryActions.browse?.takeIf { startId in it.ids } }
                        var lastShown by remember { mutableStateOf(startId) }
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onDispose {
                                detailShownId = null
                                if (lastShown != startId) entryActions.returnFocusId = lastShown
                            }
                        }
                        ShellFrame(hints) {
                        // Status icons and a back button on top; the artwork runs on behind them.
                        var detailBarHeight by remember { mutableStateOf(0.dp) }
                        val detailDensity = androidx.compose.ui.platform.LocalDensity.current
                        val detailHosts by vm.hosts.collectAsState()
                        val detailControllers = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers()
                        Box(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(io.github.thecodingdad.gamelodge.ui.screens.detail.LocalDetailTopInset provides detailBarHeight) {
                        io.github.thecodingdad.gamelodge.ui.screens.detail.DetailBrowser(
                            startId, browse?.ids,
                            onShown = { shown -> detailShownId = shown; lastShown = shown; browse?.onShown?.invoke(shown) },
                        ) { id, onBrowse, pageActive ->
                            val entry by remember(id) { vm.entry(id) }.collectAsState(null)
                            val sessions by remember(id) { vm.sessions(id) }.collectAsState(emptyList())
                            entry?.let { e ->
                                val detailProfiles by vm.profiles.collectAsState()
                                val detailActive by vm.activeProfile.collectAsState()
                                val detailRunning by vm.runningSessions.collectAsState()
                                val runningSession = detailRunning.firstOrNull { it.entry?.item?.id == e.item.id }
                                // Or running on this PC (started by the launcher, Windows).
                                val detailLocal by io.github.thecodingdad.gamelodge.stream.LocalSessions.running.collectAsState()
                                val localSession = detailLocal?.takeIf { it.entry.item.id == e.item.id }
                                val localKey = "local:${e.item.id}"
                                // Or running on an Xbox console.
                                val xboxGame = io.github.thecodingdad.gamelodge.xbox.rememberXboxRunning().firstOrNull { it.entry?.item?.id == e.item.id && it.target != null }
                                val detailQuitting by io.github.thecodingdad.gamelodge.stream.Quitting.keys.collectAsState()
                                DetailScreen(
                                    e, sessions, settings.animatedTiles, settings.animatedHero, onFavorite = { vm.setFavorite(e, it) }, onPlatform = { platformFor = e },
                                    running = runningSession != null || localSession != null || xboxGame != null,
                                    onResume = {
                                        localSession?.let { it.resume(); return@DetailScreen }
                                        xboxGame?.let { io.github.thecodingdad.gamelodge.ui.components.resumeXbox(container, it); return@DetailScreen }
                                        val s = runningSession ?: return@DetailScreen
                                        e.remoteTargets.firstOrNull { it.hostUuid == s.host.uuid && it.appId == s.host.runningAppId }?.let { vm.launch(e, it) }
                                    },
                                    onQuit = {
                                        localSession?.let { l ->
                                            io.github.thecodingdad.gamelodge.stream.Quitting.start(
                                                container.scope, localKey, e.item.title, quit = { l.quit() },
                                                running = { io.github.thecodingdad.gamelodge.stream.LocalSessions.running.value?.entry?.item?.id == e.item.id },
                                            )
                                            return@DetailScreen
                                        }
                                        xboxGame?.let { io.github.thecodingdad.gamelodge.ui.components.startXboxQuit(container, it); return@DetailScreen }
                                        val s = runningSession ?: return@DetailScreen
                                        io.github.thecodingdad.gamelodge.stream.Quitting.quitHost(container, s.host, e.item.title)
                                    },
                                    onRate = { vm.setUserRating(e, it) },
                                    quitting = if (localSession != null) localKey in detailQuitting
                                    else if (xboxGame != null) "xbox:${xboxGame.consoleId}" in detailQuitting
                                    else runningSession?.let { io.github.thecodingdad.gamelodge.stream.Quitting.hostKey(it.host) in detailQuitting } == true,
                                    profiles = detailProfiles, activeProfile = detailActive, onProfile = vm::setActiveProfile,
                                    onEditProfiles = { nav.navigate(Routes.settings(SettingsPageId.PROFILES)) },
                                    onBrowse = onBrowse,
                                    active = pageActive,
                                ) {
                                    io.github.thecodingdad.gamelodge.ui.screens.metadata.ReviewConfirmAction(e) {
                                        metaScope.launch { container.metadata.confirm(e.item.id) }
                                    }
                                }
                            }
                        }
                        }
                        val detailBg = LauncherTheme.colors.background
                        ShellTopBar(
                            selected = -1, onSelect = {}, hosts = detailHosts.values.sortedBy { it.name },
                            controllerCount = detailControllers.size, onHostInfo = { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, onControllers = { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) },
                            onClock = { vm.quickMenuTouch.tryEmit(Unit) },
                            onBack = { nav.popBackStack() },
                            modifier = Modifier.align(Alignment.TopCenter)
                                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(detailBg.copy(alpha = 0.85f), androidx.compose.ui.graphics.Color.Transparent)))
                                .onSizeChanged { detailBarHeight = with(detailDensity) { it.height.toDp() } },
                        )
                        }
                        }
                    }
                    composable(Routes.SETTINGS) { backStack ->
                        val page = SettingsPageId.valueOf(backStack.arguments?.read { getStringOrNull("page") } ?: SettingsPageId.ABOUT.name)
                        val crumbs = listOfNotNull(
                            tabCrumb(vm, nav, MainTab.SETTINGS),
                            if (page == SettingsPageId.LICENSES) pageCrumb(vm, nav, Res.string.settings_about, SettingsPageId.ABOUT) else null,
                        )
                        SubpageFrame(vm, nav, hints, MainTab.SETTINGS, crumbs, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) }) {
                            SettingsPageContent(
                                page, vm,
                                openReview = { nav.navigate(Routes.REVIEW) },
                                openProfile = { nav.navigate(Routes.profile(it)) },
                                openHost = { nav.navigate(Routes.host(it)) },
                                openPage = { nav.navigate(Routes.settings(it)) },
                                openStats = { nav.navigate(Routes.STATS) },
                            )
                        }
                    }
                    composable(Routes.HOST) { backStack ->
                        val uuid = backStack.arguments?.read { getStringOrNull("uuid") }.orEmpty()
                        val hosts by vm.hosts.collectAsState()
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS,
                            listOf(tabCrumb(vm, nav, MainTab.SETTINGS), pageCrumb(vm, nav, Res.string.settings_hosts, SettingsPageId.HOSTS)),
                            { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) },
                        ) {
                            val consoles by io.github.thecodingdad.gamelodge.LocalContainer.current.xbox.hosts.collectAsState()
                            val host = hosts[uuid] ?: io.github.thecodingdad.gamelodge.xbox.XboxRepository.consoleId(uuid)?.let { id ->
                                consoles[id]?.asHostState()
                            }
                            host?.let { HostConfigScreen(it, openHaSettings = { nav.navigate(Routes.settings(SettingsPageId.HOME_ASSISTANT)) }) } }
                    }
                    composable(Routes.WIZARD) { backStack ->
                        val manual = backStack.arguments?.read { getStringOrNull("manual") } == "true"
                        io.github.thecodingdad.gamelodge.ui.screens.wizard.SetupWizard(vm, manual) {
                            // Done: the start tab (from the settings: back to them).
                            if (manual) nav.popBackStack() else nav.popBackStack(Routes.MAIN, inclusive = false)
                        }
                    }
                    composable(Routes.CONTROLLERS) {
                        SubpageFrame(vm, nav, hints, MainTab.SETTINGS, listOf(tabCrumb(vm, nav, MainTab.SETTINGS)), { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) }) {
                            io.github.thecodingdad.gamelodge.ui.PlatformUi.current.ControllersScreen()
                        }
                    }
                    composable(Routes.CONTROLLER_SECTION) { backStack ->
                        val section = runCatching {
                            io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection.valueOf(backStack.arguments?.read { getStringOrNull("section") }.orEmpty())
                        }.getOrDefault(io.github.thecodingdad.gamelodge.ui.screens.settings.ControllerSection.MOUSE)
                        val controllersCrumb = io.github.thecodingdad.gamelodge.ui.screens.settings.Crumb(stringResource(Res.string.settings_controllers)) {
                            if (nav.previousBackStackEntry?.destination?.route == Routes.CONTROLLERS) nav.popBackStack()
                            else nav.navigate(Routes.CONTROLLERS) { popUpTo(Routes.MAIN) }
                        }
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS, listOf(tabCrumb(vm, nav, MainTab.SETTINGS), controllersCrumb),
                            { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) },
                        ) {
                            io.github.thecodingdad.gamelodge.ui.PlatformUi.current.ControllerSectionScreen(section)
                        }
                    }
                    composable(Routes.STATS) {
                        val all by vm.allEntries.collectAsState()
                        val sessions by vm.finishedSessions.collectAsState()
                        SubpageFrame(vm, nav, hints, MainTab.LIBRARY, listOf(tabCrumb(vm, nav, MainTab.LIBRARY)), { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) }) {
                            StatsScreen(all, sessions)
                        }
                    }
                    composable(Routes.PROFILE) { backStack ->
                        val raw = backStack.arguments?.read { getStringOrNull("uuid") }
                        val uuid = raw?.takeIf { it != "base" }?.let(java.util.UUID::fromString)
                        val profiles by vm.profiles.collectAsState()
                        val name = profiles.firstOrNull { it.uuid == uuid }?.name ?: stringResource(Res.string.profile_default)
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS,
                            listOf(tabCrumb(vm, nav, MainTab.SETTINGS), pageCrumb(vm, nav, Res.string.settings_profiles, SettingsPageId.PROFILES)),
                            { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) },
                        ) { io.github.thecodingdad.gamelodge.ui.PlatformUi.current.ProfileEditor(uuid, name, onDeleted = { nav.popBackStack() }) }
                    }
                    composable(Routes.REVIEW) {
                        val all by vm.allEntries.collectAsState()
                        val review = remember(all) { all.filter { it.item.metadataState == MetadataState.REVIEW } }
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS,
                            listOf(tabCrumb(vm, nav, MainTab.SETTINGS), pageCrumb(vm, nav, Res.string.settings_metadata, SettingsPageId.METADATA)),
                            { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.HOSTS) }, { openQuickMenu(io.github.thecodingdad.gamelodge.ui.shell.QuickMenuTab.CONTROLLERS) },
                        ) { ReviewScreen(review) }
                    }
                }
}

                // Global overlays, above every route.
                val profiles by vm.profiles.collectAsState()
                val activeProfile by vm.activeProfile.collectAsState()
                TargetPickerDialog(
                    pick = entryActions.pick,
                    profiles = profiles,
                    activeProfile = activeProfile,
                    onDismiss = { entryActions.pick = null },
                    onLaunch = { entry, target, profile, emulatorKey, action ->
                        entryActions.pick = null
                        vm.launch(entry, target, profile, emulatorKey = emulatorKey, action = action)
                    },
                )
                val runningForMenu by vm.runningSessions.collectAsState()
                EntryContextMenu(
                    entry = entryActions.contextMenuFor,
                    // A game running on a host gets the stream options, wherever its menu is opened.
                    session = entryActions.contextSession ?: entryActions.contextMenuFor?.let { e ->
                        runningForMenu.firstOrNull { it.entry?.item?.id == e.item.id }
                    },
                    onResume = { session ->
                        val entry = session.entry
                        val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                        if (entry != null && target != null) vm.launch(entry, target)
                    },
                    onDismiss = entryActions::closeContext,
                    onPlay = entryActions::play,
                    onPlayWith = entryActions::playWith,
                    onDetails = { e ->
                        // Apps have no detail page: Android's app info instead.
                        val pkg = e.localTarget?.packageName
                        if (e.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP && pkg != null) {
                            io.github.thecodingdad.gamelodge.ui.PlatformUi.current.openAppInfo(pkg)
                        } else entryActions.showDetail(e)
                    },
                    onFavorite = vm::setFavorite,
                    onKind = vm::setKind,
                    onHide = { e ->
                        when {
                            e.item.hidden -> vm.setHidden(e, false)
                            vm.sharesPlayData(e) -> hideChoiceFor = e
                            else -> vm.setHidden(e, true)
                        }
                    },
                    onCollections = { collectionsFor = it },
                    onEditMetadata = { metaEditorFor = it.item.id },
                    onAppArtwork = { appTileFor = it.item.id },
                    onEmulator = { emulatorFor = it.item.id },
                    onLaunchSettings = { launchSettingsFor = it.item.id },
                    onGameActions = { gameActionsFor = it.item.id },
                    inDetail = detailOpenFor == entryActions.contextMenuFor?.item?.id,
                )
                // Metadata editor with its match and artwork dialogs, reachable from every menu.
                val metaLibrary by vm.allEntries.collectAsState()
                io.github.thecodingdad.gamelodge.ui.PlatformUi.current.LaunchSettingsDialog(
                    launchSettingsFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } },
                ) { launchSettingsFor = null }
                io.github.thecodingdad.gamelodge.ui.screens.ha.GameActionsDialog(
                    gameActionsFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } },
                ) { gameActionsFor = null }
                io.github.thecodingdad.gamelodge.ui.shell.GameEmulatorDialog(
                    entry = emulatorFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } },
                    onDismiss = { emulatorFor = null },
                    onChoose = { target, key -> vm.setGameEmulator(target.id, key); emulatorFor = null },
                )
                val metaEntry = metaEditorFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } }
                io.github.thecodingdad.gamelodge.ui.screens.metadata.MetadataEditorDialog(
                    entry = metaEntry, library = metaLibrary,
                    onPlatform = { platformFor = it }, onDismiss = { metaEditorFor = null },
                    onFix = { fixingFor = it.item.id }, onArtwork = { artworkFor = it.item.id },
                )
                FixMetadataDialog(fixingFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id }?.item }) { fixingFor = null }
                artworkFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } }.let { e -> ArtworkPickerDialog(e?.item, e?.targets.orEmpty()) { artworkFor = null } }
                io.github.thecodingdad.gamelodge.ui.screens.metadata.AppTileDialog(appTileFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } }) { appTileFor = null }
                val allForPlatforms by vm.allEntries.collectAsState()
                io.github.thecodingdad.gamelodge.ui.shell.PlatformDialog(
                    entry = platformFor,
                    knownPlatforms = remember(allForPlatforms) { allForPlatforms.mapNotNull { it.item.platform }.distinct().sorted() },
                    onSelect = vm::setPlatform,
                    onDismiss = { platformFor = null },
                )
                val collections by vm.collections.collectAsState()
                val allFilters by vm.filters.collectAsState()
                // Apps only see app groups, games only game groups.
                val groupScope = if (collectionsFor?.item?.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.APP) {
                    io.github.thecodingdad.gamelodge.data.db.FilterScope.APPS
                } else io.github.thecodingdad.gamelodge.data.db.FilterScope.GAMES
                val scopedGroupIds = allFilters.filter {
                    (it.scope == io.github.thecodingdad.gamelodge.data.db.FilterScope.APPS) ==
                        (groupScope == io.github.thecodingdad.gamelodge.data.db.FilterScope.APPS)
                }.map { it.id }.toSet()
                CollectionsDialog(
                    entry = collectionsFor,
                    collections = collections.filter { it.first.id in scopedGroupIds },
                    onToggle = { id, member -> collectionsFor?.let { vm.setInCollection(it.item.id, id, member) } },
                    onCreate = { name -> vm.createGroup(name, groupScope, collectionsFor?.item?.id) },
                    onDismiss = { collectionsFor = null },
                )
                io.github.thecodingdad.gamelodge.ui.shell.HideChoiceDialog(
                    entry = hideChoiceFor,
                    onChoice = { here -> hideChoiceFor?.let { vm.setHidden(it, true, here) }; hideChoiceFor = null },
                    onDismiss = { hideChoiceFor = null },
                )
                val launchState by vm.launchState.collectAsState()
                val launchKind = launchState::class
                LaunchedEffect(launchKind) {
                    when (launchState) {
                        is io.github.thecodingdad.gamelodge.stream.LaunchState.Working -> feedback.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.LAUNCH)
                        is io.github.thecodingdad.gamelodge.stream.LaunchState.Failed -> feedback.play(io.github.thecodingdad.gamelodge.ui.feedback.UiSound.ERROR)
                        else -> Unit
                    }
                }
                LaunchOverlay(
                    state = launchState,
                    onDismiss = vm::dismissLaunch,
                    onRetry = { entry -> vm.dismissLaunch(); entryActions.play(entry) },
                    onConfirmQuit = { c -> vm.launch(c.entry, c.target, c.override, quitRunning = true) },
                    profiles = profiles,
                    onContinueDespiteNetwork = { w, profile -> vm.launch(w.entry, w.target, profile, skipNetworkCheck = true) },
                    onCancel = vm::cancelLaunch,
                )
                val running by vm.runningSessions.collectAsState()
                QuickMenu(
                    visible = quickMenu,
                    onDismiss = { quickMenu = false; quickMenuTab = null },
                    running = running,
                    requestedTab = quickMenuTab,
                    onResume = { session ->
                        val entry = session.entry
                        val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                        if (entry != null && target != null) vm.launch(entry, target)
                    },
                    openHostSettings = { quickMenu = false; nav.navigate(Routes.host(it.uuid)) },
                    openControllerSettings = { quickMenu = false; nav.navigate(Routes.CONTROLLERS) },
                    openHaSettings = { quickMenu = false; nav.navigate(Routes.settings(SettingsPageId.HOME_ASSISTANT)) },
                )
        io.github.thecodingdad.gamelodge.ui.shell.TasksPopup()
                TvSlowHint(vm)
                TvModeHint(vm)
                ControllerKeyboardHost(keyboard)
                // Every popup and panel is drawn here, above the (blurred) launcher.
                io.github.thecodingdad.gamelodge.ui.components.OverlayOutlet()
                io.github.thecodingdad.gamelodge.ui.shell.NoticeHost()

                val keepScreenOn = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.rememberScreenKeeper()
                // The system's own screen timeout would switch the display off before the screensaver:
                // keep it on while the launcher is visible, and let it sleep 10 min into the screensaver.
                LaunchedEffect(idle, settings.screensaverMinutes) {
                    keepScreenOn(settings.screensaverMinutes > 0)
                    if (idle) {
                        kotlinx.coroutines.delay(10 * 60_000L)
                        keepScreenOn(false)
                    }
                }
                LaunchedEffect(idle) { io.github.thecodingdad.gamelodge.ui.shell.ScreensaverControl.active.value = idle }
                if (idle) {
                    val games by vm.games.collectAsState()
                    io.github.thecodingdad.gamelodge.ui.shell.Screensaver(games, settings.screensaverKenBurns) {
                        if (System.currentTimeMillis() >= shownByHandUntil) { idle = false; vm.touch() }
                    }
                }
            }
            }
            }
        }
    }
}

/** Route frame with the shared hint bar (detail and settings pages). */
@Composable
private fun ShellFrame(hints: HintHolder, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        ButtonHintBar(
            hints.hints.ifEmpty {
                    listOf(
                        ButtonHint(PadButton.A, stringResource(Res.string.hint_select)),
                        ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
                        ButtonHint(PadButton.L1, stringResource(Res.string.hint_tabs), secondButton = PadButton.R1),
                    )
                },
            hideWhenCompact = true,
        )
    }
}

@Composable
private fun MainShell(
    vm: LauncherViewModel,
    nav: NavHostController,
    openStats: () -> Unit,
    onHostInfo: (String) -> Unit,
    onControllers: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val hostsMap by vm.hosts.collectAsState()
    val games by vm.games.collectAsState()
    val apps by vm.apps.collectAsState()
    val allEntries by vm.allEntries.collectAsState()
    val running by vm.runningSessions.collectAsState()
    val activeProfile by vm.activeProfile.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val hints = LocalHintHolder.current
    val entryActions = LocalEntryActions.current
    var tab by vm.tabProperty
    val tabs = MainTab.entries
    // A on a game: details or start right away, as set for the tab it is on.
    entryActions.directStart = when (tabs.getOrNull(tab)) {
        MainTab.HOME -> settings.homeDirectStart
        MainTab.LIBRARY -> settings.libraryDirectStart
        else -> false
    }
    val hosts = hostsMap.values.sortedBy { it.name }
    val libraryFilters by vm.libraryFilters.collectAsState()
    val homeFilters by vm.homeFilters.collectAsState()
    val appsFilters by vm.appsFilters.collectAsState()
    // Keeps each tab's saved state (filter, sort, scroll position) while another tab is shown
    // or a detail page is open.
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val controllers = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers()

    val hidden = LocalHiddenTabs.current
    PadAction(PadButton.L1) { tab = stepTab(tab, -1, hidden) }
    PadAction(PadButton.R1) { tab = stepTab(tab, 1, hidden) }
    // A tab that went away (the last app removed, or the start tab while there are no apps): home.
    LaunchedEffect(hidden, tab) { if (MainTab.entries[tab] in hidden) tab = MainTab.HOME.ordinal }
    // B: library/apps move focus to their filters (handled there); settings ignore it; home only
    // swallows it in launcher mode (as a normal app, B on home leaves the app).
    BackHandler(enabled = tabs[tab] == MainTab.SETTINGS || (tabs[tab] == MainTab.HOME && settings.launcherMode)) {}

    fun cycleProfile() {
        if (profiles.isEmpty()) return
        val i = profiles.indexOfFirst { it.uuid == activeProfile.uuid }
        vm.setActiveProfile(profiles[(i + 1).mod(profiles.size)])
    }

    // Home layouts with a backdrop image draw behind the top and hint bars, which fade out
    // with a gradient instead of hard edges; all other screens sit between solid bars.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var hintBarHeight by remember { mutableStateOf(0.dp) }
    val compactShell = io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current
    val showHints = io.github.thecodingdad.gamelodge.ui.components.LocalShowHintBar.current
    // A hidden hint bar takes no space; its last measured height must not linger.
    LaunchedEffect(showHints, compactShell) { if (!showHints && !compactShell) hintBarHeight = 0.dp }
    // The start tab keeps the hint bar's room even while the hints are hidden (the bar is
    // transparent there): its rows stay where they are with and without a controller.
    var hiddenHintSpace by remember { mutableStateOf(0.dp) }
    val homeBottomInset = if (!showHints && !compactShell) hiddenHintSpace else hintBarHeight
    val fullBleed = tabs[tab] == MainTab.HOME
    val bg = LauncherTheme.colors.background
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxSize()) {
          CompositionLocalProvider(
              io.github.thecodingdad.gamelodge.ui.screens.home.LocalShellInsets provides
                  androidx.compose.foundation.layout.PaddingValues(top = topBarHeight, bottom = homeBottomInset),
          ) {
            AnimatedContent(tabs[tab], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
              // The tab fading out no longer provides the hints.
              CompositionLocalProvider(io.github.thecodingdad.gamelodge.ui.shell.LocalHintsActive provides (transition.targetState == androidx.compose.animation.EnterExitState.Visible)) {
                // The start tab's saved state is keyed by the reset counter, so Home starts fresh.
                val stateKey = if (current == MainTab.HOME) "HOME-${vm.homeResets.intValue}" else current.name
                tabStates.SaveableStateProvider(stateKey) {
                // Each tab applies the bar insets itself (full-bleed home draws behind them), so the
                // outgoing and incoming tab keep their own layout during the cross-fade.
                val tabFullBleed = current == MainTab.HOME
                Box(Modifier.fillMaxSize().then(if (tabFullBleed) Modifier else Modifier.padding(top = topBarHeight, bottom = hintBarHeight))) {
                // Each tab its own card size.
                val tabCards = when (current) {
                    MainTab.LIBRARY -> settings.libraryLayout.cardSize
                    MainTab.APPS -> settings.appsLayout.cardSize
                    else -> settings.homeLayout.cardSize
                }
                // The start tab keeps a full row of cards below its info block: large cards are
                // limited to what the screen's height allows (the interface itself keeps its size).
                val tabWindow = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
                val tabHeightDp = tabWindow.height / androidx.compose.ui.platform.LocalDensity.current.density
                val tabDimens = io.github.thecodingdad.gamelodge.ui.theme.dimensFor(tabCards).let { d ->
                    val fit = ((tabHeightDp - 255f) / 1.5f).dp
                    val medium = io.github.thecodingdad.gamelodge.ui.theme.dimensFor(io.github.thecodingdad.gamelodge.data.settings.CardSize.MEDIUM).coverWidth
                    if (current == MainTab.HOME && tabWindow.height > 0 && tabWindow.width > tabWindow.height && d.coverWidth > fit) d.copy(coverWidth = maxOf(fit, medium)) else d
                }
                CompositionLocalProvider(io.github.thecodingdad.gamelodge.ui.theme.LocalLauncherDimens provides tabDimens) {
                when (current) {
                    MainTab.HOME -> {
                        val rows = remember(games, running) { HomeRows.from(games, running) }
                        // Seed from the remembered (or first) entry so the hints show the focused
                        // state (A label, quit hint) from the first frame instead of reflowing when
                        // a card claims focus a frame later (the A hint would visibly jump left).
                        var homeFocused by remember {
                            val id = vm.focusKeys[MainTab.HOME.name]?.substringAfterLast(':')
                            val pool = rows.continuePlaying + rows.favorites + rows.recentlyAdded
                            mutableStateOf(pool.firstOrNull { it.item.id == id } ?: pool.firstOrNull())
                        }
                        val callbacks = HomeCallbacks(
                            onResume = { session ->
                                val entry = session.entry
                                val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                                if (entry != null && target != null) vm.launch(entry, target)
                            },
                            onRunningMenu = entryActions::contextForSession,
                            lastFocusedKey = vm.focusKeys[MainTab.HOME.name],
                            onFocusKey = { vm.focusKeys[MainTab.HOME.name] = it },
                            onAtStart = { vm.homeAtStart = it },
                            onFocusedEntry = { homeFocused = it },
                            animatedTiles = settings.animatedTiles,
                            animatedHero = settings.animatedHero,
                            layout = settings.homeLayout,
                        )
                        val quitHint = io.github.thecodingdad.gamelodge.ui.components.quitHoldHint(homeFocused)
                        val haTile = io.github.thecodingdad.gamelodge.ui.screens.ha.LocalHaTileFocus.current?.tile
                        io.github.thecodingdad.gamelodge.ui.shell.ScreenHints(
                            if (haTile != null) {
                                io.github.thecodingdad.gamelodge.ui.screens.ha.haTileHints(haTile) +
                                    ButtonHint(PadButton.L1, stringResource(Res.string.hint_tabs), secondButton = PadButton.R1)
                            } else listOfNotNull(
                                ButtonHint(PadButton.A, stringResource(entryActions.openLabel(homeFocused))),
                                ButtonHint(PadButton.X, stringResource(Res.string.hint_options)),
                                quitHint,
                                ButtonHint(PadButton.L1, stringResource(Res.string.hint_tabs), secondButton = PadButton.R1),
                            ),
                        )
                        // "Empty" only for a library without any entry (not while rows are still being put together).
                        val empty: @Composable () -> Unit = { if (allEntries.isEmpty()) io.github.thecodingdad.gamelodge.ui.screens.library.EmptyLibrary() }
                        HeroHome(
                            rows, callbacks, homeFilters,
                            haRow = if (settings.showHaRowOnHome && io.github.thecodingdad.gamelodge.LocalContainer.current.ha.auth.isLoggedIn) ({ HaHomeRow() }) else null,
                            emptyContent = empty,
                            haIndex = io.github.thecodingdad.gamelodge.data.library.HomeRowOrder.haIndex(homeFilters, settings.homeRowOrder),
                            rowsTop = settings.homeRowsTop,
                        )
                    }
                    MainTab.LIBRARY -> LibraryScreen(
                        allEntries, libraryFilters, settings.animatedTiles, vm.focusKeys[MainTab.LIBRARY.name],
                        openFilterSettings = { nav.navigate(Routes.settings(SettingsPageId.FILTERS)) },
                        layout = settings.libraryLayout,
                    ) { vm.focusKeys[MainTab.LIBRARY.name] = it }
                    MainTab.APPS -> AppsScreen(
                        apps, appsFilters, vm.focusKeys[MainTab.APPS.name],
                        openFilterSettings = { nav.navigate(Routes.settings(SettingsPageId.APP_FILTERS)) },
                        layout = settings.appsLayout,
                    ) { vm.focusKeys[MainTab.APPS.name] = it }
                    MainTab.SETTINGS -> {
                        io.github.thecodingdad.gamelodge.ui.shell.ScreenHints(
                            listOf(
                                ButtonHint(PadButton.A, stringResource(Res.string.hint_select)),
                                // Start: search the settings with the on-screen keyboard.
                                ButtonHint(PadButton.START, stringResource(Res.string.hint_search)),
                                ButtonHint(PadButton.L1, stringResource(Res.string.hint_tabs), secondButton = PadButton.R1),
                            ),
                        )
                        SettingsHome(open = { nav.navigate(Routes.settings(it)) }, isFirstVisit = true, openControllers = { nav.navigate(Routes.CONTROLLERS) }, openWizard = { nav.navigate(Routes.wizard(manual = true)) }, openStats = { nav.navigate(Routes.STATS) }, query = vm.settingsQuery.value, onQueryChange = { vm.settingsQuery.value = it })
                    }
                }
                }
                }
                }
            }
}
          }
        }
        ShellTopBar(
            selected = tab, onSelect = { tab = it }, hosts = hosts, controllerCount = controllers.size,
            onHostInfo = onHostInfo, onControllers = onControllers, onClock = { vm.quickMenuTouch.tryEmit(Unit) },
            modifier = Modifier.align(Alignment.TopCenter)
                .background(
                    if (fullBleed) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0.85f), androidx.compose.ui.graphics.Color.Transparent))
                    else androidx.compose.ui.graphics.SolidColor(bg),
                )
                .onSizeChanged { topBarHeight = with(density) { it.height.toDp() } },
        )
        if (!showHints && !compactShell) {
            // Invisible stand-in, only measured (no clickable hints).
            CompositionLocalProvider(io.github.thecodingdad.gamelodge.ui.components.LocalShowHintBar provides true) {
                ButtonHintBar(
                    hints.hints.map { it.copy(onClick = null) },
                    modifier = Modifier.align(Alignment.BottomCenter).alpha(0f)
                        .onSizeChanged { hiddenHintSpace = with(density) { it.height.toDp() } },
                )
            }
        }
        ButtonHintBar(
            hints.hints,
            hideWhenCompact = true,
            modifier = Modifier.align(Alignment.BottomCenter)
                .background(
                    if (fullBleed) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, bg.copy(alpha = 0.7f)))
                    else androidx.compose.ui.graphics.SolidColor(bg),
                )
                .onSizeChanged { hintBarHeight = with(density) { it.height.toDp() } },
        )
        if (compactShell) {
            // Portrait: main tabs as a bottom navigation bar (icon + label); takes the hint bar's place.
            BottomNavBar(
                tabs = tabs, selected = tab, onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .onSizeChanged { hintBarHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

/** Tabs and status cluster: the top bar of the main screen and of its sub pages. */
@Composable
private fun ShellTopBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    hosts: List<io.github.thecodingdad.gamelodge.data.remote.HostState>,
    controllerCount: Int,
    onHostInfo: (String) -> Unit,
    onControllers: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tap on the clock opens the quick menu. */
    onClock: () -> Unit = {},
    /** Detail page: a back button (touch/mouse) instead of the tabs. */
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            // Constant height for every tab: a changing bar height would shift all content.
            .padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val compact = io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current
        // Camera hole: in the middle the status splits around it, on the right it moves left.
        val cutout = io.github.thecodingdad.gamelodge.ui.shell.rememberTopCutout()
        val endShift = if (cutout.position == io.github.thecodingdad.gamelodge.ui.shell.TopCutout.Position.RIGHT) {
            (cutout.rightInset + 8.dp - 28.dp).coerceAtLeast(0.dp)
        } else 0.dp
        val status: @Composable (Boolean, Boolean) -> Unit = { split, batteryOnly ->
            StatusCluster(
                hosts,
                modifier = Modifier.padding(end = endShift),
                onHostClick = { onHostInfo(it.uuid) },
                onHostsClick = { onHostInfo("") },
                controllerCount = controllerCount,
                onControllerClick = onControllers,
                onClockClick = onClock,
                split = split,
                batteryAndClockOnly = batteryOnly,
            )
        }
        val centreHole = cutout.position == io.github.thecodingdad.gamelodge.ui.shell.TopCutout.Position.CENTER
        if (onBack != null) {
            // Landscape: the tabs set the bar's height on the main screens; an invisible stand-in
            // (height only, no width, not placed) keeps it identical here.
            if (!compact) {
                Box(
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
                        layout(0, placeable.height) {}
                    },
                ) { TabChip(stringResource(MainTab.HOME.title), selected = false, onClick = {}) }
            }
            BackButton(onBack)
            // The back button takes the left half: with a centre hole only battery and clock fit.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { status(false, centreHole) }
        } else if (compact) {
            // Narrow screen: only the status at the top; the tabs move to the bottom navigation bar.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { status(centreHole, false) }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                val hidden = LocalHiddenTabs.current
                MainTab.entries.forEachIndexed { index, t ->
                    if (t !in hidden) TabChip(stringResource(t.title), selected = index == selected, onClick = { onSelect(index) })
                }
            }
            status(false, false)
        }
    }
}

/** Round back arrow for touch and mouse; controllers go back with B, so it takes no D-pad focus. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = LauncherTheme.colors.surface.copy(alpha = 0.7f),
        dpadFocusable = false,
        clickSound = io.github.thecodingdad.gamelodge.ui.feedback.UiSound.BACK,
        modifier = Modifier
            // Adds no height to the bar (it stays as high as on the main tabs, the status icons at
            // the same height): measured freely, centred on the bar's middle line.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
            }
            .padding(end = 12.dp).size(44.dp),
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.hint_back),
            tint = LauncherTheme.colors.onSurface, modifier = Modifier.align(Alignment.Center).size(24.dp),
        )
    }
}

/** Leaves any sub page for a main tab. */
private fun openMainTab(vm: LauncherViewModel, nav: NavHostController, tab: MainTab) {
    vm.tab = tab.ordinal
    nav.popBackStack(Routes.MAIN, inclusive = false)
}

/** Path step to a main tab. */
@Composable
private fun tabCrumb(vm: LauncherViewModel, nav: NavHostController, tab: MainTab) =
    io.github.thecodingdad.gamelodge.ui.screens.settings.Crumb(stringResource(tab.title)) { openMainTab(vm, nav, tab) }

/** Path step back to the page this one was opened from. */
@Composable
private fun pageCrumb(
    vm: LauncherViewModel,
    nav: NavHostController,
    label: org.jetbrains.compose.resources.StringResource,
    page: SettingsPageId,
) = io.github.thecodingdad.gamelodge.ui.screens.settings.Crumb(stringResource(label)) {
    val previous = nav.previousBackStackEntry
    val previousPage = previous?.arguments?.read { getStringOrNull("page") }
    if (previous?.destination?.route == Routes.SETTINGS && previousPage == page.name) {
        nav.popBackStack()
    } else {
        // Opened from elsewhere (e.g. the host popup): to that settings page, which replaces this one.
        vm.tab = MainTab.SETTINGS.ordinal
        val current = nav.currentBackStackEntry?.destination?.route
        nav.navigate(Routes.settings(page)) { current?.let { popUpTo(it) { inclusive = true } } }
    }
}

/**
 * Frame of a page opened from a main tab (settings pages, statistics): the main screen's top
 * bar with [tab] selected, the page with its path ([parents] › title) and the hint bar.
 * Choosing a tab leaves the page.
 */
@Composable
private fun SubpageFrame(
    vm: LauncherViewModel,
    nav: NavHostController,
    hints: HintHolder,
    tab: MainTab,
    parents: List<io.github.thecodingdad.gamelodge.ui.screens.settings.Crumb>,
    onHostInfo: (String) -> Unit,
    onControllers: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hostsMap by vm.hosts.collectAsState()
    val controllers = io.github.thecodingdad.gamelodge.ui.screens.settings.rememberControllers()
    val compact = io.github.thecodingdad.gamelodge.ui.components.LocalCompactWidth.current
    fun openTab(index: Int) = openMainTab(vm, nav, MainTab.entries[index])
    val hidden = LocalHiddenTabs.current
    PadAction(PadButton.L1) { openTab(stepTab(tab.ordinal, -1, hidden)) }
    PadAction(PadButton.R1) { openTab(stepTab(tab.ordinal, 1, hidden)) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShellTopBar(
            selected = tab.ordinal, onSelect = ::openTab, hosts = hostsMap.values.sortedBy { it.name },
            controllerCount = controllers.size, onHostInfo = onHostInfo, onControllers = onControllers,
            onClock = { vm.quickMenuTouch.tryEmit(Unit) },
            modifier = Modifier.background(LauncherTheme.colors.background),
        )
        CompositionLocalProvider(
            io.github.thecodingdad.gamelodge.ui.screens.settings.LocalPagePath provides
                io.github.thecodingdad.gamelodge.ui.screens.settings.PagePath(parents) { nav.popBackStack() },
        ) {
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
        if (compact) {
            BottomNavBar(tabs = MainTab.entries, selected = tab.ordinal, onSelect = ::openTab)
        } else {
            // LB / RB switch the main tabs on every sub page: always listed, last, also next to the page's own hints.
            val tabsHint = ButtonHint(PadButton.L1, stringResource(Res.string.hint_tabs), secondButton = PadButton.R1)
            ButtonHintBar(
                hints.hints.ifEmpty {
                    listOf(
                        ButtonHint(PadButton.A, stringResource(Res.string.hint_select)),
                        ButtonHint(PadButton.B, stringResource(Res.string.hint_back)),
                    )
                }.let { list -> if (list.any { it.button == PadButton.L1 }) list else list + tabsHint },
                hideWhenCompact = true,
            )
        }
    }
}

@Composable
private fun BottomNavBar(tabs: List<MainTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = LauncherTheme.colors
    Row(
        modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        val hidden = LocalHiddenTabs.current
        tabs.forEachIndexed { index, t ->
            if (t in hidden) return@forEachIndexed
            val active = index == selected
            FocusSurface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(16.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                focusScale = 1f,
                dpadFocusable = false,
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (active) colors.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            t.icon, null, tint = if (active) colors.background else colors.onSurfaceMuted,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        stringResource(t.title), style = MaterialTheme.typography.labelMedium,
                        color = if (active) colors.onSurface else colors.onSurfaceMuted,
                        maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LauncherTheme.colors
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.onSurface else androidx.compose.ui.graphics.Color.Transparent,
        focusScale = 1.05f,
        dpadFocusable = false,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.background else colors.onSurfaceMuted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SettingsPageContent(
    page: SettingsPageId,
    vm: LauncherViewModel,
    openReview: () -> Unit,
    openProfile: (java.util.UUID?) -> Unit,
    openHost: (String) -> Unit,
    openPage: (SettingsPageId) -> Unit,
    openStats: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val all by vm.allEntries.collectAsState()
    when (page) {
        SettingsPageId.METADATA -> MetadataSettings(
            settings, vm::updateSettings,
            reviewCount = all.count { it.item.metadataState == MetadataState.REVIEW },
            openReview = openReview,
            games = all.count { it.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.GAME },
            gamesWithMetadata = all.count {
                it.item.kind == io.github.thecodingdad.gamelodge.data.db.ItemKind.GAME &&
                    it.item.metadataState in setOf(MetadataState.OK, MetadataState.LOCKED, MetadataState.REVIEW)
            },
        )
        SettingsPageId.HOSTS -> HostsSettings(hosts.values.toList(), openHostConfig = { openHost(it.uuid) })
        SettingsPageId.HOME_SCREEN -> io.github.thecodingdad.gamelodge.ui.screens.settings.HomeScreenSettings(settings, vm::updateSettings, vm)
        SettingsPageId.LIBRARY_VIEW -> io.github.thecodingdad.gamelodge.ui.screens.settings.LibraryViewSettings(settings, vm::updateSettings)
        SettingsPageId.APPS_VIEW -> io.github.thecodingdad.gamelodge.ui.screens.settings.AppsViewSettings(settings, vm::updateSettings)
        SettingsPageId.THEME -> io.github.thecodingdad.gamelodge.ui.screens.settings.ThemeSettings(settings, vm::updateSettings)
        SettingsPageId.DISPLAY -> {
            val external by vm.externalDisplay.collectAsState()
            io.github.thecodingdad.gamelodge.ui.screens.settings.DisplaySettings(settings, vm::updateSettings, external)
        }
        SettingsPageId.LANGUAGE -> LanguageSettings(settings, vm::updateSettings)
        SettingsPageId.FILTERS -> io.github.thecodingdad.gamelodge.ui.screens.settings.FilterSettings(vm, io.github.thecodingdad.gamelodge.data.db.FilterScope.GAMES)
        SettingsPageId.APP_FILTERS -> io.github.thecodingdad.gamelodge.ui.screens.settings.FilterSettings(vm, io.github.thecodingdad.gamelodge.data.db.FilterScope.APPS)
        SettingsPageId.SOUNDS -> io.github.thecodingdad.gamelodge.ui.screens.settings.SoundSettings(settings, vm::updateSettings)
        SettingsPageId.INPUT -> io.github.thecodingdad.gamelodge.ui.screens.settings.InputSettings(settings, vm::updateSettings)
        SettingsPageId.ABOUT -> AboutSettings(openLicenses = { openPage(SettingsPageId.LICENSES) })
        SettingsPageId.LICENSES -> io.github.thecodingdad.gamelodge.ui.screens.settings.OpenSourceLicenses()
        SettingsPageId.HOME_ASSISTANT -> HaSettings(settings, vm::updateSettings, hosts.values.filter { it.paired }.sortedBy { it.name }, openHost, openPage)
        SettingsPageId.DATA -> io.github.thecodingdad.gamelodge.ui.screens.settings.DataSettings(settings, vm::updateSettings, vm)
        // Pages of platform features (VPN, backup, emulators …).
        else -> if (io.github.thecodingdad.gamelodge.ui.PlatformUi.current.hasPage(page)) io.github.thecodingdad.gamelodge.ui.PlatformUi.current.PlatformSettingsPage(page, vm, openProfile = openProfile, openStats = openStats)
        else SettingsPage(stringResource(Res.string.coming_soon)) {}
    }
}

/** The TV stutters in the chosen mode: switch to automatic (smooth), not again, or later. */
@Composable
private fun TvSlowHint(vm: LauncherViewModel) {
    val shown = vm.tvSlowHint.value
    fun close() { vm.tvSlowHint.value = false }
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = shown, onDismiss = ::close, title = stringResource(Res.string.tv_slow_title),
        size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.COMPACT, closeButton = false,
    ) {
        Text(stringResource(Res.string.tv_slow_text), style = MaterialTheme.typography.bodyMedium, color = io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.onSurfaceMuted)
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow {
            io.github.thecodingdad.gamelodge.ui.components.LauncherButton(stringResource(Res.string.tv_slow_auto), primary = true, onClick = {
                vm.updateSettings { it.copy(tvMode = "") }; close()
            })
            io.github.thecodingdad.gamelodge.ui.components.LauncherButton(stringResource(Res.string.tv_slow_never), onClick = {
                vm.updateSettings { it.copy(tvSlowHintOff = true) }; close()
            })
        }
    }
}

/** The TV keeps another mode than wanted: says so, with the device's own setting where it has one. */
@Composable
private fun TvModeHint(vm: LauncherViewModel) {
    val hint = vm.tvModeHint.value
    fun close() { vm.tvModeHint.value = null }
    val openOutput = io.github.thecodingdad.gamelodge.ui.PlatformUi.current.openTvOutputSettings
    io.github.thecodingdad.gamelodge.ui.components.Modal(
        visible = hint != null, onDismiss = ::close, title = stringResource(Res.string.tv_mode_hint_title),
        size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.COMPACT, closeButton = false,
    ) {
        val (wanted, running) = hint ?: ("" to "")
        Text(stringResource(Res.string.tv_mode_hint_text, wanted, running), style = MaterialTheme.typography.bodyMedium, color = io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.onSurfaceMuted)
        if (openOutput != null) {
            Text(stringResource(Res.string.tv_mode_hint_odin), style = MaterialTheme.typography.bodyMedium, color = io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme.colors.onSurface)
        }
        io.github.thecodingdad.gamelodge.ui.components.ButtonRow {
            if (openOutput != null) {
                io.github.thecodingdad.gamelodge.ui.components.LauncherButton(stringResource(Res.string.tv_mode_hint_open), primary = true, onClick = { close(); openOutput() })
            }
            io.github.thecodingdad.gamelodge.ui.components.LauncherButton(stringResource(Res.string.action_close), primary = openOutput == null, onClick = ::close)
            io.github.thecodingdad.gamelodge.ui.components.LauncherButton(stringResource(Res.string.tv_slow_never), onClick = {
                vm.updateSettings { it.copy(tvModeHintOff = true) }; close()
            })
        }
    }
}
