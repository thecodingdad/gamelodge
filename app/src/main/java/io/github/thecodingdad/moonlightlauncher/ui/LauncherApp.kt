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
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.MetadataSettings
import io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.ReviewScreen
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import io.github.thecodingdad.moonlightlauncher.ui.screens.ha.HaSettings
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
            .getOrDefault(io.github.thecodingdad.moonlightlauncher.ui.feedback.SoundStyle.MUFFLED),
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

enum class MainTab(val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME(R.string.tab_home, androidx.compose.material.icons.Icons.Filled.Home),
    LIBRARY(R.string.tab_library, androidx.compose.material.icons.Icons.Filled.VideoLibrary),
    APPS(R.string.tab_apps, androidx.compose.material.icons.Icons.Filled.Apps),
    SETTINGS(R.string.tab_settings, androidx.compose.material.icons.Icons.Filled.Settings),
}

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
    entryActions.directStart = settings.gamesDirectStart

    var hostInfoFor by remember { mutableStateOf<String?>(null) }
    var controllerPopup by remember { mutableStateOf(false) }
    var quickMenu by remember { mutableStateOf(false) }
    // After start: the configured start tab (read from storage, not the not-yet-loaded default).
    LaunchedEffect(Unit) {
        if (!vm.startTabApplied) {
            vm.startTabApplied = true
            val before = vm.tab
            val start = MainTab.valueOf(activity.container.settings.settings.first().startTab.name).ordinal
            // Unless the user already switched tabs in the meantime.
            if (vm.tab == before) vm.tab = start
        }
    }
    LaunchedEffect(Unit) {
        vm.homePressed.collect {
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
            hostInfoFor = null
            controllerPopup = false
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
    var platformFor by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?>(null) }
    var metaEditorFor by remember { mutableStateOf<String?>(null) }
    var fixingFor by remember { mutableStateOf<String?>(null) }
    var artworkFor by remember { mutableStateOf<String?>(null) }
    var appTileFor by remember { mutableStateOf<String?>(null) }
    val metaScope = rememberCoroutineScope()
    // Game whose detail page is on screen (its menu leaves out what the page offers itself).
    // Game whose detail page is on screen (paging through a list changes it without navigating).
    var detailShownId by remember { mutableStateOf<String?>(null) }
    val detailOpenFor = detailShownId
    var collectionsFor by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?>(null) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollSpec = remember(density) { io.github.thecodingdad.moonlightlauncher.ui.components.MarginBringIntoViewSpec(density) }

    // Mirrored to a TV the handheld-sized layout looks huge: scale the whole UI down by lowering
    // the density. Running on the TV itself: lay out for ~960 dp width, like on the handheld.
    val external by vm.externalDisplay.collectAsState()
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val hostActivity = androidx.activity.compose.LocalActivity.current
    val onTv = remember(configuration) { hostActivity?.let(io.github.thecodingdad.moonlightlauncher.system.TvSession::isOnTv) == true }
    // The TV size setting applies on the TV either way (mirrored or running there).
    val uiScale = (if (settings.tvScaleAuto && (external != null || onTv)) settings.tvScale else settings.uiScale) / 100f
    val scaledDensity = remember(baseDensity, uiScale, onTv, configuration.screenWidthDp) {
        val base = if (onTv) configuration.screenWidthDp * baseDensity.density / 960f else baseDensity.density
        androidx.compose.ui.unit.Density(base * uiScale, baseDensity.fontScale)
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
            io.github.thecodingdad.moonlightlauncher.ui.input.RightStickScrollDriver()
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // Button hints only make sense with a controller (and can be turned off entirely).
            val hintControllers = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers()
            CompositionLocalProvider(
                io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth provides (maxWidth < 720.dp),
                io.github.thecodingdad.moonlightlauncher.ui.components.LocalShowHintBar provides (settings.showHintBar && hintControllers.isNotEmpty()),
            ) {
            Box(Modifier.fillMaxSize().background(LauncherTheme.colors.background)) {
                // The launcher behind any popup or panel is blurred (Android 12+; older versions just dim).
                val backdropBlur by androidx.compose.animation.core.animateDpAsState(if (io.github.thecodingdad.moonlightlauncher.ui.components.OverlayHost.anyVisible) 14.dp else 0.dp, label = "backdropBlur")
                NavHost(nav, startDestination = Routes.MAIN, modifier = Modifier.blur(backdropBlur)) {
                    composable(Routes.MAIN) {
                        MainShell(
                            vm, nav, openStats = { nav.navigate(Routes.STATS) },
                            onHostInfo = { hostInfoFor = it }, onControllers = { controllerPopup = true },
                        )
                    }
                    composable(Routes.DETAIL) { backStack ->
                        val startId = backStack.arguments?.getString("id").orEmpty()
                        val browse = remember(startId) { entryActions.browse?.takeIf { startId in it.ids } }
                        var lastShown by remember { mutableStateOf(startId) }
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            onDispose {
                                detailShownId = null
                                if (lastShown != startId) entryActions.returnFocusId = lastShown
                            }
                        }
                        ShellFrame(hints) {
                        io.github.thecodingdad.moonlightlauncher.ui.screens.detail.DetailBrowser(
                            startId, browse?.ids,
                            onShown = { shown -> detailShownId = shown; lastShown = shown; browse?.onShown?.invoke(shown) },
                        ) { id, onBrowse, pageActive ->
                            val entry by remember(id) { vm.entry(id) }.collectAsState(null)
                            val sessions by remember(id) { vm.sessions(id) }.collectAsState(emptyList())
                            entry?.let { e ->
                                val detailProfiles by vm.profiles.collectAsState()
                                val detailActive by vm.activeProfile.collectAsState()
                                DetailScreen(
                                    e, sessions, settings.animatedArtwork, onFavorite = { vm.setFavorite(e, it) }, onPlatform = { platformFor = e },
                                    profiles = detailProfiles, activeProfile = detailActive, onProfile = vm::setActiveProfile,
                                    onEditProfiles = { nav.navigate(Routes.settings(SettingsPageId.PROFILES)) },
                                    onBrowse = onBrowse,
                                    active = pageActive,
                                ) {
                                    io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.ReviewConfirmAction(e) {
                                        metaScope.launch { activity.container.metadata.confirm(e.item.id) }
                                    }
                                }
                            }
                        }
                        }
                    }
                    composable(Routes.SETTINGS) { backStack ->
                        val page = SettingsPageId.valueOf(backStack.arguments?.getString("page") ?: SettingsPageId.ABOUT.name)
                        SubpageFrame(vm, nav, hints, MainTab.SETTINGS, listOf(tabCrumb(vm, nav, MainTab.SETTINGS)), { hostInfoFor = it }, { controllerPopup = true }) {
                            SettingsPageContent(
                                page, vm,
                                openReview = { nav.navigate(Routes.REVIEW) },
                                openProfile = { nav.navigate(Routes.profile(it)) },
                                openHost = { nav.navigate(Routes.host(it)) },
                                openControllers = { nav.navigate(Routes.CONTROLLERS) },
                                openPage = { nav.navigate(Routes.settings(it)) },
                            )
                        }
                    }
                    composable(Routes.HOST) { backStack ->
                        val uuid = backStack.arguments?.getString("uuid").orEmpty()
                        val hosts by vm.hosts.collectAsState()
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS,
                            listOf(tabCrumb(vm, nav, MainTab.SETTINGS), backCrumb(nav, R.string.settings_hosts)),
                            { hostInfoFor = it }, { controllerPopup = true },
                        ) { hosts[uuid]?.let { HostConfigScreen(it) } }
                    }
                    composable(Routes.CONTROLLERS) {
                        SubpageFrame(vm, nav, hints, MainTab.SETTINGS, listOf(tabCrumb(vm, nav, MainTab.SETTINGS)), { hostInfoFor = it }, { controllerPopup = true }) {
                            ControllersScreen()
                        }
                    }
                    composable(Routes.STATS) {
                        val all by vm.allEntries.collectAsState()
                        val sessions by vm.finishedSessions.collectAsState()
                        SubpageFrame(vm, nav, hints, MainTab.LIBRARY, listOf(tabCrumb(vm, nav, MainTab.LIBRARY)), { hostInfoFor = it }, { controllerPopup = true }) {
                            StatsScreen(all, sessions)
                        }
                    }
                    composable(Routes.PROFILE) { backStack ->
                        val raw = backStack.arguments?.getString("uuid")
                        val uuid = raw?.takeIf { it != "base" }?.let(java.util.UUID::fromString)
                        val profiles by vm.profiles.collectAsState()
                        val name = profiles.firstOrNull { it.uuid == uuid }?.name ?: stringResource(R.string.profile_default)
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS,
                            listOf(tabCrumb(vm, nav, MainTab.SETTINGS), backCrumb(nav, R.string.settings_profiles)),
                            { hostInfoFor = it }, { controllerPopup = true },
                        ) { ProfileEditor(uuid, name, onDeleted = { nav.popBackStack() }) }
                    }
                    composable(Routes.REVIEW) {
                        val all by vm.allEntries.collectAsState()
                        val review = remember(all) { all.filter { it.item.metadataState == MetadataState.REVIEW } }
                        SubpageFrame(
                            vm, nav, hints, MainTab.SETTINGS,
                            listOf(tabCrumb(vm, nav, MainTab.SETTINGS), backCrumb(nav, R.string.settings_metadata)),
                            { hostInfoFor = it }, { controllerPopup = true },
                        ) { ReviewScreen(review) }
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
                    onDetails = { e ->
                        // Apps have no detail page: Android's app info instead.
                        val pkg = e.localTarget?.packageName
                        if (e.item.kind == io.github.thecodingdad.moonlightlauncher.data.db.ItemKind.APP && pkg != null) {
                            runCatching {
                                activity.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        } else entryActions.showDetail(e)
                    },
                    onFavorite = vm::setFavorite,
                    onKind = vm::setKind,
                    onHide = { vm.setHidden(it, true) },
                    onCollections = { collectionsFor = it },
                    onEditMetadata = { metaEditorFor = it.item.id },
                    onAppArtwork = { appTileFor = it.item.id },
                    inDetail = detailOpenFor == entryActions.contextMenuFor?.item?.id,
                )
                // Metadata editor with its match and artwork dialogs, reachable from every menu.
                val metaLibrary by vm.allEntries.collectAsState()
                val metaEntry = metaEditorFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } }
                io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.MetadataEditorDialog(
                    entry = metaEntry, library = metaLibrary,
                    onPlatform = { platformFor = it }, onDismiss = { metaEditorFor = null },
                    onFix = { fixingFor = it.item.id }, onArtwork = { artworkFor = it.item.id },
                )
                FixMetadataDialog(fixingFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id }?.item }) { fixingFor = null }
                ArtworkPickerDialog(artworkFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id }?.item }) { artworkFor = null }
                io.github.thecodingdad.moonlightlauncher.ui.screens.metadata.AppTileDialog(appTileFor?.let { id -> metaLibrary.firstOrNull { it.item.id == id } }) { appTileFor = null }
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
                // Y opens the quick menu everywhere (holding Select does too): system controls,
                // controllers, hosts, VPN, stream profile and the Home Assistant tiles.
                PadAction(PadButton.Y) { quickMenu = !quickMenu }
                PadAction(PadButton.SELECT_HOLD) { quickMenu = !quickMenu }
                val running by vm.runningSessions.collectAsState()
                QuickMenu(
                    visible = quickMenu,
                    onDismiss = { quickMenu = false },
                    profiles = profiles,
                    activeProfile = activeProfile,
                    onProfile = vm::setActiveProfile,
                    onHost = { hostInfoFor = it },
                    onControllers = { controllerPopup = true },
                    openHaSettings = { quickMenu = false; nav.navigate(Routes.settings(SettingsPageId.HOME_ASSISTANT)) },
                )
                // Popups from the status bar and the quick menu, above everything else.
                val controllers = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers()
                val hostsGlobal by vm.hosts.collectAsState()
        io.github.thecodingdad.moonlightlauncher.ui.shell.ControllerPopup(
            visible = controllerPopup, controllers = controllers,
            onOpenSettings = { nav.navigate(Routes.CONTROLLERS) },
            onDismiss = { controllerPopup = false },
        )
        io.github.thecodingdad.moonlightlauncher.ui.shell.HostInfoPopup(
            host = hostInfoFor?.let { hostsGlobal[it] },
            runningGame = running.firstOrNull { it.host.uuid == hostInfoFor }?.appName,
            profiles = profiles,
            activeProfile = activeProfile,
            onProfile = vm::setActiveProfile,
            onEditProfiles = { nav.navigate(Routes.settings(SettingsPageId.PROFILES)) },
            onDismiss = { hostInfoFor = null },
            openSettings = { nav.navigate(Routes.host(it.uuid)) },
        )
                ControllerKeyboardHost(keyboard)
                // Every popup and panel is drawn here, above the (blurred) launcher.
                io.github.thecodingdad.moonlightlauncher.ui.components.OverlayOutlet()

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
                        ButtonHint(PadButton.A, stringResource(R.string.hint_select)),
                        ButtonHint(PadButton.B, stringResource(R.string.hint_back)),
                        ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs), secondButton = PadButton.R1),
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
    val controllers = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers()

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
    val compactShell = io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current
    val showHints = io.github.thecodingdad.moonlightlauncher.ui.components.LocalShowHintBar.current
    // A hidden hint bar takes no space; its last measured height must not linger.
    LaunchedEffect(showHints, compactShell) { if (!showHints && !compactShell) hintBarHeight = 0.dp }
    val fullBleed = tabs[tab] == MainTab.HOME
    val bg = LauncherTheme.colors.background
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxSize()) {
          CompositionLocalProvider(
              io.github.thecodingdad.moonlightlauncher.ui.screens.home.LocalShellInsets provides
                  androidx.compose.foundation.layout.PaddingValues(top = topBarHeight, bottom = hintBarHeight),
          ) {
            AnimatedContent(tabs[tab], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                // The start tab's saved state is keyed by the reset counter, so Home starts fresh.
                val stateKey = if (current == MainTab.HOME) "HOME-${vm.homeResets.intValue}" else current.name
                tabStates.SaveableStateProvider(stateKey) {
                // Each tab applies the bar insets itself (full-bleed home draws behind them), so the
                // outgoing and incoming tab keep their own layout during the cross-fade.
                val tabFullBleed = current == MainTab.HOME
                Box(Modifier.fillMaxSize().then(if (tabFullBleed) Modifier else Modifier.padding(top = topBarHeight, bottom = hintBarHeight))) {
                when (current) {
                    MainTab.HOME -> {
                        val rows = remember(games, running) { HomeRows.from(games, running) }
                        var homeFocused by remember { mutableStateOf<io.github.thecodingdad.moonlightlauncher.data.db.LibraryEntry?>(null) }
                        val callbacks = HomeCallbacks(
                            onResume = { session ->
                                val entry = session.entry
                                val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                                if (entry != null && target != null) vm.launch(activity, entry, target)
                            },
                            onRunningMenu = entryActions::contextForSession,
                            lastFocusedKey = vm.focusKeys[MainTab.HOME.name],
                            onFocusKey = { vm.focusKeys[MainTab.HOME.name] = it },
                            onAtStart = { vm.homeAtStart = it },
                            onFocusedEntry = { homeFocused = it },
                            animatedArtwork = settings.animatedArtwork,
                        )
                        io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints(
                            listOf(
                                ButtonHint(PadButton.A, stringResource(entryActions.openLabel(homeFocused))),
                                ButtonHint(PadButton.X, stringResource(R.string.hint_options)),
                                ButtonHint(PadButton.Y, stringResource(R.string.quick_menu)),
                                ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs), secondButton = PadButton.R1),
                            ),
                        )
                        val empty: @Composable () -> Unit = { EmptyHome { tab = MainTab.SETTINGS.ordinal } }
                        HeroHome(
                            rows, callbacks, homeFilters,
                            haRow = if (settings.showHaRowOnHome) ({ HaHomeRow() }) else null,
                            emptyContent = empty,
                            haIndex = io.github.thecodingdad.moonlightlauncher.data.library.HomeRowOrder.haIndex(homeFilters, settings.homeRowOrder),
                        )
                    }
                    MainTab.LIBRARY -> LibraryScreen(
                        allEntries, libraryFilters, settings.animatedArtwork, vm.focusKeys[MainTab.LIBRARY.name], openStats,
                        openFilterSettings = { nav.navigate(Routes.settings(SettingsPageId.FILTERS)) },
                    ) { vm.focusKeys[MainTab.LIBRARY.name] = it }
                    MainTab.APPS -> AppsScreen(
                        apps, appsFilters, vm.focusKeys[MainTab.APPS.name],
                        openFilterSettings = { nav.navigate(Routes.settings(SettingsPageId.APP_FILTERS)) },
                    ) { vm.focusKeys[MainTab.APPS.name] = it }
                    MainTab.SETTINGS -> {
                        io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints(
                            listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_select)), ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs), secondButton = PadButton.R1)),
                        )
                        SettingsHome(open = { nav.navigate(Routes.settings(it)) }, isFirstVisit = true, openControllers = { nav.navigate(Routes.CONTROLLERS) })
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
    hosts: List<io.github.thecodingdad.moonlightlauncher.data.remote.HostState>,
    controllerCount: Int,
    onHostInfo: (String) -> Unit,
    onControllers: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tap on the clock opens the quick menu. */
    onClock: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth()
            // Constant height for every tab: a changing bar height would shift all content.
            .padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val compact = io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current
        val status: @Composable () -> Unit = {
            StatusCluster(
                hosts,
                onHostClick = { onHostInfo(it.uuid) },
                controllerCount = controllerCount,
                onControllerClick = onControllers,
                onClockClick = onClock,
            )
        }
        if (compact) {
            // Narrow screen: only the status at the top; the tabs move to the bottom navigation bar.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { status() }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                MainTab.entries.forEachIndexed { index, t ->
                    TabChip(stringResource(t.title), selected = index == selected, onClick = { onSelect(index) })
                }
            }
            status()
        }
    }
}

/** Leaves any sub page for a main tab. */
private fun openMainTab(vm: LauncherViewModel, nav: NavHostController, tab: MainTab) {
    vm.tabState.intValue = tab.ordinal
    nav.popBackStack(Routes.MAIN, inclusive = false)
}

/** Path step to a main tab. */
@Composable
private fun tabCrumb(vm: LauncherViewModel, nav: NavHostController, tab: MainTab) =
    io.github.thecodingdad.moonlightlauncher.ui.screens.settings.Crumb(stringResource(tab.title)) { openMainTab(vm, nav, tab) }

/** Path step back to the page this one was opened from. */
@Composable
private fun backCrumb(nav: NavHostController, label: Int) =
    io.github.thecodingdad.moonlightlauncher.ui.screens.settings.Crumb(stringResource(label)) { nav.popBackStack() }

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
    parents: List<io.github.thecodingdad.moonlightlauncher.ui.screens.settings.Crumb>,
    onHostInfo: (String) -> Unit,
    onControllers: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hostsMap by vm.hosts.collectAsState()
    val controllers = io.github.thecodingdad.moonlightlauncher.ui.screens.settings.rememberControllers()
    val compact = io.github.thecodingdad.moonlightlauncher.ui.components.LocalCompactWidth.current
    fun openTab(index: Int) = openMainTab(vm, nav, MainTab.entries[index])
    PadAction(PadButton.L1) { openTab((tab.ordinal - 1).mod(MainTab.entries.size)) }
    PadAction(PadButton.R1) { openTab((tab.ordinal + 1).mod(MainTab.entries.size)) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ShellTopBar(
            selected = tab.ordinal, onSelect = ::openTab, hosts = hostsMap.values.sortedBy { it.name },
            controllerCount = controllers.size, onHostInfo = onHostInfo, onControllers = onControllers,
            onClock = { vm.quickMenuTouch.tryEmit(Unit) },
            modifier = Modifier.background(LauncherTheme.colors.background),
        )
        CompositionLocalProvider(
            io.github.thecodingdad.moonlightlauncher.ui.screens.settings.LocalPagePath provides
                io.github.thecodingdad.moonlightlauncher.ui.screens.settings.PagePath(parents) { nav.popBackStack() },
        ) {
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
        if (compact) {
            BottomNavBar(tabs = MainTab.entries, selected = tab.ordinal, onSelect = ::openTab)
        } else {
            ButtonHintBar(
                hints.hints.ifEmpty {
                    listOf(
                        ButtonHint(PadButton.A, stringResource(R.string.hint_select)),
                        ButtonHint(PadButton.B, stringResource(R.string.hint_back)),
                        ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs), secondButton = PadButton.R1),
                    )
                },
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
        tabs.forEachIndexed { index, t ->
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
    openPage: (SettingsPageId) -> Unit,
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
        SettingsPageId.HOME_SCREEN -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.HomeScreenSettings(settings, vm::updateSettings, vm, openFilters = { openPage(SettingsPageId.FILTERS) })
        SettingsPageId.THEME -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.ThemeSettings(settings, vm::updateSettings)
        SettingsPageId.DISPLAY -> {
            val external by vm.externalDisplay.collectAsState()
            io.github.thecodingdad.moonlightlauncher.ui.screens.settings.DisplaySettings(settings, vm::updateSettings, external)
        }
        SettingsPageId.CONTROLS -> ControlsSettings(settings, vm::updateSettings, openControllers = openControllers)
        SettingsPageId.LANGUAGE -> LanguageSettings(settings, vm::updateSettings)
        SettingsPageId.FILTERS -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.FilterSettings(vm, io.github.thecodingdad.moonlightlauncher.data.db.FilterScope.GAMES)
        SettingsPageId.QUICK_MENU -> io.github.thecodingdad.moonlightlauncher.ui.screens.settings.QuickMenuSettings(vm, settings, vm::updateSettings)
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
