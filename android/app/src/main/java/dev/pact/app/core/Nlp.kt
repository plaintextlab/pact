package dev.pact.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Port of core/src/nlp.rs: same patterns, same order. Both are checked against
// spec/nlp_cases.json (see NlpCasesTest).

@Serializable
data class NlpConfig(
    @SerialName("next_week_day") val nextWeekDay: Int = 1,
    @SerialName("next_period_same_day") val nextPeriodSameDay: Boolean = false,
    @SerialName("month_first") val monthFirst: Boolean = false,
    @SerialName("week_start") val weekStart: Int = 1,
    @SerialName("morning_hour") val morningHour: Int = 9,
    @SerialName("afternoon_hour") val afternoonHour: Int = 14,
    @SerialName("evening_hour") val eveningHour: Int = 18,
    @SerialName("night_hour") val nightHour: Int = 21,
    @SerialName("end_of_day_hour") val endOfDayHour: Int = 17,
)

data class Parsed(
    val title: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val recurrence: Recurrence? = null,
    val priority: Int? = null,
    val labels: List<String> = emptyList(),
    val group: String? = null,
    val reminders: List<Long> = emptyList(),
    val urgent: Boolean = false,
    /** The text named a day (not just a time or a repeat rule). */
    val dateExplicit: Boolean = false,
    /** From `deadline <when>` or `{<when>}`. */
    val deadline: LocalDate? = null,
    val deadlineTime: LocalTime? = null,
)

/** What quick add understood for a calendar event; start/end times null = all-day. */
data class ParsedEvent(
    val title: String,
    val date: LocalDate,
    val startTime: LocalTime?,
    val endDate: LocalDate,
    val endTime: LocalTime?,
    val recurrence: Recurrence?,
    val group: String?,
    val reminders: List<Long>,
    val urgent: Boolean,
    val dateExplicit: Boolean,
)

object Nlp {
    private const val WD = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun"
    private const val WD_BARE = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri"
    private const val MONTH = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    private const val NUM = """\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|(?:a\s+)?couple(?:\s+of)?|(?:a\s+)?few"""
    private const val UNIT = "minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|wks|wk|w|fortnights|fortnight|months|month|mos|mo|years|year|yrs|yr|y"

    private fun re(p: String) = Regex(
        p.replace("<WD>", WD).replace("<WDB>", WD_BARE).replace("<MONTH>", MONTH).replace("<NUM>", NUM).replace("<UNIT>", UNIT),
        RegexOption.IGNORE_CASE,
    )

    private val DL_BRACES = re("""\{([^}]*)\}""")
    private val DL_WORD = re("""\bdeadline\s*:?\s+([^#@{!]+)""")
    private val GROUP = re("""(?:^|\s)#([\p{L}\p{N}_][\p{L}\p{N}_\-/]*)""")
    private val LABEL = re("""(?:^|\s)@([\p{L}_][\p{L}\p{N}_\-]*)""")
    private val PRIORITY = re("""(?:^|\s)(?:p|!!)([1-4])\b""")
    private val PRIORITY_WORD = re("""\b(?:priority|prio)\s+(high|medium|med|low|none|[1-4])\b""")
    private val REMIND = re("""\b(remind(?:\s+me)?|reminder|alert|alarm|notify(?:\s+me)?)\s+(<NUM>|half\s+an?)\s*(minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|w)\s+(?:before|early|earlier|prior|ahead)\b""")
    private val REMIND_SHORT = re("""(?:^|\s)!(\d+)\s*(m|min|h|d)\b""")
    private val URGENT = re("""(?:^|\s)(?:!urgent|!alarm|!!)(?:\s|$)""")

    private val R_OTHER = re("""\bevery\s+other\s+(day|week|month|year)\b""")
    private val R_EVERY_N = re("""\bevery\s+(\d+)\s+(days?|weeks?|months?|years?)\b""")
    private val R_WORKDAYS = re("""\b(?:every\s+(?:week\s?day|work(?:ing)?\s?day|business\s+day)s?|on\s+weekdays|weekdays\s+only)\b""")
    private val R_WEEKEND = re("""\bevery\s+weekend\b""")
    private val R_WEEKDAYS = re("""\bevery\s+((?:<WD>)\b(?:\s*(?:,|and|&|\+)\s*(?:<WD>)\b)*)""")
    private val R_MONTH_LAST = re("""\bevery\s+(?:the\s+)?(?:last\s+day|end)\s+of\s+(?:the\s+)?month\b""")
    private val R_EVERY_UNIT = re("""\bevery\s+(day|morning|afternoon|evening|night|week|month|year)\b""")
    private val R_ADVERB = re("""\b(daily|weekly|monthly|yearly|annually|nightly|fortnightly|biweekly)\b""")
    private val R_YEARLY_MD = re("""\bevery\s+(<MONTH>)\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
    private val R_YEARLY_DM = re("""\bevery\s+(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(<MONTH>)\b""")
    private val R_MONTHDAY = re("""\bevery\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?(?:\s+(?:of\s+)?(?:the\s+|each\s+|every\s+)?month)?\b""")
    private val ONE_WD = re("""\b(<WD>)\b""")

    private val T_AMPM = re("""(?:\bat\s+|@\s*)?\b(\d{1,2})(?:[:.](\d{2}))?\s*(am\b|pm\b|a\.m\.|p\.m\.)""")
    private val T_24 = re("""(?:\bat\s+|@\s*)?\b([01]?\d|2[0-3])[:h]([0-5]\d)\b""")
    private val T_AT = re("""\bat\s+(\d{1,2})\b""")
    private val T_NOON = re("""\b(?:at\s+)?(noon|midday|midnight)\b""")
    private val T_TONIGHT = re("""\b(?:tonight|tonite)\b""")
    private val T_THIS_PART = re("""\bthis\s+(morning|afternoon|evening)\b""")
    private val T_PART = re("""\b(?:in\s+the\s+|at\s+)?(morning|afternoon|evening)\b|\bat\s+(night)\b""")
    private val T_EOD = re("""\b(?:by\s+)?(?:eod|end\s+of\s+(?:the\s+)?day)\b""")

    private val D_BUSINESS = re("""\b(?:in|after|within)\s+(<NUM>)\s+(?:(?:working|business|work)\s+days?|workdays?|weekdays?)\b""")
    private val D_BUSINESS_SUFFIX = re("""\b(<NUM>)\s+(?:(?:working|business|work)\s+days?|workdays?|weekdays?)\s+(?:from\s+(?:now|today)|later)\b""")
    private val D_HALF_HOUR = re("""\bin\s+(?:half\s+an?\s+hour|a\s+half\s+hour)\b""")
    private val D_HOUR_HALF = re("""\bin\s+(?:an?|one)\s+hour\s+and\s+a\s+half\b""")
    private val D_REL = re("""\b(?:in|after|within)\s+(<NUM>)\s+(<UNIT>)\b""")
    private val D_REL_SUFFIX = re("""\b(<NUM>)\s+(<UNIT>)\s+(?:from\s+(?:now|today)|later|hence)\b""")

    private val W_NEXT_WEEK_DAY = re("""\bnext\s+week\s+(?:on\s+)?(<WD>)\b|\b(?:on\s+)?(<WD>)\s+(?:of\s+)?next\s+week\b""")
    private val N_DAY_AFTER = re("""\b(?:the\s+)?day\s+after\s+(?:tomorrow|tmrw?)\b""")
    private val N_TODAY = re("""\b(?:today|tod|tdy)\b""")
    private val N_TOMORROW = re("""\b(?:tomorrow|tmrw|tmr|tmw|tomorow|tommorow|tommorrow)\b""")
    private val N_NEXT_WEEKEND = re("""\bnext\s+weekend\b""")
    private val N_WEEKEND = re("""\b(?:(?:this|the|on\s+the)\s+)?weekend\b""")
    private val N_EOW = re("""\b(?:by\s+)?(?:end\s+of\s+(?:the\s+)?week|eow)\b""")
    private val N_END_NEXT_MONTH = re("""\bend\s+of\s+next\s+month\b""")
    private val N_EOM = re("""\b(?:by\s+)?(?:end\s+of\s+(?:the\s+)?month|eom)\b""")
    private val N_EOY = re("""\b(?:by\s+)?(?:end\s+of\s+(?:the\s+)?year|eoy)\b""")
    private val N_NEXT_WEEK = re("""\b(?:(start|beginning)\s+of\s+)?next\s+week\b""")
    private val N_NEXT_MONTH = re("""\b(?:(start|beginning)\s+of\s+)?next\s+month\b""")
    private val N_NEXT_YEAR = re("""\b(?:(start|beginning)\s+of\s+)?next\s+year\b""")
    private val N_MID_MONTH = re("""\b(?:mid|middle\s+of)(?:\s+the)?[\s-]+month\b""")

    private val W_NEXT = re("""\bnext\s+(<WD>)\b""")
    private val W_THIS = re("""\b(?:this\s+coming|this|coming|on|by|until|till|before)\s+(<WD>)\b""")
    private val W_BARE = re("""\b(<WDB>)\b""")

    private val A_ISO = re("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val A_DM = re("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(<MONTH>)\b\.?(?:,?\s+(\d{4})\b)?""")
    private val A_MD = re("""\b(?:on\s+)?(<MONTH>)\.?\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4})\b)?""")
    private val A_NUM = re("""\b(\d{1,2})[/.](\d{1,2})(?:[/.](\d{4}|\d{2}))?\b""")
    private val A_ON_THE = re("""\bon\s+the\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
    private val A_ORD = re("""\b(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\b""")
    private val A_IN_MONTH = re("""\b(?:in|during|by)\s+(<MONTH>)\b""")

    private val TRAIL = re("""(?:^|\s)(?:on|at|by|due|in|from|starting|until|till|before)$|[\s,;:\-–]+$""")
    private val LEAD = re("""^(?:due|on|at)\s+|^[\s,;:\-–]+""")

    /** Working text: matches are blanked out with spaces so offsets stay valid. */
    private class Text(var s: String) {
        fun <T : Any> take(re: Regex, f: (MatchResult) -> T?): T? {
            for (m in re.findAll(s)) {
                val v = f(m) ?: continue
                s = s.replaceRange(m.range, " ".repeat(m.range.last - m.range.first + 1))
                return v
            }
            return null
        }
    }

    private fun MatchResult.g(i: Int): String? = groups[i]?.value
    private fun MatchResult.lc(i: Int): String? = g(i)?.lowercase()
    private fun MatchResult.int(i: Int): Int? = g(i)?.toIntOrNull()

    private fun hm(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null

    private fun num(s: String): Long? {
        val t = s.lowercase().trim().split(Regex("\\s+")).joinToString(" ")
        t.toLongOrNull()?.let { return it }
        return when (t) {
            "a", "an", "one" -> 1
            "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "six" -> 6
            "seven" -> 7; "eight" -> 8; "nine" -> 9; "ten" -> 10; "eleven" -> 11; "twelve" -> 12
            "couple", "a couple", "couple of", "a couple of" -> 2
            "few", "a few" -> 3
            else -> null
        }
    }

    private fun weekdayNum(s: String): Int? = when (s.lowercase().take(2)) {
        "mo" -> 1; "tu" -> 2; "we" -> 3; "th" -> 4; "fr" -> 5; "sa" -> 6; "su" -> 7; else -> null
    }

    private val MONTH_KEYS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private fun monthNum(s: String): Int? = MONTH_KEYS.indexOfFirst { s.lowercase().startsWith(it) }.takeIf { it >= 0 }?.plus(1)

    private sealed interface Span
    private data class Minutes(val n: Long) : Span
    private data class Days(val n: Long) : Span
    private data class Months(val n: Long) : Span

    private fun unitSpan(unit: String, n: Long): Span? {
        val u = unit.lowercase()
        return when {
            u == "m" || u.startsWith("mi") -> Minutes(n)
            u.startsWith("h") -> Minutes(n * 60)
            u.startsWith("d") -> Days(n)
            u.startsWith("w") -> Days(n * 7)
            u.startsWith("f") -> Days(n * 14)
            u.startsWith("mo") -> Months(n)
            u.startsWith("y") -> Months(n * 12)
            else -> null
        }
    }

    private fun reminderMinutes(u: String): Long {
        val x = u.lowercase()
        return when {
            x.startsWith("h") -> 60
            x.startsWith("d") -> 1440
            x.startsWith("w") -> 10080
            else -> 1
        }
    }

    private fun startOfWeek(d: LocalDate, weekStart: Int): LocalDate = d.minusDays(((Recur.weekday(d) + 7 - weekStart) % 7).toLong())

    private fun inNextWeek(today: LocalDate, wd: Int, weekStart: Int): LocalDate =
        startOfWeek(today, weekStart).plusDays((7 + (wd + 7 - weekStart) % 7).toLong())

    private fun upcoming(today: LocalDate, wd: Int): LocalDate = today.plusDays(((wd + 7 - Recur.weekday(today)) % 7).toLong())

    private fun dateOrNull(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun upcomingMd(today: LocalDate, m: Int, d: Int, year: Int?): LocalDate? {
        if (year != null) return dateOrNull(year, m, d)
        return (0 until 8).mapNotNull { dateOrNull(today.year + it, m, d) }.firstOrNull { !it.isBefore(today) }
    }

    private fun upcomingDayOfMonth(today: LocalDate, d: Int): LocalDate? {
        if (d !in 1..31) return null
        return (0L until 13L).map { Recur.addMonths(today, it, 1) }
            .filter { d <= Recur.daysInMonth(it.year, it.monthValue) }
            .map { Recur.ymdClamped(it.year, it.monthValue, d) }
            .firstOrNull { !it.isBefore(today) }
    }

    /** (valid, year): a missing year is valid and null; an unparsable one is invalid. */
    private fun yearOf(m: MatchResult, i: Int): Pair<Boolean, Int?> {
        val s = m.g(i) ?: return true to null
        val y = s.toIntOrNull() ?: return false to null
        return true to (if (y < 100) 2000 + y else y)
    }

    /** A deadline phrase like "dec 15 5pm"; it must start with the date. */
    private fun deadlineOf(s: String, now: LocalDateTime, cfg: NlpConfig): Parsed? {
        val p = parse(s, now, cfg)
        if (p.date == null || !(p.dateExplicit || p.time != null)) return null
        if (p.title.isNotEmpty() && s.trimStart().lowercase().startsWith(p.title.lowercase())) return null
        return p
    }

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now(), cfg: NlpConfig = NlpConfig()): Parsed {
        val today = now.toLocalDate()
        val t = Text(input)
        // The deadline comes out first so its date isn't taken as the due date.
        val dl = t.take(DL_BRACES) { deadlineOf(it.g(1)!!, now, cfg) } ?: t.take(DL_WORD) { deadlineOf(it.g(1)!!, now, cfg) }
        val group = t.take(GROUP) { it.g(1) }
        val labels = mutableListOf<String>()
        while (true) labels += t.take(LABEL) { it.g(1) } ?: break
        val priority = t.take(PRIORITY) { it.int(1) } ?: t.take(PRIORITY_WORD) {
            when (val v = it.lc(1)) {
                "high" -> 1; "medium", "med" -> 2; "low" -> 3; "none" -> 4
                else -> v?.toIntOrNull()
            }
        }

        val reminders = mutableListOf<Long>()
        var urgent = false
        while (true) {
            val hit = t.take(REMIND) {
                val mult = reminderMinutes(it.g(3)!!)
                val n = if (it.lc(2)!!.startsWith("half")) mult / 2 else (num(it.g(2)!!) ?: return@take null) * mult
                n to it.lc(1)!!.startsWith("alarm")
            } ?: t.take(REMIND_SHORT) { m -> m.g(1)!!.toLongOrNull()?.let { it * reminderMinutes(m.g(2)!!) to false } }
            ?: break
            if (hit.first !in reminders) reminders += hit.first
            urgent = urgent || hit.second
        }
        if (t.take(URGENT) { true } != null) urgent = true

        var recTime: LocalTime? = null
        val recurrence = parseRecurrence(t, cfg) { recTime = it }

        val part = { s: String ->
            hm(
                when (s) {
                    "morning" -> cfg.morningHour
                    "afternoon" -> cfg.afternoonHour
                    "evening" -> cfg.eveningHour
                    else -> cfg.nightHour
                }, 0,
            )
        }
        var todayHint = false
        var time: LocalTime? = t.take(T_AMPM) {
            val h = it.int(1) ?: return@take null
            val m = if (it.g(2) != null) it.int(2) ?: return@take null else 0
            if (h == 0 || h > 12 || m > 59) return@take null
            val pm = it.lc(3)!!.startsWith("p")
            hm(if (pm) h % 12 + 12 else h % 12, m)
        }
        if (time == null) time = t.take(T_24) { hm(it.int(1) ?: return@take null, it.int(2) ?: return@take null) }
        if (time == null) time = t.take(T_AT) {
            // "at 5" means 17:00; 7 and up are taken literally.
            val h = it.int(1) ?: return@take null
            if (h > 23) null else hm(if (h in 1..6) h + 12 else h, 0)
        }
        if (time == null) time = t.take(T_NOON) { if (it.lc(1) == "midnight") hm(23, 59) else hm(12, 0) }
        if (time == null) {
            time = t.take(T_TONIGHT) { hm(cfg.nightHour, 0) }
            todayHint = time != null
        }
        if (time == null) {
            time = t.take(T_THIS_PART) { part(it.lc(1)!!) }
            todayHint = time != null
        }
        if (time == null) time = t.take(T_PART) { part(it.lc(1) ?: it.lc(2) ?: return@take null) }
        if (time == null) time = t.take(T_EOD) { hm(cfg.endOfDayHour, 0) }

        var date: LocalDate? = (t.take(D_BUSINESS) { num(it.g(1)!!) } ?: t.take(D_BUSINESS_SUFFIX) { num(it.g(1)!!) })
            ?.let { Recur.addBusinessDays(today, it) }
        if (date == null) {
            val m = t.take(D_HALF_HOUR) { 30L } ?: t.take(D_HOUR_HALF) { 90L }
            if (m != null) {
                val dt = now.plusMinutes(m)
                date = dt.toLocalDate()
                time = time ?: hm(dt.hour, dt.minute)
            }
        }
        if (date == null) {
            val rel = { m: MatchResult -> num(m.g(1)!!)?.let { unitSpan(m.g(2)!!, it) } }
            when (val span = t.take(D_REL, rel) ?: t.take(D_REL_SUFFIX, rel)) {
                is Minutes -> {
                    val dt = now.plusMinutes(span.n)
                    date = dt.toLocalDate()
                    time = time ?: hm(dt.hour, dt.minute)
                }
                is Days -> date = today.plusDays(span.n)
                is Months -> date = Recur.addMonths(today, span.n, today.dayOfMonth)
                null -> {}
            }
        }

        if (date == null) date = namedDate(t, today, cfg)
        if (date == null) {
            date = t.take(W_NEXT) { m -> weekdayNum(m.g(1)!!)?.let { inNextWeek(today, it, cfg.weekStart) } }
                ?: t.take(W_THIS) { m -> weekdayNum(m.g(1)!!)?.let { upcoming(today, it) } }
                ?: t.take(W_BARE) { m -> weekdayNum(m.g(1)!!)?.let { upcoming(today, it) } }
        }
        if (date == null) date = absoluteDate(t, today, cfg)

        val dateExplicit = date != null || todayHint
        if (date == null && todayHint) date = today
        if (recurrence != null) {
            if (time == null) time = recTime
            if (date == null) {
                var d = Recur.firstOnOrAfter(recurrence, today)
                val tm = time
                if (d == today && tm != null && tm.isBefore(now.toLocalTime())) d = Recur.nextAfter(recurrence, d)
                date = d
            }
        }
        if (date == null) {
            val tm = time
            if (tm != null) date = if (tm.isAfter(now.toLocalTime())) today else today.plusDays(1)
        }

        // Words and tags caught inside "deadline …" still belong to the task.
        val title = cleanTitle(t.s).let { base -> if (dl != null && dl.title.isNotEmpty()) cleanTitle("$base ${dl.title}") else base }
        return Parsed(
            title = title,
            date = date,
            time = time,
            recurrence = recurrence,
            priority = priority ?: dl?.priority,
            labels = labels + dl?.labels.orEmpty(),
            group = group,
            reminders = reminders + dl?.reminders.orEmpty().filter { it !in reminders },
            urgent = urgent || dl?.urgent == true,
            dateExplicit = dateExplicit,
            deadline = dl?.date,
            deadlineTime = dl?.time,
        )
    }

    // ---- Events (port of parse_event in core/src/nlp.rs) ---------------------------------

    private const val TIME_FRAG = """(\d{1,2})(?:[:.](\d{2}))?\s*(am\b|pm\b|a\.m\.|p\.m\.)?"""
    private val E_ALL_DAY = re("""\ball[\s-]day\b""")
    private val E_RANGE_FROM = re("""\bfrom\s+(?:at\s+)?$TIME_FRAG\s*(?:-|–|to|until|till)\s*$TIME_FRAG""")
    private val E_RANGE = re("""(?:\bat\s+)?\b$TIME_FRAG\s*(?:-|–|to|until|till)\s*$TIME_FRAG""")
    private val E_DUR = re("""\bfor\s+(<NUM>|half\s+an?)\s*(minutes|minute|mins|min|m|hours|hour|hrs|hr|h)\b""")
    private val E_DUR_HOUR_HALF = re("""\bfor\s+(?:an?|one)\s+hour\s+and\s+a\s+half\b""")

    private fun fragTime(m: MatchResult, i: Int, mer: Boolean?): LocalTime? {
        val h = m.int(i) ?: return null
        val min = if (m.g(i + 1) != null) m.int(i + 1) ?: return null else 0
        return when {
            mer == null -> hm(h, min)
            h in 1..12 -> hm(if (mer) h % 12 + 12 else h % 12, min)
            else -> null
        }
    }

    private fun meridiem(m: MatchResult, i: Int): Boolean? = m.lc(i)?.startsWith("p")

    /** "3-5pm", "11am to 1pm", "9:30-10:15", "from 3 to 5" → (start, end). */
    private fun timeRange(m: MatchResult, fromWord: Boolean): Pair<LocalTime, LocalTime>? {
        val sm = meridiem(m, 3)
        val em = meridiem(m, 6)
        val colons = m.g(2) != null && m.g(5) != null
        if (sm == null && em == null) {
            if (colons) return (fragTime(m, 1, null) ?: return null) to (fragTime(m, 4, null) ?: return null)
            if (!fromWord) return null
            // "from 3 to 5" reads like "at 3": small hours are afternoon.
            val fix = { t: LocalTime -> if (t.hour in 1..6) t.plusHours(12) else t }
            return fix(fragTime(m, 1, null) ?: return null) to fix(fragTime(m, 4, null) ?: return null)
        }
        val end = fragTime(m, 4, em ?: sm) ?: return null
        val start = if (sm != null) {
            fragTime(m, 1, sm) ?: return null
        } else {
            // "11-1pm" is 11am to 1pm; "3-5pm" is 3pm to 5pm.
            val same = fragTime(m, 1, em) ?: return null
            if (!same.isAfter(end)) same else fragTime(m, 1, em?.not()) ?: return null
        }
        return start to end
    }

    /**
     * Quick add for events: everything [parse] understands, plus time ranges ("3-5pm",
     * "from 9 to 11"), durations ("for 45 min") and "all day". Without a time the event
     * is all-day; without an end it lasts an hour.
     */
    fun parseEvent(input: String, now: LocalDateTime = LocalDateTime.now(), cfg: NlpConfig = NlpConfig()): ParsedEvent {
        val today = now.toLocalDate()
        val t = Text(input)
        val allDay = t.take(E_ALL_DAY) { true } != null
        val range = t.take(E_RANGE_FROM) { timeRange(it, true) } ?: t.take(E_RANGE) { timeRange(it, false) }
        val duration = t.take(E_DUR_HOUR_HALF) { 90L } ?: t.take(E_DUR) {
            val mult = reminderMinutes(it.g(2)!!)
            if (it.lc(1)!!.startsWith("half")) mult / 2 else (num(it.g(1)!!) ?: return@take null) * mult
        }
        val p = parse(t.s, now, cfg)

        val startTime = if (allDay) null else range?.first ?: p.time
        var date = p.date ?: if (startTime != null && !startTime.isAfter(now.toLocalTime())) today.plusDays(1) else today
        val rec = p.recurrence
        if (rec != null && startTime != null && range != null && p.date == today && startTime.isBefore(now.toLocalTime())) {
            date = Recur.nextAfter(rec, today)
        }
        val endDate: LocalDate
        val endTime: LocalTime?
        if (startTime == null) {
            endDate = date
            endTime = null
        } else if (range != null) {
            endTime = range.second
            endDate = if (!endTime.isAfter(startTime)) date.plusDays(1) else date
        } else {
            val end = date.atTime(startTime).plusMinutes(duration ?: 60)
            endDate = end.toLocalDate()
            endTime = end.toLocalTime()
        }
        return ParsedEvent(p.title, date, startTime, endDate, endTime, p.recurrence, p.group, p.reminders, p.urgent, p.dateExplicit)
    }

    private fun parseRecurrence(t: Text, cfg: NlpConfig, setTime: (LocalTime?) -> Unit): Recurrence? {
        fun freqOf(s: String): Freq? = when (s.lowercase().firstOrNull()) {
            'd' -> Freq.DAILY; 'w' -> Freq.WEEKLY; 'm' -> Freq.MONTHLY; 'y' -> Freq.YEARLY; else -> null
        }
        fun mk(f: Freq, n: Int) = Recurrence(freq = f, interval = n)

        t.take(R_OTHER) { m -> freqOf(m.g(1)!!)?.let { mk(it, 2) } }?.let { return it }
        t.take(R_EVERY_N) { m ->
            val n = m.int(1)?.takeIf { it > 0 } ?: return@take null
            freqOf(m.g(2)!!)?.let { mk(it, n) }
        }?.let { return it }
        t.take(R_WORKDAYS) { Recurrence(workdays = true) }?.let { return it }
        t.take(R_WEEKEND) { Recurrence(freq = Freq.WEEKLY, weekdays = listOf(6, 7)) }?.let { return it }
        t.take(R_WEEKDAYS) { m ->
            val days = ONE_WD.findAll(m.g(1)!!).mapNotNull { weekdayNum(it.groupValues[1]) }.toSortedSet().toList()
            Recurrence(freq = Freq.WEEKLY, weekdays = days)
        }?.let { return it }
        t.take(R_MONTH_LAST) { Recurrence(freq = Freq.MONTHLY, monthDay = 31) }?.let { return it }
        t.take(R_EVERY_UNIT) { m ->
            val u = m.lc(1)!!
            val h = when (u) {
                "morning" -> cfg.morningHour
                "afternoon" -> cfg.afternoonHour
                "evening" -> cfg.eveningHour
                "night" -> cfg.nightHour
                else -> null
            }
            if (h != null) {
                setTime(hm(h, 0))
                mk(Freq.DAILY, 1)
            } else freqOf(u)?.let { mk(it, 1) }
        }?.let { return it }
        t.take(R_ADVERB) { m ->
            when (m.lc(1)) {
                "daily" -> mk(Freq.DAILY, 1)
                "nightly" -> {
                    setTime(hm(cfg.nightHour, 0))
                    mk(Freq.DAILY, 1)
                }
                "weekly" -> mk(Freq.WEEKLY, 1)
                "fortnightly", "biweekly" -> mk(Freq.WEEKLY, 2)
                "monthly" -> mk(Freq.MONTHLY, 1)
                else -> mk(Freq.YEARLY, 1)
            }
        }?.let { return it }
        fun yearly(m: Int?, d: Int?) = if (m != null && d != null && d in 1..31) Recurrence(freq = Freq.YEARLY, month = m, monthDay = d) else null
        (t.take(R_YEARLY_MD) { yearly(monthNum(it.g(1)!!), it.int(2)) } ?: t.take(R_YEARLY_DM) { yearly(monthNum(it.g(2)!!), it.int(1)) })
            ?.let { return it }
        return t.take(R_MONTHDAY) { m ->
            val d = m.int(1)?.takeIf { it in 1..31 } ?: return@take null
            Recurrence(freq = Freq.MONTHLY, monthDay = d)
        }
    }

    private fun namedDate(t: Text, today: LocalDate, cfg: NlpConfig): LocalDate? {
        val ws = cfg.weekStart
        val nextMonthFirst = Recur.addMonths(today, 1, 1)
        val isStart = { m: MatchResult -> m.g(1) != null }
        return t.take(W_NEXT_WEEK_DAY) { m -> weekdayNum(m.g(1) ?: m.g(2) ?: return@take null)?.let { inNextWeek(today, it, ws) } }
            ?: t.take(N_DAY_AFTER) { today.plusDays(2) }
            ?: t.take(N_TODAY) { today }
            ?: t.take(N_TOMORROW) { today.plusDays(1) }
            ?: t.take(N_NEXT_WEEKEND) { inNextWeek(today, 6, ws) }
            ?: t.take(N_WEEKEND) { if (Recur.weekday(today) == 7) today else upcoming(today, 6) }
            ?: t.take(N_EOW) { upcoming(today, 5) }
            ?: t.take(N_END_NEXT_MONTH) { Recur.ymdClamped(nextMonthFirst.year, nextMonthFirst.monthValue, 31) }
            ?: t.take(N_EOM) { Recur.ymdClamped(today.year, today.monthValue, 31) }
            ?: t.take(N_EOY) { LocalDate.of(today.year, 12, 31) }
            ?: t.take(N_NEXT_WEEK) { inNextWeek(today, if (isStart(it)) ws else cfg.nextWeekDay, ws) }
            ?: t.take(N_NEXT_MONTH) { if (isStart(it) || !cfg.nextPeriodSameDay) nextMonthFirst else Recur.addMonths(today, 1, today.dayOfMonth) }
            ?: t.take(N_NEXT_YEAR) {
                if (isStart(it) || !cfg.nextPeriodSameDay) LocalDate.of(today.year + 1, 1, 1) else Recur.addMonths(today, 12, today.dayOfMonth)
            }
            ?: t.take(N_MID_MONTH) { upcomingDayOfMonth(today, 15) }
    }

    private fun absoluteDate(t: Text, today: LocalDate, cfg: NlpConfig): LocalDate? {
        fun withYear(m: MatchResult, i: Int, f: (Int?) -> LocalDate?): LocalDate? {
            val (ok, y) = yearOf(m, i)
            return if (ok) f(y) else null
        }
        return t.take(A_ISO) { dateOrNull(it.int(1) ?: return@take null, it.int(2) ?: return@take null, it.int(3) ?: return@take null) }
            ?: t.take(A_DM) { m -> withYear(m, 3) { y -> upcomingMd(today, monthNum(m.g(2)!!) ?: return@withYear null, m.int(1) ?: return@withYear null, y) } }
            ?: t.take(A_MD) { m -> withYear(m, 3) { y -> upcomingMd(today, monthNum(m.g(1)!!) ?: return@withYear null, m.int(2) ?: return@withYear null, y) } }
            ?: t.take(A_NUM) { m ->
                val a = m.int(1) ?: return@take null
                val b = m.int(2) ?: return@take null
                val (d, mo) = if (cfg.monthFirst) b to a else a to b
                if (mo !in 1..12) return@take null
                withYear(m, 3) { y -> upcomingMd(today, mo, d, y) }
            }
            ?: t.take(A_ON_THE) { upcomingDayOfMonth(today, it.int(1) ?: return@take null) }
            ?: t.take(A_ORD) { upcomingDayOfMonth(today, it.int(1) ?: return@take null) }
            ?: t.take(A_IN_MONTH) { m ->
                val mo = monthNum(m.g(1)!!) ?: return@take null
                when {
                    mo == today.monthValue -> today
                    mo > today.monthValue -> LocalDate.of(today.year, mo, 1)
                    else -> LocalDate.of(today.year + 1, mo, 1)
                }
            }
    }

    private fun cleanTitle(s: String): String {
        var cur = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        while (true) {
            val next = LEAD.replace(TRAIL.replace(cur, ""), "").trim()
            if (next == cur) return cur
            cur = next
        }
    }
}
