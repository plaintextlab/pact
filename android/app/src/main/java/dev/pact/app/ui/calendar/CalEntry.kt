package dev.pact.app.ui.calendar

import androidx.compose.ui.graphics.Color
import dev.pact.app.core.Event
import dev.pact.app.core.Group
import dev.pact.app.core.Occurrence
import dev.pact.app.core.Palette
import dev.pact.app.core.Recur
import dev.pact.app.core.Task
import dev.pact.app.data.PhoneEvent
import dev.pact.app.data.Store
import dev.pact.app.ui.theme.color
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class EntryKind { EVENT, TASK, PHONE }

/** Anything shown in the calendar: a Pact event occurrence, a dated task or a phone event. */
data class CalEntry(
    val key: String,
    val kind: EntryKind,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val color: Color,
    val location: String = "",
    val repeats: Boolean = false,
    /** A future instance of a recurring task (not a stored task). */
    val projected: Boolean = false,
    val done: Boolean = false,
    val occurrence: Occurrence? = null,
    val task: Task? = null,
    val phone: PhoneEvent? = null,
) {
    val date: LocalDate get() = start.toLocalDate()

    /** Last day touched; ending exactly at midnight ends the day before. */
    val lastDay: LocalDate
        get() = if (!allDay && end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate().isAfter(date)) end.toLocalDate().minusDays(1) else end.toLocalDate()

    fun covers(d: LocalDate) = !date.isAfter(d) && !lastDay.isBefore(d)
}

/** All-day first, then by start, then title. */
val EntryOrder: Comparator<CalEntry> = compareBy<CalEntry> { !it.allDay }.thenBy { it.start }.thenBy { it.title }

fun eventColor(e: Event, groups: Map<String, Group>, p: Palette): Color =
    color(p.named(e.color ?: e.groupId?.let { groups[it]?.takeIf { g -> !g.deleted }?.color } ?: "primary"))

/** Builds every calendar entry touching [from]..[to]. */
fun buildEntries(
    store: Store,
    events: Map<String, Event>,
    tasks: Map<String, Task>,
    groups: Map<String, Group>,
    phone: List<PhoneEvent>,
    from: LocalDate,
    to: LocalDate,
    showTasks: Boolean,
    showCompleted: Boolean,
    p: Palette,
): List<CalEntry> {
    val out = mutableListOf<CalEntry>()
    for (o in store.agenda(from, to, events.values)) {
        out += CalEntry(
            key = "e:${o.event.id}:${o.date}", kind = EntryKind.EVENT, title = o.event.title,
            start = o.start, end = o.end, allDay = o.allDay, color = eventColor(o.event, groups, p),
            location = o.event.location, repeats = o.event.recurrence != null, occurrence = o,
        )
    }
    if (showTasks) {
        for (t in tasks.values) {
            if (t.deleted || (t.completed && !showCompleted)) continue
            val d = t.date ?: continue
            val c = t.groupId?.let { groups[it] }?.let { color(p.named(it.color)) } ?: color(p.muted)
            fun entry(day: LocalDate, projected: Boolean): CalEntry {
                val start = day.atTime(t.time ?: LocalTime.MIDNIGHT)
                return CalEntry(
                    key = "t:${t.id}:$day", kind = EntryKind.TASK, title = t.title, start = start,
                    end = if (t.time == null) start else start.plusMinutes(30), allDay = t.time == null, color = c,
                    repeats = t.recurrence != null, projected = projected, done = t.completed, task = t,
                )
            }
            if (!d.isBefore(from) && !d.isAfter(to)) out += entry(d, false)
            val r = t.recurrence
            if (r != null && !t.completed) {
                var x = Recur.nextAfter(r, d)
                var guard = 0
                while (!x.isAfter(to) && guard++ < 400 && (r.untilDate?.let { !x.isAfter(it) } ?: true)) {
                    if (!x.isBefore(from)) out += entry(x, true)
                    x = Recur.nextAfter(r, x)
                }
            }
        }
    }
    for (e in phone) {
        out += CalEntry(
            key = "p:${e.id}:${e.start}", kind = EntryKind.PHONE, title = e.title, start = e.start, end = e.end,
            allDay = e.allDay, color = Color(e.color or 0xFF000000.toInt()), location = e.location, phone = e,
        )
    }
    return out.sortedWith(EntryOrder)
}
