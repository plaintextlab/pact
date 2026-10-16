package dev.pact.app.data

import dev.pact.app.core.NlpConfig
import dev.pact.app.core.PactJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** Per-device settings; same keys as core/src/settings.rs plus Android-only ones. */
@Serializable
data class Settings(
    @SerialName("app_name") val appName: String = "Pact",
    val theme: String = "gruvbox",
    /** "system", "dark" or "light". */
    @SerialName("theme_mode") val themeMode: String = "system",
    val nlp: NlpConfig = NlpConfig(),
    @SerialName("reminder_presets") val reminderPresets: List<Long> = listOf(5, 10, 15, 20, 30, 45, 60, 120, 1440),
    @SerialName("default_reminders") val defaultReminders: List<Long> = emptyList(),
    @SerialName("snooze_minutes") val snoozeMinutes: Int = 10,
    val notifications: Boolean = true,
    @SerialName("git_remote") val gitRemote: String = "",
    @SerialName("git_branch") val gitBranch: String = "main",
    /** Pact's folder inside the (possibly shared) sync repository; "" = repository root. */
    @SerialName("git_folder") val gitFolder: String = "pact",
    @SerialName("git_username") val gitUsername: String = "",
    /** Personal access token for HTTPS remotes. Stored in app-private storage. */
    @SerialName("git_token") val gitToken: String = "",
    @SerialName("auto_sync_minutes") val autoSyncMinutes: Int = 30,
    @SerialName("sync_on_change") val syncOnChange: Boolean = true,
    @SerialName("show_completed") val showCompleted: Boolean = false,
    /** Launcher entry (activity-alias) in use, see LauncherNames. */
    @SerialName("launcher_name") val launcherName: String = "Pact",
    /** "system", "12h" or "24h". */
    @SerialName("time_format") val timeFormat: String = "system",
)

class SettingsRepo(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state
    val value: Settings get() = _state.value

    private fun load(): Settings = runCatching { PactJson.decodeFromString(Settings.serializer(), file.readText()) }.getOrDefault(Settings())

    @Synchronized
    fun update(f: (Settings) -> Settings) {
        val next = f(_state.value)
        if (next == _state.value) return
        file.parentFile?.mkdirs()
        file.writeText(PactJson.encodeToString(Settings.serializer(), next))
        _state.value = next
    }
}

fun formatOffset(minutes: Long): String = when {
    minutes == 0L -> "at due time"
    minutes % 1440 == 0L -> "${minutes / 1440} day" + if (minutes == 1440L) "" else "s"
    minutes % 60 == 0L -> "${minutes / 60} h"
    else -> "$minutes min"
}
