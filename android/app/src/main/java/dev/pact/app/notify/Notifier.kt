package dev.pact.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pact.app.MainActivity
import dev.pact.app.R
import dev.pact.app.core.Occurrence
import dev.pact.app.core.Task
import dev.pact.app.data.formatOffset
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Every notify() below runs only after canPost() checked POST_NOTIFICATIONS.
@SuppressLint("MissingPermission")
object Notifier {
    const val CH_REMINDERS = "reminders"
    const val CH_ALARMS = "alarms"
    const val CH_PINNED = "pinned"
    const val EXTRA_TASK = "task_id"
    const val EXTRA_EVENT = "event_id"
    const val EXTRA_DATE = "date"

    /** Notification key of one event occurrence; tasks use their id. */
    fun eventKey(eventId: String, date: LocalDate) = "event:$eventId:$date"

    /** (event id, occurrence date) from an [eventKey]. */
    fun parseEventKey(key: String): Pair<String, LocalDate>? {
        if (!key.startsWith("event:")) return null
        val rest = key.removePrefix("event:")
        val i = rest.lastIndexOf(':')
        if (i < 0) return null
        return rest.substring(0, i) to (runCatching { LocalDate.parse(rest.substring(i + 1)) }.getOrNull() ?: return null)
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task reminders"
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALARMS, "Urgent alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings like an alarm clock for urgent tasks"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_PINNED, "Pinned tasks", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tasks you pinned to the notification shade"
            setShowBadge(false)
        })
    }

    fun id(taskId: String, salt: Int = 0) = taskId.hashCode() * 31 + salt

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openTaskIntent(context: Context, taskId: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, taskId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, id(taskId, 1), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(context: Context, action: String, taskId: String, salt: Int): PendingIntent {
        val i = Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_TASK, taskId)
        return PendingIntent.getBroadcast(context, id(taskId, salt), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun dueText(t: Task): String {
        val d = t.date ?: return ""
        val today = LocalDate.now()
        val day = when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        return listOfNotNull(day, t.time?.let { dev.pact.app.ui.Format.time(it) }).joinToString(" ")
    }

    fun openEventIntent(context: Context, eventId: String, date: LocalDate): PendingIntent {
        val i = Intent(context, MainActivity::class.java).putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_DATE, date.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, id(eventKey(eventId, date), 1), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "Starts in 15 min · 10:00–11:00 · Room 4". */
    fun eventText(o: Occurrence, offset: Long): String {
        val f = dev.pact.app.ui.Format
        val whenText = if (o.allDay) "All day" else f.time(o.start.toLocalTime()) + "–" + f.time(o.end.toLocalTime())
        val lead = if (offset == 0L) "Now" else "Starts in ${formatOffset(offset)}"
        return listOf(lead, whenText, o.event.location).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun eventReminder(context: Context, o: Occurrence, offset: Long) {
        if (!canPost(context)) return
        val key = eventKey(o.event.id, o.date)
        val text = eventText(o, offset)
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pact)
            .setContentTitle(o.event.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (o.event.notes.isBlank()) text else "$text\n${o.event.notes}"))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openEventIntent(context, o.event.id, o.date))
            .addAction(0, "Snooze", action(context, ActionReceiver.SNOOZE, key, 3))
            .build()
        NotificationManagerCompat.from(context).notify(id(key), n)
    }

    fun eventAlarm(context: Context, o: Occurrence, offset: Long) =
        alarmFor(context, eventKey(o.event.id, o.date), o.event.title, eventText(o, offset), canComplete = false)

    /**
     * Escalating deadline reminder. Each one replaces the previous for the same task,
     * so the shade shows one up-to-date countdown instead of a pile.
     */
    fun deadline(context: Context, t: Task, deadline: java.time.LocalDateTime, now: java.time.LocalDateTime) {
        if (!canPost(context)) return
        val f = dev.pact.app.ui.Format
        val whenText = deadline.toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM")) + (t.deadlineClock?.let { " " + f.time(it) } ?: "")
        val text = dev.pact.app.core.Deadline.message(deadline, now) + " · " + whenText
        val close = java.time.Duration.between(now, deadline) <= java.time.Duration.ofHours(24)
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pact)
            .setContentTitle("⚑ ${t.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (t.notes.isBlank()) text else "$text\n${t.notes}"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (close) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openTaskIntent(context, t.id))
            .addAction(0, "Complete", action(context, ActionReceiver.COMPLETE, t.id, 2))
            .build()
        NotificationManagerCompat.from(context).notify(id(t.id, 11), n)
    }

    fun reminder(context: Context, t: Task, offset: Long) {
        if (!canPost(context)) return
        val text = if (offset == 0L) "Due now · ${dueText(t)}" else "In ${formatOffset(offset)} · ${dueText(t)}"
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_pact)
            .setContentTitle(t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (t.notes.isBlank()) text else "$text\n${t.notes}"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openTaskIntent(context, t.id))
            .addAction(0, "Complete", action(context, ActionReceiver.COMPLETE, t.id, 2))
            .addAction(0, "Snooze", action(context, ActionReceiver.SNOOZE, t.id, 3))
            .build()
        NotificationManagerCompat.from(context).notify(id(t.id), n)
    }

    /** Rings until dismissed (insistent alarm-channel sound) and opens the full-screen alarm. */
    fun alarm(context: Context, t: Task, offset: Long) {
        val text = if (offset == 0L) "Due now · ${dueText(t)}" else "In ${formatOffset(offset)} · ${dueText(t)}"
        alarmFor(context, t.id, t.title, text, canComplete = true)
    }

    /** Shared by tasks and events; [key] is a task id or an [eventKey]. */
    private fun alarmFor(context: Context, key: String, name: String, text: String, canComplete: Boolean) {
        if (!canPost(context)) return
        val full = Intent(context, AlarmActivity::class.java).putExtra(EXTRA_TASK, key).putExtra(AlarmActivity.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fullPi = PendingIntent.getActivity(context, id(key, 4), full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = "⏰ $name"
        // Our own layout instead of system action pills, which some phones truncate ("Di…", "Sno…").
        val small = RemoteViews(context.packageName, R.layout.notification_alarm_small).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, text)
        }
        val big = RemoteViews(context.packageName, R.layout.notification_alarm).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, text)
            setOnClickPendingIntent(R.id.btn_dismiss, action(context, ActionReceiver.DISMISS, key, 5))
            setOnClickPendingIntent(R.id.btn_snooze, action(context, ActionReceiver.SNOOZE, key, 3))
            if (canComplete) {
                setOnClickPendingIntent(R.id.btn_complete, action(context, ActionReceiver.COMPLETE, key, 2))
            } else {
                setViewVisibility(R.id.btn_complete, android.view.View.GONE)
            }
        }
        val n = NotificationCompat.Builder(context, CH_ALARMS)
            .setSmallIcon(R.drawable.ic_stat_pact)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setCustomHeadsUpContentView(big)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .setOngoing(true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(id(key), n)
    }

    /** An ongoing notification; if the system lets the user swipe it away, it is re-posted. */
    fun pinned(context: Context, t: Task) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_PINNED)
            .setSmallIcon(R.drawable.ic_stat_pact)
            .setContentTitle("📌 ${t.title}")
            .setContentText(dueText(t).ifEmpty { t.notes.lines().firstOrNull().orEmpty() })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openTaskIntent(context, t.id))
            .setDeleteIntent(action(context, ActionReceiver.REPOST, t.id, 6))
            .addAction(0, "Complete", action(context, ActionReceiver.COMPLETE, t.id, 2))
            .addAction(0, "Unpin", action(context, ActionReceiver.UNPIN, t.id, 7))
            .build()
        NotificationManagerCompat.from(context).notify(id(t.id, 9), n)
    }

    fun cancelPinned(context: Context, taskId: String) = NotificationManagerCompat.from(context).cancel(id(taskId, 9))
    fun cancel(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(id(taskId))
        NotificationManagerCompat.from(context).cancel(id(taskId, 11))
    }

    /** Shows pins for pinned tasks and removes stale ones. */
    fun syncPinned(context: Context, tasks: Collection<Task>) {
        for (t in tasks) {
            if (t.pinned && !t.completed && !t.deleted) pinned(context, t) else cancelPinned(context, t.id)
            // A finished task's deadline countdown is no longer relevant.
            if ((t.completed || t.deleted) && t.deadlineDate != null) NotificationManagerCompat.from(context).cancel(id(t.id, 11))
        }
    }
}
