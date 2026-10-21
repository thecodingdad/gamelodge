package io.github.thecodingdad.gamelodge.ui.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.settings.LauncherSettings
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.LauncherViewModel
import io.github.thecodingdad.gamelodge.ui.PlatformUi
import io.github.thecodingdad.gamelodge.ui.components.BackHandler
import io.github.thecodingdad.gamelodge.ui.components.LauncherButton
import io.github.thecodingdad.gamelodge.ui.components.ScrollAreaColumn
import io.github.thecodingdad.gamelodge.ui.components.restoreFocusIf
import io.github.thecodingdad.gamelodge.ui.input.PadAction
import io.github.thecodingdad.gamelodge.ui.input.PadButton
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The steps of the setup wizard. Which of them a platform shows, and in which order, comes from
 * [PlatformUi.wizardSteps]; the backup path takes [PlatformUi.wizardDeviceSteps] after the restore.
 */
enum class WizardStep(val mandatory: Boolean = false) {
    WELCOME(mandatory = true),
    BACKUP,
    HOST_ROLE,
    CONTROLLER(mandatory = true),
    GUIDE,
    APPEARANCE,
    LAUNCHER_MODE,
    PERMISSIONS,
    STARTUP,
    GAME_SOURCES,
    EMULATORS,
    HOSTS,
    COMPANION,
    METADATA,
    RESTORE,
    HOME_ASSISTANT,
    VPN,
    FINISH,
}

/** What a step shows on the left (landscape) or above its content (portrait). */
internal class StepInfo(val icon: ImageVector, val title: StringResource, val text: StringResource, val banner: Boolean = false)

/**
 * Navigation inside the wizard, for the steps: [next] / [back] move on, [autoSkip] leaves a step
 * that has nothing to offer (in the direction the user was going).
 */
class WizardNav internal constructor(
    val step: WizardStep,
    /** The wizard was opened from the settings (not the first start): presets don't apply. */
    val manual: Boolean,
    val next: () -> Unit,
    val back: () -> Unit,
    val autoSkip: () -> Unit,
    /** Switches to the backup path with the chosen backup (restored after pairing). */
    val chooseBackup: (String) -> Unit,
    /** Changes the launcher settings (applies at once). */
    val update: ((LauncherSettings) -> LauncherSettings) -> Unit,
)

val LocalWizardNav = staticCompositionLocalOf<WizardNav?> { null }

/** The steps of the current path: new setup, or restore from a backup after pairing. */
internal fun wizardSteps(platform: PlatformUi, restoring: Boolean, manual: Boolean): List<WizardStep> =
    // From the settings the device is set up already: no choice between new and backup.
    if (!restoring) platform.wizardSteps.filter { !(manual && it == WizardStep.BACKUP) }
    else listOf(WizardStep.WELCOME, WizardStep.BACKUP, WizardStep.HOSTS, WizardStep.COMPANION, WizardStep.RESTORE) +
        platform.wizardDeviceSteps + WizardStep.FINISH

/**
 * The setup wizard, full screen: progress on top, the step (explanation and settings side by side,
 * stacked in portrait), Back / Skip / Next at the bottom. Every choice applies at once. [onDone]
 * when it is finished; the state is stored, so a restart continues at the same step.
 */
@Composable
fun SetupWizard(vm: LauncherViewModel, manual: Boolean, onDone: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val platform = PlatformUi.current
    val restoring = settings.setupRestore.isNotEmpty()
    val steps = remember(restoring) { wizardSteps(platform, restoring, manual) }
    val stored = steps.firstOrNull { it.name == settings.setupStep }
    // The step lives here while the wizard runs; it is stored for a restart as well.
    var current by remember { mutableStateOf(stored ?: steps.first()) }
    if (current !in steps) current = steps.first()
    var forward by remember { mutableStateOf(true) }
    val index = steps.indexOf(current).coerceAtLeast(0)

    fun go(to: Int, isForward: Boolean) {
        val target = steps.getOrNull(to) ?: return
        forward = isForward
        current = target
        vm.updateSettings { it.copy(setupStep = target.name) }
    }
    fun finish() {
        vm.updateSettings { it.copy(setupDone = true, setupStep = "", setupRestore = "") }
        onDone()
    }
    val next = { if (current == WizardStep.FINISH) finish() else go(index + 1, true) }
    val back = { go(index - 1, false) }
    val nav = remember(current, steps) {
        WizardNav(
            current, manual, next = next, back = back,
            autoSkip = { if (forward) go(steps.indexOf(current) + 1, true) else go(steps.indexOf(current) - 1, false) },
            chooseBackup = { address ->
                vm.updateSettings { it.copy(setupRestore = address, setupStep = WizardStep.HOSTS.name) }
                forward = true
                current = WizardStep.HOSTS
            },
            update = vm::updateSettings,
        )
    }

    // Start = next, B = previous step (never leaves the wizard).
    PadAction(PadButton.START) { next() }
    // Always taken, so B never leaves the wizard (or the app).
    BackHandler { if (index > 0) back() }

    CompositionLocalProvider(LocalWizardNav provides nav) {
        WizardFrame(steps, index, nav, settings, onNext = next, onBack = back)
    }
}

@Composable
private fun WizardFrame(
    steps: List<WizardStep>,
    index: Int,
    nav: WizardNav,
    settings: LauncherSettings,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val step = steps[index]
    val info = stepInfo(step)
    val colors = LauncherTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val landscape = maxWidth > maxHeight && maxWidth >= 720.dp
            val side = if (maxWidth < 600.dp) 20.dp else 40.dp
            // Like the settings: the room is used up to a limit, beyond it the wizard stays a
            // centred block (wide screens, small UI scale) instead of spreading out.
            val limit = (if (landscape) MAX_WIDTH_LANDSCAPE else MAX_WIDTH_PORTRAIT) + side * 2
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = limit).fillMaxWidth().weight(1f)) {
                    Progress(steps, index, Modifier.padding(horizontal = side).padding(top = 18.dp, bottom = 8.dp))
                    // Each step is its own composition: its state and focus start fresh.
                    key(step) {
                        if (landscape) {
                            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                                Column(Modifier.weight(0.38f).fillMaxHeight().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    StepHeader(info, big = true)
                                }
                                ScrollAreaColumn(
                                    modifier = Modifier.weight(0.62f).fillMaxHeight(),
                                    inner = Modifier.padding(top = 24.dp, bottom = 24.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        StepContent(step, settings)
                                    }
                                }
                            }
                        } else {
                            ScrollAreaColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                inner = Modifier.padding(horizontal = side).padding(top = 12.dp, bottom = 24.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StepHeader(info, big = false)
                                    Spacer(Modifier.height(4.dp))
                                    StepContent(step, settings)
                                }
                            }
                        }
                    }
                }
            }
        }
        BottomBar(step, index, onNext, onBack)
    }
}

/** Widest the wizard's content gets (without the side margins). */
private val MAX_WIDTH_LANDSCAPE = 1240.dp
private val MAX_WIDTH_PORTRAIT = 760.dp

/** Dots for every step (done, current, open) and "Step 4 of 14 · Controller". */
@Composable
private fun Progress(steps: List<WizardStep>, index: Int, modifier: Modifier) {
    val colors = LauncherTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            steps.indices.forEach { i ->
                val size = if (i == index) 12.dp else 8.dp
                Box(
                    Modifier.size(size).clip(CircleShape).background(
                        when {
                            i < index -> colors.accent
                            i == index -> colors.accent
                            else -> colors.surfaceRaised
                        },
                    ).then(if (i == index) Modifier.border(2.dp, colors.onSurface, CircleShape) else Modifier),
                )
            }
        }
        Text(
            stringResource(Res.string.wizard_progress, index + 1, steps.size, stringResource(stepInfo(steps[index]).title)),
            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepHeader(info: StepInfo, big: Boolean) {
    val colors = LauncherTheme.colors
    if (info.banner) {
        // The GameLodge banner (light lettering) on the dark tile the About page uses too.
        Box(
            Modifier.fillMaxWidth().height(if (big) 180.dp else 120.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF1A1E27), androidx.compose.ui.graphics.Color(0xFF0E1015)))),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                org.jetbrains.compose.resources.painterResource(Res.drawable.gamelodge_banner),
                contentDescription = stringResource(Res.string.launcher_name),
                modifier = Modifier.fillMaxHeight(),
            )
        }
    } else {
        val circle = if (big) 96.dp else 56.dp
        Box(Modifier.size(circle).clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
            Icon(info.icon, null, tint = colors.accent, modifier = Modifier.size(circle / 2))
        }
    }
    Text(
        stringResource(info.title),
        style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall, color = colors.onSurface,
    )
    Text(stringResource(info.text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceMuted)
}

@Composable
private fun BottomBar(step: WizardStep, index: Int, onNext: () -> Unit, onBack: () -> Unit) {
    val colors = LauncherTheme.colors
    val last = step == WizardStep.FINISH
    // Back and Next only (no button hints: the pad buttons work all the same; every step can be
    // left with Next unchanged, so there is no separate Skip).
    // The buttons line up with the content (same width limit), the bar spans the screen.
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val limit = (if (window.width > window.height) MAX_WIDTH_LANDSCAPE else MAX_WIDTH_PORTRAIT) + 80.dp
    Box(Modifier.fillMaxWidth().background(colors.surface), contentAlignment = Alignment.Center) {
    Row(
        Modifier.widthIn(max = limit).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (index > 0) LauncherButton(stringResource(Res.string.wizard_back), onClick = onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
        Spacer(Modifier.weight(1f))
        LauncherButton(
            stringResource(if (last) Res.string.wizard_finish else Res.string.wizard_next),
            onClick = onNext, primary = true,
            icon = if (last) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward,
            // Steps without choices of their own start on Next.
            modifier = Modifier.restoreFocusIf(step in FOCUS_NEXT, trigger = step),
        )
    }
    }
}

/** Steps whose content has nothing to focus first: Next gets the focus. */
private val FOCUS_NEXT = setOf(WizardStep.FINISH)

@Composable
private fun ColumnScope.StepContent(step: WizardStep, settings: LauncherSettings) {
    when (step) {
        WizardStep.WELCOME -> WelcomeStep(settings)
        WizardStep.BACKUP -> BackupStep(settings)
        WizardStep.CONTROLLER -> ControllerStep(settings)
        WizardStep.APPEARANCE -> AppearanceStep(settings)
        WizardStep.HOSTS -> HostsStep()
        WizardStep.COMPANION -> CompanionStep()
        WizardStep.METADATA -> MetadataStep()
        WizardStep.RESTORE -> RestoreStep(settings)
        WizardStep.HOME_ASSISTANT -> HomeAssistantStep(settings)
        WizardStep.FINISH -> FinishStep()
        else -> PlatformUi.current.WizardStepContent(step)
    }
}
