package dev.pact.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import dev.pact.app.data.SettingsRepo
import dev.pact.app.data.Store
import dev.pact.app.notify.Notifier
import dev.pact.app.notify.ReminderScheduler
import dev.pact.app.sync.GitSync
import dev.pact.app.sync.SyncWorker
import dev.pact.app.widget.PactWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class SyncState(val busy: Boolean = false, val status: String = "not synced yet", val error: String? = null, val lastOk: Long? = null)

class PactApp : Application() {
    lateinit var store: Store
        private set
    lateinit var settings: SettingsRepo
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync
    private val syncLock = Mutex()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepo(File(filesDir, "settings.json"))
        store = Store(File(filesDir, "data"))
        Notifier.createChannels(this)
        scope.launch(Dispatchers.IO) { store.purgeTombstones() }
        scope.launch { store.changes.debounce(400).collect { onDataChanged(local = true) } }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.autoSyncMinutes to it.gitRemote }.collect {
                SyncWorker.schedulePeriodic(this@PactApp, if (it.gitRemote.isBlank()) 0 else it.autoSyncMinutes)
            }
        }
        ReminderScheduler.schedule(this)
        Notifier.syncPinned(this, store.liveTasks())
        if (settings.value.gitRemote.isNotBlank()) SyncWorker.soon(this, 2)
    }

    /** Re-arms reminders, refreshes pins and widgets; queues a sync for local edits. */
    fun onDataChanged(local: Boolean) {
        ReminderScheduler.schedule(this)
        Notifier.syncPinned(this, store.tasks.value.values)
        scope.launch { runCatching { PactWidget().updateAll(this@PactApp) } }
        val s = settings.value
        if (local && s.syncOnChange && s.gitRemote.isNotBlank()) SyncWorker.soon(this)
    }

    /** Runs one git sync; safe to call from any thread. Returns false on failure. */
    suspend fun runSync(): Boolean = syncLock.withLock {
        _sync.value = _sync.value.copy(busy = true, status = "syncing…")
        try {
            val msg = GitSync.sync(store.root, settings.value)
            store.reload()
            onDataChanged(local = false)
            _sync.value = SyncState(busy = false, status = msg, error = null, lastOk = System.currentTimeMillis())
            true
        } catch (e: Exception) {
            _sync.value = _sync.value.copy(busy = false, status = "sync failed", error = e.message ?: e.toString())
            false
        }
    }
}
