package dev.pact.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// Port of core/src/event.rs; field order matches so both apps write identical JSON.

@Serializable
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val location: String = "",
    /** The group doubles as the event's calendar (name and color). */
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("start_date") val startDate: String,
    /** null = all-day event. */
    @SerialName("start_time") val startTime: String? = null,
    /** Inclusive; equals [startDate] for single-day events. */
    @SerialName("end_date") val endDate: String,
    @SerialName("end_time") val endTime: String? = null,
    val recurrence: Recurrence? = null,
    /** Start dates of cancelled occurrences of a recurring event. */
    val exdates: List<String> = emptyList(),
    /** Minutes before start. */
    val reminders: List<Long> = emptyList(),
    /** Ring an alarm instead of a plain notification. */
    val urgent: Boolean = false,
    /** Overrides the group color: theme key or `#RRGGBB`. */
    val color: String? = null,
    val url: String = "",
    /** iCalendar UID, kept so re-importing a file updates instead of duplicating. */
    val uid: String? = null,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val allDay: Boolean get() = startTime == null

    val start: LocalDateTime
        get() = LocalDate.parse(startDate).atTime(startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.MIDNIGHT)

    val end: LocalDateTime
        get() {
            val d = runCatching { LocalDate.parse(endDate) }.getOrElse { start.toLocalDate() }
            val t = endTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            return if (t != null && !allDay) d.atTime(t) else d.atTime(23, 59, 59)
        }

    /** Sets start and end; all-day spans keep only their dates. */
    fun withSpan(start: LocalDateTime, end: LocalDateTime, allDay: Boolean): Event {
        val e = if (end.isBefore(start)) start else end
        return copy(
            startDate = start.toLocalDate().toString(),
            endDate = e.toLocalDate().toString(),
            startTime = if (allDay) null else start.toLocalTime().format(TIME_FMT),
            endTime = if (allDay) null else e.toLocalTime().format(TIME_FMT),
        )
    }

    private fun occurrenceAt(date: LocalDate): Occurrence {
        val first = start
        val s = date.atTime(first.toLocalTime())
        return Occurrence(this, s, s.plus(ChronoUnit.SECONDS.between(first, end), ChronoUnit.SECONDS), allDay)
    }

    /** Occurrences that touch any day in [from]..[to]. */
    fun occurrences(from: LocalDate, to: LocalDate): List<Occurrence> {
        val first = start
        val r = recurrence
        if (r == null) {
            val o = occurrenceAt(first.toLocalDate())
            return if (!o.date.isAfter(to) && !o.lastDay.isBefore(from)) listOf(o) else emptyList()
        }
        val until = r.untilDate
        val spanDays = maxOf(0L, ChronoUnit.DAYS.between(first.toLocalDate(), end.toLocalDate()))
        val ex = exdates.toHashSet()
        val out = mutableListOf<Occurrence>()
        var x = first.toLocalDate()
        var n = 0
        repeat(50_000) {
            if (x.isAfter(to) || (until != null && x.isAfter(until)) || (r.count != null && n >= r.count)) return out
            n++
            if (!x.plusDays(spanDays).isBefore(from) && x.toString() !in ex) out += occurrenceAt(x)
            x = Recur.nextAfter(r, x)
        }
        return out
    }

    /** First occurrence starting at or after [t]. */
    fun nextOccurrence(t: LocalDateTime): Occurrence? =
        occurrences(t.toLocalDate(), t.toLocalDate().plusDays(400)).firstOrNull { !it.start.isBefore(t) }

    companion object {
        /** A one-hour event, or an all-day one when [time] is null. */
        fun create(title: String, date: LocalDate, time: LocalTime?): Event {
            val base = Event(title = title, startDate = date.toString(), endDate = date.toString())
            return if (time == null) base else base.withSpan(date.atTime(time), date.atTime(time).plusHours(1), false)
        }
    }
}

/** One concrete occurrence of an event. */
data class Occurrence(val event: Event, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean) {
    val date: LocalDate get() = start.toLocalDate()

    /** Last calendar day touched (an event ending exactly at midnight ends the day before). */
    val lastDay: LocalDate
        get() = if (!allDay && end.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalDate().isAfter(start.toLocalDate())) end.toLocalDate().minusDays(1) else end.toLocalDate()

    fun covers(d: LocalDate) = !start.toLocalDate().isAfter(d) && !lastDay.isBefore(d)
}

/** All-day first, then by start time, then title. */
val OccurrenceOrder: Comparator<Occurrence> = compareBy<Occurrence> { !it.allDay }.thenBy { it.start }.thenBy { it.event.title }
