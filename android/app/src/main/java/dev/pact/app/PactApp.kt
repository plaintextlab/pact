package dev.pact.app

import android.app.Application
import android.text.format.DateFormat
import androidx.glance.appwidget.updateAll
import dev.pact.app.data.Settings
import dev.pact.app.data.SettingsRepo
import dev.pact.app.data.Store
import dev.pact.app.notify.Notifier
import dev.pact.app.notify.ReminderScheduler
import dev.pact.app.sync.GitSync
import dev.pact.app.sync.SyncWorker
import dev.pact.app.ui.Format
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

    /** The git repository used for sync; may be shared with other apps. Pact's data lives in a folder of it. */
    val repoRoot: File get() = File(filesDir, "data")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync
    private val syncLock = Mutex()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepo(File(filesDir, "settings.json"))
        applyClock()
        val folder = settings.value.gitFolder
        runCatching { Store.migrateIntoFolder(repoRoot, folder) }
        store = Store(Store.dataDir(repoRoot, folder))
        Notifier.createChannels(this)
        scope.launch(Dispatchers.IO) { store.purgeTombstones() }
        scope.launch { store.changes.debounce(400).collect { onDataChanged(local = true) } }
        // Clock format, launcher icon/name and widgets follow the settings.
        scope.launch {
            settings.state.distinctUntilChangedBy { Triple(it.timeFormat, it.theme, it.themeMode) to it.launcherName }.collect { s ->
                applyClock(s)
                runCatching { LauncherNames.apply(this@PactApp, s.launcherName, s.theme) }
                runCatching { PactWidget().updateAll(this@PactApp) }
                Notifier.syncPinned(this@PactApp, store.liveTasks())
            }
        }
        scope.launch {
            settings.state.distinctUntilChangedBy { it.autoSyncMinutes to it.gitRemote }.collect {
                SyncWorker.schedulePeriodic(this@PactApp, if (it.gitRemote.isBlank()) 0 else it.autoSyncMinutes)
            }
        }
        ReminderScheduler.schedule(this)
        Notifier.syncPinned(this, store.liveTasks())
        if (settings.value.gitRemote.isNotBlank()) SyncWorker.soon(this, 2)
    }

    /** Sets the app-wide 12/24-hour clock from the setting (or the phone's own choice). */
    fun applyClock(s: Settings = settings.value) {
        Format.use24h = when (s.timeFormat) {
            "12h" -> false
            "24h" -> true
            else -> DateFormat.is24HourFormat(this)
        }
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
            val msg = GitSync.sync(repoRoot, settings.value)
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
