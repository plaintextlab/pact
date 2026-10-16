package dev.pact.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pact.app.PactApp
import dev.pact.app.core.AttachmentKind
import dev.pact.app.core.Group
import dev.pact.app.core.Task
import dev.pact.app.data.QuickAddDefaults
import dev.pact.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

sealed interface View {
    data object Inbox : View
    data object Today : View
    data object Upcoming : View
    data object Calendar : View
    data object All : View
    data object Completed : View
    data class GroupView(val id: String) : View
    data class LabelView(val label: String) : View
}

sealed interface Screen {
    data object Home : Screen
    data class TaskDetail(val id: String) : Screen
    data object SettingsScreen : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as PactApp
    val store = app.store
    val settingsRepo = app.settings

    var view by mutableStateOf<View>(View.Today)
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var search by mutableStateOf("")
    var showQuickAdd by mutableStateOf(false)
    var calDay by mutableStateOf(LocalDate.now())
    var calMonth by mutableStateOf(YearMonth.now())
    val collapsed = mutableStateListOf<String>()
    var editingGroup by mutableStateOf<Group?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val screen: Screen get() = stack.last()

    private fun io(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { block() }.onFailure { _messages.tryEmit("⚠ ${it.message}") }
    }

    fun say(msg: String) = _messages.tryEmit(msg)

    fun openTask(id: String) {
        if (screen != Screen.TaskDetail(id)) stack += Screen.TaskDetail(id)
    }

    fun openSettings() {
        stack += Screen.SettingsScreen
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun defaults(): QuickAddDefaults {
        val reminders = settingsRepo.value.defaultReminders
        return when (val v = view) {
            is View.GroupView -> QuickAddDefaults(groupId = v.id, reminders = reminders)
            View.Today -> QuickAddDefaults(date = LocalDate.now(), reminders = reminders)
            View.Calendar -> QuickAddDefaults(date = calDay, reminders = reminders)
            else -> QuickAddDefaults(reminders = reminders)
        }
    }

    fun quickAdd(text: String, defaults: QuickAddDefaults = defaults()) = io {
        store.quickAdd(text, settingsRepo.value.nlp, defaults)?.let { t ->
            val due = Format.due(t)
            say(if (due.isEmpty()) "Added “${t.title}”" else "Added “${t.title}” · $due")
        }
    }

    fun setDone(id: String, done: Boolean) = io {
        val wasRecurring = store.task(id)?.let { it.recurrence != null && it.dueDate != null } ?: false
        store.setCompleted(id, done)
        if (done) store.task(id)?.let { say(if (wasRecurring) "Next: ${Format.due(it)}" else "Completed “${it.title}”") }
    }

    fun save(t: Task) = io { store.saveTask(t) }

    fun delete(id: String) = io {
        store.deleteTask(id)
        viewModelScope.launch { if (screen == Screen.TaskDetail(id)) back() }
    }

    fun saveGroup(g: Group) = io { store.saveGroup(g) }

    fun deleteGroup(id: String) = io {
        store.deleteGroup(id)
        if (view == View.GroupView(id)) view = View.Inbox
    }

    fun attach(taskId: String, uri: Uri, kind: AttachmentKind?) = io {
        store.attachUri(taskId, app.contentResolver, uri, kind)
    }

    fun attachFile(taskId: String, file: File, kind: AttachmentKind) = io {
        store.attachFile(taskId, file, kind)
        file.delete()
    }

    fun attachText(taskId: String, name: String, text: String) = io { store.attachText(taskId, name, text) }
    fun removeAttachment(taskId: String, attId: String) = io { store.removeAttachment(taskId, attId) }

    fun updateSettings(f: (Settings) -> Settings) {
        val before = settingsRepo.value
        settingsRepo.update(f)
        val after = settingsRepo.value
        app.applyClock(after)
        if (after.nlp != before.nlp || after.notifications != before.notifications) app.onDataChanged(local = false)
    }

    fun syncNow() {
        app.scope.launch(Dispatchers.IO) {
            if (app.runSync()) say("Synced: ${app.sync.value.status}") else say("Sync failed: ${app.sync.value.error}")
        }
    }

    fun toggleCollapsed(id: String) {
        if (!collapsed.remove(id)) collapsed += id
    }
}
