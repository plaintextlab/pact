package dev.pact.app.ui

import dev.pact.app.core.Task
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Format {
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

    fun due(t: Task): String {
        val d = t.date ?: return ""
        return listOfNotNull(date(d), t.dueTime).joinToString(" ")
    }
}
