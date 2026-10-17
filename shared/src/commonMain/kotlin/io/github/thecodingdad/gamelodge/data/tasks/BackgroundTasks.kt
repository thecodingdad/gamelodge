package io.github.thecodingdad.gamelodge.data.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.StringResource

/** Work running in the background (sync, downloads, scans), shown in the status bar and its popup. */
data class BackgroundTask(
    val id: String,
    val title: StringResource,
    val args: List<Any> = emptyList(),
    val done: Int = 0,
    /** 0: no count known (indeterminate). */
    val total: Int = 0,
    /** Detail line, e.g. the store being scanned. */
    val detail: String? = null,
)

/**
 * Registry of running background work. Whoever does the work reports it ([set] while it runs,
 * [remove] when done); the UI shows a spinner while anything is listed.
 */
object BackgroundTasks {
    private val _tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    val tasks: StateFlow<List<BackgroundTask>> = _tasks.asStateFlow()

    /** The task list popup is open (status bar spinner, quick menu). */
    var popupOpen by mutableStateOf(false)

    @Synchronized
    fun set(task: BackgroundTask) {
        val list = _tasks.value
        val index = list.indexOfFirst { it.id == task.id }
        _tasks.value = if (index < 0) list + task else list.toMutableList().also { it[index] = task }
    }

    @Synchronized
    fun remove(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
    }

    /** Runs [block] as task [id]; it reports progress through the given function. */
    suspend fun <T> track(
        id: String,
        title: StringResource,
        args: List<Any> = emptyList(),
        block: suspend (progress: (done: Int, total: Int, detail: String?) -> Unit) -> T,
    ): T {
        set(BackgroundTask(id, title, args))
        return try {
            block { done, total, detail -> set(BackgroundTask(id, title, args, done, total, detail)) }
        } finally {
            remove(id)
        }
    }
}
