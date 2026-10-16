package dev.pact.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Android can't rename a launcher icon at runtime, so the manifest declares one
 * activity-alias per name and exactly one is enabled. Labels live in strings.xml.
 */
object LauncherNames {
    val names = listOf("Pact", "ToDo", "Tasks", "To-do list", "Checklist", "Reminders")

    private fun alias(name: String) = "dev.pact.app.Launcher" + name.filter { it.isLetterOrDigit() }

    fun apply(context: Context, name: String) {
        val chosen = if (name in names) name else names.first()
        val pm = context.packageManager
        // Enable the new entry first so the app always has a launcher icon.
        for (n in listOf(chosen) + names.filter { it != chosen }) {
            val state = if (n == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(ComponentName(context.packageName, alias(n)), state, PackageManager.DONT_KILL_APP)
        }
    }
}
