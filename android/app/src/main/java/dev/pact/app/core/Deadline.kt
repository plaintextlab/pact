package dev.pact.app.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Escalating deadline reminders; port of core/src/deadline.rs (see there for the rules).
 * Tested against spec/deadline_cases.json.
 */
data class DeadlineConfig(val dayStart: Int = 9, val dayEnd: Int = 21, val finalHours: Int = 1)

object Deadline {
    private const val OVERDUE_DAYS = 3L

    /** The moment a deadline falls due; without a time, the end of the waking day. */
    fun moment(date: LocalDate, time: LocalTime?, cfg: DeadlineConfig): LocalDateTime =
        date.atTime(time ?: LocalTime.of(cfg.dayEnd.coerceIn(0, 23), 0))

    /** Every reminder time, sorted, without duplicates. */
    fun alerts(deadline: LocalDateTime, cfg: DeadlineConfig): List<LocalDateTime> {
        val day = deadline.toLocalDate()
        fun atStart(d: LocalDate) = d.atTime(cfg.dayStart.coerceIn(0, 23), 0)
        val out = mutableListOf<LocalDateTime>()
        for (weeks in listOf(4L, 3L, 2L)) out += atStart(day.minusWeeks(weeks))
        for (days in 1L..7L) out += atStart(day.minusDays(days))
        val step = cfg.finalHours.coerceIn(1, 12).toLong()
        val floor = deadline.minusHours(24)
        var t = deadline.minusHours(step)
        while (t.isAfter(floor)) {
            if (t.hour >= cfg.dayStart && t.hour <= cfg.dayEnd) out += t
            t = t.minusHours(step)
        }
        out += deadline
        for (days in 1L..OVERDUE_DAYS) out += atStart(day.plusDays(days))
        return out.filter { !it.isAfter(deadline) || it.toLocalDate().isAfter(day) }.distinct().sorted()
    }

    /** "Deadline in 3 weeks", "… tomorrow", "… in 2 hours", "Deadline passed 2 days ago". */
    fun message(deadline: LocalDateTime, at: LocalDateTime): String {
        val left = Duration.between(at, deadline)
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), deadline.toLocalDate())
        if (left <= Duration.ZERO) {
            val ago = ChronoUnit.DAYS.between(deadline.toLocalDate(), at.toLocalDate())
            return when {
                ago == 0L && left > Duration.ofMinutes(-1) -> "Deadline now"
                ago == 0L -> "Deadline passed today"
                ago == 1L -> "Deadline passed yesterday"
                else -> "Deadline passed $ago days ago"
            }
        }
        return when {
            days >= 14 -> "Deadline in ${(days + 3) / 7} weeks"
            days >= 2 -> "Deadline in $days days"
            left >= Duration.ofHours(24) || (days == 1L && left > Duration.ofHours(12)) -> "Deadline tomorrow"
            left >= Duration.ofHours(1) -> {
                val h = (left.toMinutes() + 30) / 60
                "Deadline in $h hour" + if (h == 1L) "" else "s"
            }
            else -> "Deadline in ${maxOf(1L, left.toMinutes())} min"
        }
    }
}
