package io.github.thecodingdad.moonlightlauncher.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.thecodingdad.moonlightlauncher.R
import io.github.thecodingdad.moonlightlauncher.container
import io.github.thecodingdad.moonlightlauncher.data.db.MetadataState
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.ArtworkPickerDialog
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.FixMetadataDialog
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.MetadataActions
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.MetadataSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.ReviewScreen
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import io.github.thecodingdad.moonlightlauncher.data.settings.HomeLayout
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHint
import io.github.thecodingdad.moonlightlauncher.ui.components.ButtonHintBar
import io.github.thecodingdad.moonlightlauncher.ui.components.ControllerKeyboardHost
import io.github.thecodingdad.moonlightlauncher.ui.components.FocusSurface
import io.github.thecodingdad.moonlightlauncher.ui.components.KeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalButtonLayout
import io.github.thecodingdad.moonlightlauncher.ui.components.LocalKeyboardController
import io.github.thecodingdad.moonlightlauncher.ui.components.restoreFocusIf
import io.github.thecodingdad.moonlightlauncher.ui.feedback.LocalUiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.feedback.UiFeedback
import io.github.thecodingdad.moonlightlauncher.ui.input.InputDispatcher
import io.github.thecodingdad.moonlightlauncher.ui.input.LocalInputDispatcher
import io.github.thecodingdad.moonlightlauncher.ui.input.PadAction
import io.github.thecodingdad.moonlightlauncher.ui.input.PadButton
import io.github.thecodingdad.moonlightlauncher.ui.screens.apps.AppsScreen
import io.github.thecodingdad.moonlightlauncher.ui.screens.detail.DetailScreen
import io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaHomeRow
import io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaPanel
import io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.CarouselHome
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.GridHome
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.HeroHome
import io.github.thecodingdad.moonlightlauncher.ui.screens.profiles.ProfileEditor
import io.github.thecodingdad.moonlightlauncher.ui.screens.profiles.ProfilesList
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ControllersScreen
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.HostConfigScreen
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.LauncherModeSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.stats.StatsScreen
import io.github.thecodingdad.moonlightlauncher.ui.shell.CollectionsDialog
import io.github.thecodingdad.moonlightlauncher.ui.shell.QuickMenu
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.HomeCallbacks
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.HomeRows
import io.github.thecodingdad.moonlightlauncher.ui.screens.library.LibraryScreen
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.AboutSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.AppearanceSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ControlsSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.HostsSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.LanguageSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsHome
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPage
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SettingsPageId
import io.github.thecodingdad.moonlightlauncher.ui.shell.EntryActions
import io.github.thecodingdad.moonlightlauncher.ui.shell.EntryContextMenu
import io.github.thecodingdad.moonlightlauncher.ui.shell.HintHolder
import io.github.thecodingdad.moonlightlauncher.ui.shell.LaunchOverlay
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalEntryActions
import io.github.thecodingdad.moonlightlauncher.ui.shell.LocalHintHolder
import io.github.thecodingdad.moonlightlauncher.ui.shell.StatusCluster
import io.github.thecodingdad.moonlightlauncher.ui.shell.TargetPickerDialog
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme

/** Maps the stored settings to the feedback engine's configuration. */
fun io.github.thecodingdad.moonlightlauncher.data.settings.LauncherSettings.feedbackConfig() =
    io.github.thecodingdad.moonlightlauncher.ui.feedback.FeedbackConfig(
        soundsEnabled = uiSounds,
        volume = uiSoundVolume / 100f,
        style = runCatching { io.github.thecodingdad.moonlightlauncher.ui.feedback.SoundStyle.valueOf(soundStyle) }
            .getOrDefault(io.github.thecodingdad.moonlightlauncher.ui.feedback.SoundStyle.SOFT),
        pitch = soundPitch,
        mutedSounds = mutedSounds.mapNotNull { name ->
            io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound.entries.firstOrNull { it.name == name }
        }.toSet(),
        hapticsEnabled = haptics,
        hapticStrength = runCatching { io.github.thecodingdad.moonlightlauncher.ui.feedback.HapticStrength.valueOf(hapticStrength) }
            .getOrDefault(io.github.thecodingdad.moonlightlauncher.ui.feedback.HapticStrength.MEDIUM),
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
    fun host(uuid: String) = "host/$uuid"
    const val PROFILE = "profile/{uuid}"
    fun profile(uuid: java.util.UUID?) = "profile/${uuid ?: "base"}"
    fun detail(id: String) = "detail/$id"
    fun settings(page: SettingsPageId) = "settings/${page.name}"
}

enum class MainTab(val title: Int) { HOME(R.string.tab_home), LIBRARY(R.string.tab_library), APPS(R.string.tab_apps), SETTINGS(R.string.tab_settings) }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LauncherApp(vm: LauncherViewModel, dispatcher: InputDispatcher, feedback: UiFeedback) {
    val settings by vm.settings.collectAsState()
    val nav = rememberNavController()
    val keyboard = remember { KeyboardController() }
    val hints = remember { HintHolder() }
    val activity = LocalActivity.current!!

    LaunchedEffect(settings) { feedback.configure(settings.feedbackConfig()) }
    // First controller input after touching: continue from the touched element.
    val controllerMode = io.github.thecodingdad.moonlightlauncher.ui.components.isControllerMode()
    LaunchedEffect(controllerMode) { if (controllerMode) io.github.thecodingdad.moonlightlauncher.ui.components.TouchFocus.restore() }

    val entryActions = remember(nav) {
        EntryActions(
            openDetail = { entry -> nav.navigate(Routes.detail(entry.item.id)) },
            launchDirect = { entry, target -> vm.launch(activity, entry, target) },
        )
    }

    var haPanel by remember { mutableStateOf(false) }
    var quickMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
            nav.popBackStack(Routes.MAIN, inclusive = false)
            vm.tab = 0
            entryActions.pick = null
            entryActions.closeContext()
            keyboard.close()
            haPanel = false
            quickMenu = false
        }
    }
    var platformFor by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?>(null) }
    var collectionsFor by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?>(null) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollSpec = remember(density) { io.github.thecodingdad.moonlightlauncher.ui.components.MarginBringIntoViewSpec(density) }

    // On a TV the handheld-sized layout looks huge: scale the whole UI down by lowering the density.
    val external by vm.externalDisplay.collectAsState()
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val uiScale = (if (settings.tvScaleAuto && external != null) settings.tvScale else settings.uiScale) / 100f
    val scaledDensity = remember(baseDensity, uiScale) {
        androidx.compose.ui.unit.Density(baseDensity.density * uiScale, baseDensity.fontScale)
    }

    LauncherTheme(settings) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides scaledDensity,
            LocalInputDispatcher provides dispatcher,
            LocalUiFeedback provides feedback,
            LocalKeyboardController provides keyboard,
            LocalHintHolder provides hints,
            LocalEntryActions provides entryActions,
            LocalButtonLayout provides settings.buttonLayout,
            LocalBringIntoViewSpec provides scrollSpec,
        ) {
            Box(Modifier.fillMaxSize().background(LauncherTheme.colors.background)) {
                NavHost(nav, startDestination = Routes.MAIN) {
                    composable(Routes.MAIN) { MainShell(vm, nav, openStats = { nav.navigate(Routes.STATS) }) }
                    composable(Routes.DETAIL) { backStack ->
                        val id = backStack.arguments?.getString("id").orEmpty()
                        val entry by remember(id) { vm.entry(id) }.collectAsState(null)
                        val sessions by remember(id) { vm.sessions(id) }.collectAsState(emptyList())
                        ShellFrame(hints) {
                            entry?.let { e ->
                                var fixing by remember { mutableStateOf(false) }
                                var artwork by remember { mutableStateOf(false) }
                                val scope = rememberCoroutineScope()
                                DetailScreen(e, sessions, settings.animatedArtwork, onFavorite = { vm.setFavorite(e, it) }, onPlatform = { platformFor = e }) {
                                    MetadataActions(
                                        entry = e,
                                        onFix = { fixing = true },
                                        onArtwork = { artwork = true },
                                        onConfirm = { scope.launch { activity.container.metadata.confirm(e.item.id) } },
                                    )
                                }
                                FixMetadataDialog(if (fixing) e.item else null) { fixing = false }
                                ArtworkPickerDialog(if (artwork) e.item else null) { artwork = false }
                            }
                        }
                    }
                    composable(Routes.SETTINGS) { backStack ->
                        val page = SettingsPageId.valueOf(backStack.arguments?.getString("page") ?: SettingsPageId.ABOUT.name)
                        ShellFrame(hints) {
                            SettingsPageContent(
                                page, vm,
                                openReview = { nav.navigate(Routes.REVIEW) },
                                openProfile = { nav.navigate(Routes.profile(it)) },
                                openHost = { nav.navigate(Routes.host(it)) },
                                openControllers = { nav.navigate(Routes.CONTROLLERS) },
                            )
                        }
                    }
                    composable(Routes.HOST) { backStack ->
                        val uuid = backStack.arguments?.getString("uuid").orEmpty()
                        val hosts by vm.hosts.collectAsState()
                        ShellFrame(hints) { hosts[uuid]?.let { HostConfigScreen(it) } }
                    }
                    composable(Routes.CONTROLLERS) { ShellFrame(hints) { ControllersScreen() } }
                    composable(Routes.STATS) {
                        val all by vm.allEntries.collectAsState()
                        val sessions by vm.finishedSessions.collectAsState()
                        ShellFrame(hints) { StatsScreen(all, sessions) }
                    }
                    composable(Routes.PROFILE) { backStack ->
                        val raw = backStack.arguments?.getString("uuid")
                        val uuid = raw?.takeIf { it != "base" }?.let(java.util.UUID::fromString)
                        val profiles by vm.profiles.collectAsState()
                        val name = profiles.firstOrNull { it.uuid == uuid }?.name ?: stringResource(R.string.profile_default)
                        ShellFrame(hints) { ProfileEditor(uuid, name, onDeleted = { nav.popBackStack() }) }
                    }
                    composable(Routes.REVIEW) {
                        val all by vm.allEntries.collectAsState()
                        val review = remember(all) { all.filter { it.item.metadataState == MetadataState.REVIEW } }
                        ShellFrame(hints) { ReviewScreen(review) }
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
                    onLaunch = { entry, target, profile ->
                        entryActions.pick = null
                        vm.launch(activity, entry, target, profile)
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
                        if (entry != null && target != null) vm.launch(activity, entry, target)
                    },
                    onDismiss = entryActions::closeContext,
                    onPlay = entryActions::play,
                    onPlayWith = entryActions::playWith,
                    onDetails = entryActions::showDetail,
                    onFavorite = vm::setFavorite,
                    onKind = vm::setKind,
                    onHide = { vm.setHidden(it, true) },
                    onCollections = { collectionsFor = it },
                    onPlatform = { platformFor = it },
                )
                val allForPlatforms by vm.allEntries.collectAsState()
                io.github.thecodingdad.moonlightlauncher.ui.shell.PlatformDialog(
                    entry = platformFor,
                    knownPlatforms = remember(allForPlatforms) { allForPlatforms.mapNotNull { it.item.platform }.distinct().sorted() },
                    onSelect = vm::setPlatform,
                    onDismiss = { platformFor = null },
                )
                val collections by vm.collections.collectAsState()
                val allFilters by vm.filters.collectAsState()
                // Apps only see app groups, games only game groups.
                val groupScope = if (collectionsFor?.item?.kind == io.github.thecodingdad.moonlightlauncher.data.db.ItemKind.APP) {
                    io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.APPS
                } else io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.GAMES
                val scopedGroupIds = allFilters.filter {
                    (it.scope == io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.APPS) ==
                        (groupScope == io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.APPS)
                }.map { it.id }.toSet()
                CollectionsDialog(
                    entry = collectionsFor,
                    collections = collections.filter { it.first.id in scopedGroupIds },
                    onToggle = { id, member -> collectionsFor?.let { vm.setInCollection(it.item.id, id, member) } },
                    onCreate = { name -> vm.createGroup(name, groupScope, collectionsFor?.item?.id) },
                    onDismiss = { collectionsFor = null },
                )
                val launchState by vm.launchState.collectAsState()
                val launchKind = launchState::class
                LaunchedEffect(launchKind) {
                    when (launchState) {
                        is io.github.thecodingdad.moonlightlauncher.stream.LaunchState.Working -> feedback.play(io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound.LAUNCH)
                        is io.github.thecodingdad.moonlightlauncher.stream.LaunchState.Failed -> feedback.play(io.github.thecodingdad.moonlightlauncher.ui.feedback.UiSound.ERROR)
                        else -> Unit
                    }
                }
                LaunchOverlay(
                    state = launchState,
                    onDismiss = vm::dismissLaunch,
                    onRetry = { entry -> vm.dismissLaunch(); entryActions.play(entry) },
                    onConfirmQuit = { c -> vm.launch(activity, c.entry, c.target, c.override, quitRunning = true) },
                    profiles = profiles,
                    onContinueDespiteNetwork = { w, profile -> vm.launch(activity, w.entry, w.target, profile, skipNetworkCheck = true) },
                    onCancel = vm::cancelLaunch,
                )
                // Y opens the smart home panel everywhere (screens can't override it).
                PadAction(PadButton.Y) { haPanel = !haPanel }
                HaPanel(visible = haPanel, onDismiss = { haPanel = false }, openSettings = { nav.navigate(Routes.settings(SettingsPageId.HOME_ASSISTANT)) })
                // Holding Select opens the quick menu everywhere.
                PadAction(PadButton.SELECT_HOLD) { quickMenu = !quickMenu }
                val running by vm.runningSessions.collectAsState()
                QuickMenu(
                    visible = quickMenu,
                    onDismiss = { quickMenu = false },
                    profiles = profiles,
                    activeProfile = activeProfile,
                    onProfile = vm::setActiveProfile,
                    running = running,
                    onResume = { session ->
                        val entry = session.entry
                        val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                        if (entry != null && target != null) vm.launch(activity, entry, target)
                    },
                )
                ControllerKeyboardHost(keyboard)

                // Screensaver after the configured idle time (also protects the OLED panel).
                var idle by remember { mutableStateOf(false) }
                val lastInput by vm.lastInteraction.collectAsState()
                LaunchedEffect(lastInput, settings.screensaverMinutes) {
                    idle = false
                    if (settings.screensaverMinutes <= 0) return@LaunchedEffect
                    kotlinx.coroutines.delay(settings.screensaverMinutes * 60_000L)
                    idle = true
                }
                val view = androidx.compose.ui.platform.LocalView.current
                // Android's own screen timeout would switch the display off before the screensaver:
                // keep it on while the launcher is visible, and let it sleep 10 min into the screensaver.
                LaunchedEffect(idle, settings.screensaverMinutes) {
                    view.keepScreenOn = settings.screensaverMinutes > 0
                    if (idle) {
                        kotlinx.coroutines.delay(10 * 60_000L)
                        view.keepScreenOn = false
                    }
                }
                if (idle) {
                    val games by vm.games.collectAsState()
                    io.github.thecodingdad.moonlightlauncher.ui.shell.Screensaver(games) { idle = false; vm.touch() }
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
        ButtonHintBar(hints.hints.ifEmpty { listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_select)), ButtonHint(PadButton.B, stringResource(R.string.hint_back))) })
    }
}

@Composable
private fun MainShell(vm: LauncherViewModel, nav: NavHostController, openStats: () -> Unit) {
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
    val activity = LocalActivity.current!!
    var tab by vm.tabState
    val tabs = MainTab.entries
    val hosts = hostsMap.values.sortedBy { it.name }
    val libraryFilters by vm.libraryFilters.collectAsState()
    val homeFilters by vm.homeFilters.collectAsState()
    val appsFilters by vm.appsFilters.collectAsState()
    // Keeps each tab's saved state (filter, sort, scroll position) while another tab is shown
    // or a detail page is open.
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var hostInfoFor by remember { mutableStateOf<String?>(null) }

    PadAction(PadButton.L1) { tab = (tab - 1).mod(tabs.size) }
    PadAction(PadButton.R1) { tab = (tab + 1).mod(tabs.size) }
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
    val fullBleed = tabs[tab] == MainTab.HOME && settings.homeLayout != HomeLayout.GRID
    val bg = LauncherTheme.colors.background
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            Modifier.fillMaxSize()
                .then(if (fullBleed) Modifier else Modifier.padding(top = topBarHeight, bottom = hintBarHeight)),
        ) {
          CompositionLocalProvider(
              io.github.thecodingdad.moonlightlauncher.ui.screens.home.LocalShellInsets provides
                  androidx.compose.foundation.layout.PaddingValues(top = topBarHeight, bottom = hintBarHeight),
          ) {
            AnimatedContent(tabs[tab], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                tabStates.SaveableStateProvider(current.name) {
                when (current) {
                    MainTab.HOME -> {
                        val rows = remember(games, running) { HomeRows.from(games, running) }
                        val callbacks = HomeCallbacks(
                            onResume = { session ->
                                val entry = session.entry
                                val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                                if (entry != null && target != null) vm.launch(activity, entry, target)
                            },
                            onRunningMenu = entryActions::contextForSession,
                            lastFocusedKey = vm.lastFocusedKey,
                            onFocusKey = { vm.lastFocusedKey = it },
                            animatedArtwork = settings.animatedArtwork,
                        )
                        io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints(
                            listOf(
                                ButtonHint(PadButton.A, stringResource(R.string.hint_open)),
                                ButtonHint(PadButton.X, stringResource(R.string.hint_options)),
                                ButtonHint(PadButton.Y, stringResource(R.string.settings_home_assistant)),
                                ButtonHint(PadButton.SELECT_HOLD, stringResource(R.string.quick_menu_hold)),
                                ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs)),
                            ),
                        )
                        val empty: @Composable () -> Unit = { EmptyHome { tab = MainTab.SETTINGS.ordinal } }
                        when (settings.homeLayout) {
                            HomeLayout.HERO -> HeroHome(
                                rows, callbacks, homeFilters,
                                haRow = if (settings.showHaRowOnHome) ({ HaHomeRow() }) else null,
                                emptyContent = empty,
                            )
                            HomeLayout.GRID -> GridHome(games, running, callbacks, empty)
                            HomeLayout.CAROUSEL -> CarouselHome(rows, callbacks, openTab = { tab = it }, emptyContent = empty)
                        }
                    }
                    MainTab.LIBRARY -> LibraryScreen(
                        allEntries, libraryFilters, settings.animatedArtwork, vm.lastFocusedKey, openStats,
                        openFilterSettings = { nav.navigate(Routes.settings(SettingsPageId.FILTERS)) },
                    ) { vm.lastFocusedKey = it }
                    MainTab.APPS -> AppsScreen(
                        apps, appsFilters, vm.lastFocusedKey,
                        openFilterSettings = { nav.navigate(Routes.settings(SettingsPageId.APP_FILTERS)) },
                    ) { vm.lastFocusedKey = it }
                    MainTab.SETTINGS -> {
                        io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints(
                            listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_select)), ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs))),
                        )
                        SettingsHome(open = { nav.navigate(Routes.settings(it)) }, isFirstVisit = true)
                    }
                }
                }
            }
          }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(
                    if (fullBleed) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0.85f), androidx.compose.ui.graphics.Color.Transparent))
                    else androidx.compose.ui.graphics.SolidColor(bg),
                )
                .onSizeChanged { topBarHeight = with(density) { it.height.toDp() } }
                .padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = if (fullBleed) 14.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                tabs.forEachIndexed { index, t ->
                    TabChip(stringResource(t.title), selected = index == tab, onClick = { tab = index })
                }
            }
            StatusCluster(hosts, activeProfile.name, onProfileClick = ::cycleProfile, onHostClick = { hostInfoFor = it.uuid })
        }
        ButtonHintBar(
            hints.hints,
            Modifier.align(Alignment.BottomCenter)
                .background(
                    if (fullBleed) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, bg.copy(alpha = 0.7f)))
                    else androidx.compose.ui.graphics.SolidColor(bg),
                )
                .onSizeChanged { hintBarHeight = with(density) { it.height.toDp() } },
        )
        io.github.thecodingdad.moonlightlauncher.ui.shell.HostInfoPopup(
            host = hostInfoFor?.let { hostsMap[it] },
            runningGame = running.firstOrNull { it.host.uuid == hostInfoFor }?.appName,
            onDismiss = { hostInfoFor = null },
            openSettings = { nav.navigate(Routes.host(it.uuid)) },
        )
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
private fun EmptyHome(openSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.headlineMedium, color = LauncherTheme.colors.onSurface)
        Text(
            stringResource(R.string.home_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = LauncherTheme.colors.onSurfaceMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        io.github.thecodingdad.moonlightlauncher.ui.components.LauncherButton(
            stringResource(R.string.settings_hosts), onClick = openSettings, primary = true,
            modifier = Modifier.restoreFocusIf(true),
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
    openControllers: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val all by vm.allEntries.collectAsState()
    val context = LocalContext.current
    when (page) {
        SettingsPageId.METADATA -> MetadataSettings(
            settings, vm::updateSettings,
            reviewCount = all.count { it.item.metadataState == MetadataState.REVIEW },
            openReview = openReview,
        )
        SettingsPageId.HOSTS -> HostsSettings(hosts.values.toList(), openHostConfig = { openHost(it.uuid) })
        SettingsPageId.APPEARANCE -> {
            val external by vm.externalDisplay.collectAsState()
            AppearanceSettings(settings, vm::updateSettings, external)
        }
        SettingsPageId.CONTROLS -> ControlsSettings(settings, vm::updateSettings, openControllers = openControllers)
        SettingsPageId.LANGUAGE -> LanguageSettings(settings, vm::updateSettings)
        SettingsPageId.FILTERS -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.FilterSettings(vm, io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.GAMES)
        SettingsPageId.APP_FILTERS -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.FilterSettings(vm, io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.APPS)
        SettingsPageId.SOUNDS -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.SoundSettings(settings, vm::updateSettings)
        SettingsPageId.ABOUT -> AboutSettings()
        SettingsPageId.PROFILES -> {
            val profiles by vm.profiles.collectAsState()
            val active by vm.activeProfile.collectAsState()
            ProfilesList(profiles, active, openProfile)
        }
        SettingsPageId.LAUNCHER -> LauncherModeSettings(settings, vm::updateSettings)
        SettingsPageId.HOME_ASSISTANT -> HaSettings(settings, vm::updateSettings)
        SettingsPageId.BACKUP -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.BackupSettings(settings, vm::updateSettings)
        SettingsPageId.VPN -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.VpnSettings(settings, vm::updateSettings)
        else -> SettingsPage(stringResource(R.string.coming_soon)) {}
    }
}
