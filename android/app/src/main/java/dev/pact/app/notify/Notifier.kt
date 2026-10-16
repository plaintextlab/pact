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
        if (!canPost(context)) return
        val text = if (offset == 0L) "Due now · ${dueText(t)}" else "In ${formatOffset(offset)} · ${dueText(t)}"
        val full = Intent(context, AlarmActivity::class.java).putExtra(EXTRA_TASK, t.id).putExtra(AlarmActivity.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fullPi = PendingIntent.getActivity(context, id(t.id, 4), full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = "⏰ ${t.title}"
        // Our own layout instead of system action pills, which some phones truncate ("Di…", "Sno…").
        val small = RemoteViews(context.packageName, R.layout.notification_alarm_small).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, text)
        }
        val big = RemoteViews(context.packageName, R.layout.notification_alarm).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.text, text)
            setOnClickPendingIntent(R.id.btn_dismiss, action(context, ActionReceiver.DISMISS, t.id, 5))
            setOnClickPendingIntent(R.id.btn_snooze, action(context, ActionReceiver.SNOOZE, t.id, 3))
            setOnClickPendingIntent(R.id.btn_complete, action(context, ActionReceiver.COMPLETE, t.id, 2))
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
        NotificationManagerCompat.from(context).notify(id(t.id), n)
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
    fun cancel(context: Context, taskId: String) = NotificationManagerCompat.from(context).cancel(id(taskId))

    /** Shows pins for pinned tasks and removes stale ones. */
    fun syncPinned(context: Context, tasks: Collection<Task>) {
        for (t in tasks) {
            if (t.pinned && !t.completed && !t.deleted) pinned(context, t) else cancelPinned(context, t.id)
        }
    }
}
