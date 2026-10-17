package dev.pact.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Launcher shortcuts: shown when long-pressing the app icon (and can be dragged to the
 * home screen from there), and the "Calendar" one can be pinned from Settings.
 */
object Shortcuts {
    const val CALENDAR = "calendar"
    const val NEW_EVENT = "new_event"
    const val NEW_TASK = "new_task"

    fun intent(context: Context, open: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_OPEN, open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun build(context: Context, id: String, short: String, long: String, icon: Int) =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent(context, id))
            .build()

    fun calendar(context: Context) = build(context, CALENDAR, "Calendar", "Open calendar", R.drawable.ic_shortcut_calendar)

    /** Publishes the long-press shortcuts. */
    fun publish(context: Context) {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    calendar(context),
                    build(context, NEW_EVENT, "New event", "Add a calendar event", R.drawable.ic_shortcut_event),
                    build(context, NEW_TASK, "New task", "Add a task", R.drawable.ic_shortcut_task),
                ),
            )
        }
    }

    /** Asks the launcher to pin a Calendar icon; false if the launcher can't. */
    fun pinCalendar(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) && ShortcutManagerCompat.requestPinShortcut(context, calendar(context), null)
}
