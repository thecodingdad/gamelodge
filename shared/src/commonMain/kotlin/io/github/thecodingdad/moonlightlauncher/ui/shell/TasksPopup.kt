package io.github.thecodingdad.moonlightlauncher.ui.shell

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
import io.github.thecodingdad.moonlightlauncher.data.tasks.BackgroundTask
import io.github.thecodingdad.moonlightlauncher.data.tasks.BackgroundTasks
import io.github.thecodingdad.moonlightlauncher.resources.*
import io.github.thecodingdad.moonlightlauncher.ui.components.Modal
import io.github.thecodingdad.moonlightlauncher.ui.screens.settings.NavRow
import io.github.thecodingdad.moonlightlauncher.ui.theme.LauncherTheme
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
    LaunchedEffect(tasks.isEmpty()) { if (tasks.isEmpty()) BackgroundTasks.popupOpen = false }
    Modal(
        visible = BackgroundTasks.popupOpen && tasks.isNotEmpty(),
        onDismiss = { BackgroundTasks.popupOpen = false },
        title = stringResource(Res.string.tasks_title),
        maxWidth = 600.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            tasks.forEach { TaskLine(it) }
        }
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
