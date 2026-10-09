package io.github.thecodingdad.moonlightlauncher.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.limelight.ProfilesActivity
import io.github.thecodingdad.moonlightlauncher.R
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
import io.github.thecodingdad.moonlightlauncher.ui.screens.home.HeroHome
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

private object Routes {
    const val MAIN = "main"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings/{page}"
    fun detail(id: String) = "detail/$id"
    fun settings(page: SettingsPageId) = "settings/${page.name}"
}

enum class MainTab(val title: Int) { HOME(R.string.tab_home), LIBRARY(R.string.tab_library), APPS(R.string.tab_apps), SETTINGS(R.string.tab_settings) }

@Composable
fun LauncherApp(vm: LauncherViewModel, dispatcher: InputDispatcher, feedback: UiFeedback) {
    val settings by vm.settings.collectAsState()
    val nav = rememberNavController()
    val keyboard = remember { KeyboardController() }
    val hints = remember { HintHolder() }
    val activity = LocalContext.current as Activity

    feedback.soundsEnabled = settings.uiSounds
    feedback.volume = settings.uiSoundVolume / 100f
    feedback.hapticsEnabled = settings.haptics

    val entryActions = remember(nav) {
        EntryActions(
            openDetail = { entry -> nav.navigate(Routes.detail(entry.item.id)) },
            launchDirect = { entry, target -> vm.launch(activity, entry, target) },
        )
    }

    LauncherTheme(settings) {
        CompositionLocalProvider(
            LocalInputDispatcher provides dispatcher,
            LocalUiFeedback provides feedback,
            LocalKeyboardController provides keyboard,
            LocalHintHolder provides hints,
            LocalEntryActions provides entryActions,
            LocalButtonLayout provides settings.buttonLayout,
        ) {
            Box(Modifier.fillMaxSize().background(LauncherTheme.colors.background)) {
                NavHost(nav, startDestination = Routes.MAIN) {
                    composable(Routes.MAIN) { MainShell(vm, nav) }
                    composable(Routes.DETAIL) { backStack ->
                        val id = backStack.arguments?.getString("id").orEmpty()
                        val entry by remember(id) { vm.entry(id) }.collectAsState(null)
                        val sessions by remember(id) { vm.sessions(id) }.collectAsState(emptyList())
                        ShellFrame(hints) {
                            entry?.let { e ->
                                DetailScreen(e, sessions, settings.animatedArtwork, onFavorite = { vm.setFavorite(e, it) })
                            }
                        }
                    }
                    composable(Routes.SETTINGS) { backStack ->
                        val page = SettingsPageId.valueOf(backStack.arguments?.getString("page") ?: SettingsPageId.ABOUT.name)
                        ShellFrame(hints) { SettingsPageContent(page, vm) }
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
                EntryContextMenu(
                    entry = entryActions.contextMenuFor,
                    onDismiss = { entryActions.contextMenuFor = null },
                    onPlay = entryActions::play,
                    onPlayWith = entryActions::playWith,
                    onDetails = entryActions::showDetail,
                    onFavorite = vm::setFavorite,
                    onKind = vm::setKind,
                    onHide = { vm.setHidden(it, true) },
                )
                val launchState by vm.launchState.collectAsState()
                LaunchOverlay(
                    state = launchState,
                    onDismiss = vm::dismissLaunch,
                    onRetry = { entry -> vm.dismissLaunch(); entryActions.play(entry) },
                    onConfirmQuit = { c -> vm.launch(activity, c.entry, c.target, c.override, quitRunning = true) },
                )
                ControllerKeyboardHost(keyboard)
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
private fun MainShell(vm: LauncherViewModel, nav: NavHostController) {
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
    val activity = LocalContext.current as Activity
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = MainTab.entries
    val hosts = hostsMap.values.sortedBy { it.name }

    PadAction(PadButton.L1) { tab = (tab - 1).mod(tabs.size) }
    PadAction(PadButton.R1) { tab = (tab + 1).mod(tabs.size) }
    BackHandler(enabled = tab != 0 || settings.launcherMode) { tab = 0 }

    fun cycleProfile() {
        if (profiles.isEmpty()) return
        val i = profiles.indexOfFirst { it.uuid == activeProfile.uuid }
        vm.setActiveProfile(profiles[(i + 1).mod(profiles.size)])
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                tabs.forEachIndexed { index, t ->
                    TabChip(stringResource(t.title), selected = index == tab, onClick = { tab = index })
                }
            }
            StatusCluster(hosts, activeProfile.name, onProfileClick = ::cycleProfile)
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(tabs[tab], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                when (current) {
                    MainTab.HOME -> {
                        val rows = remember(games, running) { HomeRows.from(games, running) }
                        val callbacks = HomeCallbacks(
                            onResume = { session ->
                                val entry = session.entry
                                val target = entry?.remoteTargets?.firstOrNull { it.hostUuid == session.host.uuid && it.appId == session.host.runningAppId }
                                if (entry != null && target != null) vm.launch(activity, entry, target)
                            },
                            onRunningMenu = { session -> session.entry?.let(entryActions::context) },
                            lastFocusedKey = vm.lastFocusedKey,
                            onFocusKey = { vm.lastFocusedKey = it },
                            animatedArtwork = settings.animatedArtwork,
                        )
                        io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints(
                            listOf(
                                ButtonHint(PadButton.A, stringResource(R.string.hint_open)),
                                ButtonHint(PadButton.X, stringResource(R.string.hint_options)),
                                ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs)),
                            ),
                        )
                        when (settings.homeLayout) {
                            HomeLayout.HERO, HomeLayout.GRID, HomeLayout.CAROUSEL -> HeroHome(rows, callbacks) { EmptyHome { tab = MainTab.SETTINGS.ordinal } }
                        }
                    }
                    MainTab.LIBRARY -> LibraryScreen(allEntries, hosts, settings.animatedArtwork, vm.lastFocusedKey) { vm.lastFocusedKey = it }
                    MainTab.APPS -> AppsScreen(apps, vm.lastFocusedKey) { vm.lastFocusedKey = it }
                    MainTab.SETTINGS -> {
                        io.github.thecodingdad.moonlightlauncher.ui.shell.ScreenHints(
                            listOf(ButtonHint(PadButton.A, stringResource(R.string.hint_select)), ButtonHint(PadButton.L1, stringResource(R.string.hint_tabs))),
                        )
                        SettingsHome(open = { nav.navigate(Routes.settings(it)) }, isFirstVisit = true)
                    }
                }
            }
        }
        ButtonHintBar(hints.hints)
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
private fun SettingsPageContent(page: SettingsPageId, vm: LauncherViewModel) {
    val settings by vm.settings.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val context = LocalContext.current
    when (page) {
        SettingsPageId.HOSTS -> HostsSettings(hosts.values.toList(), openHostConfig = {})
        SettingsPageId.APPEARANCE -> AppearanceSettings(settings, vm::updateSettings)
        SettingsPageId.CONTROLS -> ControlsSettings(settings, vm::updateSettings, openControllers = {})
        SettingsPageId.LANGUAGE -> LanguageSettings(settings, vm::updateSettings)
        SettingsPageId.ABOUT -> AboutSettings()
        SettingsPageId.PROFILES -> {
            // Interim: Artemis' profile editor until the own editor lands.
            androidx.compose.runtime.LaunchedEffect(Unit) { context.startActivity(Intent(context, ProfilesActivity::class.java)) }
            SettingsPage(stringResource(R.string.settings_profiles)) {}
        }
        else -> SettingsPage(stringResource(R.string.coming_soon)) {}
    }
}
