package io.github.thecodingdad.gamelodge.ui.shell

import io.github.thecodingdad.gamelodge.ui.components.handCursor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import io.github.thecodingdad.gamelodge.data.tasks.BackgroundTask
import io.github.thecodingdad.gamelodge.data.tasks.BackgroundTasks
import io.github.thecodingdad.gamelodge.resources.*
import io.github.thecodingdad.gamelodge.ui.components.Modal
import io.github.thecodingdad.gamelodge.ui.screens.settings.NavRow
import io.github.thecodingdad.gamelodge.ui.theme.LauncherTheme
import org.jetbrains.compose.resources.stringResource

/** Status bar: a small spinner while background work runs; tapping it lists the work. */
@Composable
fun TaskSpinner() {
    val tasks by BackgroundTasks.tasks.collectAsState()
    if (tasks.isEmpty()) return
    // Touch target like the other status icons; controllers reach the list through the quick menu.
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .focusProperties { canFocus = false }
            .handCursor()
            .clickable { BackgroundTasks.popupOpen = true }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = LauncherTheme.colors.accent, strokeWidth = 2.dp)
    }
}

/** Quick menu row opening the task list (shown only while work runs). */
@Composable
fun TasksRow() {
    val tasks by BackgroundTasks.tasks.collectAsState()
    if (tasks.isEmpty()) return
    NavRow(
        stringResource(Res.string.tasks_title), { BackgroundTasks.popupOpen = true },
        subtitle = taskTitle(tasks.first()) + if (tasks.size > 1) " +${tasks.size - 1}" else "",
        icon = Icons.Filled.Sync,
        trailingContent = { CircularProgressIndicator(Modifier.size(18.dp), color = LauncherTheme.colors.accent, strokeWidth = 2.dp) },
    )
}

/** The running tasks with their progress; closes by itself when the last one ends. */
@Composable
fun TasksPopup() {
    val tasks by BackgroundTasks.tasks.collectAsState()
    val popupOpen by BackgroundTasks.popupOpenFlow.collectAsState()
    LaunchedEffect(tasks.isEmpty()) { if (tasks.isEmpty()) BackgroundTasks.popupOpen = false }
    Modal(
        visible = popupOpen && tasks.isNotEmpty(),
        onDismiss = { BackgroundTasks.popupOpen = false },
        title = stringResource(Res.string.tasks_title),
        size = io.github.thecodingdad.gamelodge.ui.components.PopupSize.STANDARD,
    ) {
        val navigate = io.github.thecodingdad.gamelodge.ui.screens.settings.LocalSettingsNavigator.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tasks.forEach { task ->
                val target = settingsFor(task.id)
                io.github.thecodingdad.gamelodge.ui.components.FocusSurface(
                    onClick = {
                        if (target != null) {
                            BackgroundTasks.popupOpen = false
                            navigate(target, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), color = LauncherTheme.colors.surfaceRaised, focusScale = 1.02f,
                ) { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { TaskLine(task) } }
            }
        }
    }
}

/** Where a task is set up: tapping it in the list opens that page. */
private fun settingsFor(taskId: String): io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget? {
    fun to(id: io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId) = io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Page(id)
    return when {
        taskId == "metadata" || taskId == "artwork" -> to(io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId.METADATA)
        taskId == "host-sync" -> to(io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId.STREAMING_HOST)
        taskId == "pc-scan" -> to(io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsPageId.GAME_SOURCES)
        taskId.startsWith("applist-") || taskId.startsWith("boxart-") ->
            io.github.thecodingdad.gamelodge.ui.screens.settings.SettingsTarget.Host(taskId.substringAfter('-'))
        else -> null
    }
}

@Composable
private fun taskTitle(task: BackgroundTask): String =
    if (task.args.isEmpty()) stringResource(task.title) else stringResource(task.title, *task.args.toTypedArray())

@Composable
private fun TaskLine(task: BackgroundTask) {
    val colors = LauncherTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(taskTitle(task), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f))
            if (task.total > 0) {
                Text(stringResource(Res.string.task_count, task.done, task.total), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
            }
        }
        task.detail?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 2)
        }
        if (task.total > 0) {
            LinearProgressIndicator(progress = { task.done.toFloat() / task.total }, modifier = Modifier.fillMaxWidth(), color = colors.accent)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.accent)
        }
    }
}
