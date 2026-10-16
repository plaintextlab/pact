package dev.pact.app.core

import java.time.LocalDate
import java.time.YearMonth

// Port of core/src/recur.rs.
object Recur {
    fun weekday(d: LocalDate) = d.dayOfWeek.value
    fun isWorkday(d: LocalDate) = weekday(d) <= 5

    fun daysInMonth(y: Int, m: Int) = YearMonth.of(y, m).lengthOfMonth()

    fun ymdClamped(y: Int, m: Int, d: Int): LocalDate = LocalDate.of(y, m, d.coerceIn(1, daysInMonth(y, m)))

    fun addMonths(d: LocalDate, months: Long, day: Int): LocalDate {
        val total = d.year.toLong() * 12 + (d.monthValue - 1) + months
        return ymdClamped(Math.floorDiv(total, 12L).toInt(), Math.floorMod(total, 12L).toInt() + 1, day)
    }

    fun addBusinessDays(d: LocalDate, n: Long): LocalDate {
        var x = d
        var left = n
        while (left > 0) {
            x = x.plusDays(1)
            if (isWorkday(x)) left--
        }
        return x
    }

    /** First occurrence on or after [d]. */
    fun firstOnOrAfter(r: Recurrence, d: LocalDate): LocalDate = when {
        r.freq == Freq.DAILY && r.workdays -> generateSequence(d) { it.plusDays(1) }.first { isWorkday(it) }
        r.freq == Freq.WEEKLY && r.weekdays.isNotEmpty() -> generateSequence(d) { it.plusDays(1) }.first { weekday(it) in r.weekdays }
        r.freq == Freq.MONTHLY && r.monthDay != null -> {
            val c = ymdClamped(d.year, d.monthValue, r.monthDay)
            if (!c.isBefore(d)) c else addMonths(d, 1, r.monthDay)
        }
        r.freq == Freq.YEARLY && r.month != null && r.monthDay != null -> {
            val c = ymdClamped(d.year, r.month, r.monthDay)
            if (!c.isBefore(d)) c else ymdClamped(d.year + 1, r.month, r.monthDay)
        }
        else -> d
    }

    /** Next occurrence strictly after [d]. */
    fun nextAfter(r: Recurrence, d: LocalDate): LocalDate {
        val n = maxOf(r.interval, 1).toLong()
        return when (r.freq) {
            Freq.DAILY -> if (r.workdays) addBusinessDays(d, n) else d.plusDays(n)
            Freq.WEEKLY -> if (r.weekdays.isNotEmpty()) {
                var x = d.plusDays(1)
                while (weekday(x) !in r.weekdays) x = x.plusDays(1)
                if (weekday(x) <= weekday(d) && n > 1) x = x.plusWeeks(n - 1)
                x
            } else d.plusWeeks(n)
            Freq.MONTHLY -> addMonths(d, n, r.monthDay ?: d.dayOfMonth)
            Freq.YEARLY -> ymdClamped(d.year + n.toInt(), r.month ?: d.monthValue, r.monthDay ?: d.dayOfMonth)
        }
    }

    /** Next due date after completing a task due on [due]; never earlier than [today]. */
    fun advance(r: Recurrence, due: LocalDate, today: LocalDate): LocalDate {
        var x = nextAfter(r, due)
        while (x.isBefore(today)) x = nextAfter(r, x)
        return x
    }

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    fun describe(r: Recurrence): String {
        val n = maxOf(r.interval, 1)
        val unit = when (r.freq) {
            Freq.DAILY -> "day"; Freq.WEEKLY -> "week"; Freq.MONTHLY -> "month"; Freq.YEARLY -> "year"
        }
        if (r.freq == Freq.DAILY && r.workdays) return "every workday"
        if (r.freq == Freq.WEEKLY && r.weekdays == listOf(6, 7) && n == 1) return "every weekend"
        var s = when (n) {
            1 -> "every $unit"
            2 -> "every other $unit"
            else -> "every $n ${unit}s"
        }
        when {
            r.freq == Freq.WEEKLY && r.weekdays.isNotEmpty() -> {
                val days = r.weekdays.mapNotNull { DAY_NAMES.getOrNull(it - 1) }.joinToString(", ")
                s = if (n == 1) "every $days" else "$s on $days"
            }
            r.freq == Freq.MONTHLY && r.monthDay != null ->
                s = if (r.monthDay >= 31) "$s on the last day" else "$s on the ${ordinal(r.monthDay)}"
            r.freq == Freq.YEARLY && r.month != null && r.monthDay != null ->
                s = "$s on ${MONTH_NAMES[(r.month - 1).coerceIn(0, 11)]} ${r.monthDay}"
        }
        return s
    }
}
