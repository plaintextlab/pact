package dev.pact.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.pact.app.MainActivity
import dev.pact.app.PactApp
import dev.pact.app.core.Deadline
import dev.pact.app.core.DeadlineConfig
import dev.pact.app.core.Event
import dev.pact.app.data.deadlineConfig
import dev.pact.app.core.Occurrence
import dev.pact.app.core.Task
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** A reminder for a task or for one occurrence of an event. */
data class ReminderEvent(
    val at: LocalDateTime,
    val offset: Long,
    val urgent: Boolean,
    val task: Task? = null,
    val occurrence: Occurrence? = null,
    /** Set for escalating deadline reminders: the deadline itself. */
    val deadline: LocalDateTime? = null,
)

/**
 * Keeps a single exact alarm armed for the next reminder. When it fires, everything
 * due since the previous check is shown, then the next alarm is armed.
 */
object ReminderScheduler {
    private const val PREFS = "reminders"
    private const val LAST_CHECK = "last_check"
    private const val GRACE_MINUTES = 15L

    /** How far ahead event occurrences are expanded; a fallback wake-up re-checks after that. */
    private const val EVENT_HORIZON_DAYS = 35L

    fun events(
        tasks: Collection<Task>,
        evs: Collection<Event>,
        allDayAnchor: LocalTime,
        now: LocalDateTime = LocalDateTime.now(),
        deadlines: DeadlineConfig? = null,
    ): List<ReminderEvent> {
        val forTasks = tasks
            .filter { !it.deleted && !it.completed && it.date != null }
            .flatMap { t ->
                val due = t.date!!.atTime(t.time ?: allDayAnchor)
                val offsets = t.reminders + if (t.time != null || t.urgent) listOf(0L) else emptyList()
                offsets.distinct().map { ReminderEvent(due.minusMinutes(it), it, t.urgent, task = t) }
            }
        val from = now.toLocalDate().minusDays(1)
        val forEvents = evs
            .filter { !it.deleted && (it.reminders.isNotEmpty() || it.urgent) }
            .flatMap { e ->
                val offsets = (e.reminders + if (e.urgent) listOf(0L) else emptyList()).distinct()
                e.occurrences(from, now.toLocalDate().plusDays(EVENT_HORIZON_DAYS)).flatMap { o ->
                    val start = if (o.allDay) o.date.atTime(allDayAnchor) else o.start
                    offsets.map { ReminderEvent(start.minusMinutes(it), it, e.urgent, occurrence = o) }
                }
            }
        val forDeadlines = if (deadlines == null) emptyList() else tasks
            .filter { !it.deleted && !it.completed && it.deadline != null }
            .flatMap { t ->
                val due = Deadline.moment(t.deadline!!, t.deadlineClock, deadlines)
                Deadline.alerts(due, deadlines).map { ReminderEvent(it, 0, t.urgent && it == due, task = t, deadline = due) }
            }
        return (forTasks + forEvents + forDeadlines).sortedBy { it.at }
    }

    private fun anchor(app: PactApp) = LocalTime.of(app.settings.value.nlp.morningHour.coerceIn(0, 23), 0)

    private fun deadlineCfg(app: PactApp) = app.settings.value.let { if (it.deadlineReminders) it.deadlineConfig() else null }

    private fun millis(dt: LocalDateTime) = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun schedule(context: Context) {
        val app = context.applicationContext as PactApp
        val now = LocalDateTime.now()
        val next = events(app.store.liveTasks(), app.store.liveEvents(), anchor(app), now, deadlineCfg(app)).firstOrNull { it.at.isAfter(now) }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        if (next == null) {
            // Nothing within the horizon: wake up later to look again.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(now.plusDays(EVENT_HORIZON_DAYS - 5)), pi)
            return
        }
        val at = millis(next.at)
        when {
            !canExact(context) -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            next.urgent -> {
                // Alarm-clock alarms are the most reliable and show in the status bar.
                val show = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
            else -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Shows everything due in (last check, now]. */
    fun fireDue(context: Context) {
        val app = context.applicationContext as PactApp
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = LocalDateTime.now()
        val floor = now.minusMinutes(GRACE_MINUTES)
        val last = prefs.getLong(LAST_CHECK, 0L).let { ms ->
            if (ms == 0L) floor else LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault())
        }.let { if (it.isBefore(floor)) floor else it }
        if (app.settings.value.notifications) {
            for (e in events(app.store.liveTasks(), app.store.liveEvents(), anchor(app), now, deadlineCfg(app)).filter { it.at.isAfter(last) && !it.at.isAfter(now) }) {
                val t = e.task
                val o = e.occurrence
                val dl = e.deadline
                when {
                    t != null && dl != null && e.urgent -> Notifier.alarm(context, t, 0)
                    t != null && dl != null -> Notifier.deadline(context, t, dl, now)
                    t != null && e.urgent -> Notifier.alarm(context, t, e.offset)
                    t != null -> Notifier.reminder(context, t, e.offset)
                    o != null && e.urgent -> Notifier.eventAlarm(context, o, e.offset)
                    o != null -> Notifier.eventReminder(context, o, e.offset)
                }
            }
        }
        prefs.edit().putLong(LAST_CHECK, millis(now)).apply()
        schedule(context)
    }

    /** Re-shows one task's reminder later. */
    fun snooze(context: Context, taskId: String, minutes: Int) {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.SNOOZED).putExtra(Notifier.EXTRA_TASK, taskId)
        val pi = PendingIntent.getBroadcast(context, Notifier.id(taskId, 8), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(AlarmManager::class.java)
        if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SNOOZED -> {
                val app = context.applicationContext as PactApp
                val key = intent.getStringExtra(Notifier.EXTRA_TASK) ?: return
                val ev = Notifier.parseEventKey(key)
                if (ev != null) {
                    val o = app.store.event(ev.first)?.occurrences(ev.second, ev.second)?.firstOrNull() ?: return
                    if (o.event.urgent) Notifier.eventAlarm(context, o, 0) else Notifier.eventReminder(context, o, 0)
                    return
                }
                val t = app.store.task(key) ?: return
                if (t.completed) return
                if (t.urgent) Notifier.alarm(context, t, 0) else Notifier.reminder(context, t, 0)
            }
            else -> ReminderScheduler.fireDue(context)
        }
    }

    companion object {
        const val FIRE = "dev.pact.app.FIRE"
        const val SNOOZED = "dev.pact.app.SNOOZED"
    }
}

/** Re-arms reminders and pinned notifications after reboot, app update or time change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PactApp
        ReminderScheduler.schedule(context)
        Notifier.syncPinned(context, app.store.liveTasks())
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PactApp
        val id = intent.getStringExtra(Notifier.EXTRA_TASK) ?: return
        when (intent.action) {
            COMPLETE -> {
                Notifier.cancel(context, id)
                if (Notifier.parseEventKey(id) != null) return
                app.store.setCompleted(id, true)
                app.store.task(id)?.let { if (it.pinned && !it.completed) Notifier.pinned(context, it) else Notifier.cancelPinned(context, id) }
            }
            SNOOZE -> {
                Notifier.cancel(context, id)
                ReminderScheduler.snooze(context, id, app.settings.value.snoozeMinutes)
            }
            DISMISS -> Notifier.cancel(context, id)
            UNPIN -> app.store.task(id)?.let {
                app.store.saveTask(it.copy(pinned = false))
                Notifier.cancelPinned(context, id)
            }
            REPOST -> app.store.task(id)?.let { if (it.pinned && !it.completed) Notifier.pinned(context, it) }
        }
    }

    companion object {
        const val COMPLETE = "dev.pact.app.COMPLETE"
        const val SNOOZE = "dev.pact.app.SNOOZE"
        const val DISMISS = "dev.pact.app.DISMISS"
        const val UNPIN = "dev.pact.app.UNPIN"
        const val REPOST = "dev.pact.app.REPOST"
    }
}
