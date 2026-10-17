package dev.pact.app.ui

import dev.pact.app.core.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Format {
    /** Set from Settings.timeFormat (or the phone's own setting) by PactApp. */
    @Volatile
    var use24h: Boolean = true

    private val h24 = DateTimeFormatter.ofPattern("HH:mm")
    private val h12 = DateTimeFormatter.ofPattern("h:mm a")

    fun time(t: LocalTime): String = t.format(if (use24h) h24 else h12)

    private val weekday = DateTimeFormatter.ofPattern("EEEE")
    private val short = DateTimeFormatter.ofPattern("EEE d MMM")
    private val long = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** "Today", "Tomorrow", "Wednesday", "Mon 2 Nov", "3 Mar 2027". */
    fun date(d: LocalDate, today: LocalDate = LocalDate.now()): String {
        val diff = ChronoUnit.DAYS.between(today, d)
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff == -1L -> "Yesterday"
            diff in 2..6 -> d.format(weekday)
            d.year == today.year -> d.format(short)
            else -> d.format(long)
        }
    }

    /** "Today 17:00", "Tomorrow", "in 5 days", "20 Nov", "2 days overdue". */
    fun deadline(t: Task, today: LocalDate = LocalDate.now()): String {
        val d = t.deadline ?: return ""
        val days = ChronoUnit.DAYS.between(today, d)
        val time = t.deadlineClock?.let { " " + time(it) } ?: ""
        return when {
            days < -1 -> "${-days} days overdue"
            days == -1L -> "overdue since yesterday"
            days == 0L -> "today$time"
            days == 1L -> "tomorrow$time"
            days < 14 -> "in $days days"
            else -> d.format(if (d.year == today.year) DateTimeFormatter.ofPattern("d MMM") else long)
        }
    }

    fun due(t: Task): String {
        val d = t.date ?: return ""
        return listOfNotNull(date(d), t.time?.let { time(it) }).joinToString(" ")
    }
}
