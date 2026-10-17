package dev.pact.app.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar (RFC 5545) import/export for events; port of core/src/ics.rs.
 * Times with TZID are read as local wall-clock time; UTC (`Z`) times are converted to local time.
 */
object Ics {
    private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val D = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                out.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Folds a content line to 75 octets. */
    private fun fold(line: String, out: StringBuilder) {
        var width = 0
        for (ch in line) {
            val w = ch.toString().toByteArray().size
            if (width + w > 75) {
                out.append("\r\n ")
                width = 1
            }
            out.append(ch)
            width += w
        }
        out.append("\r\n")
    }

    private fun rrule(r: Recurrence): String {
        val parts = mutableListOf<String>()
        if (r.freq == Freq.DAILY && r.workdays) {
            parts += "FREQ=WEEKLY"
            parts += "BYDAY=MO,TU,WE,TH,FR"
        } else {
            parts += "FREQ=" + r.freq.name
            if (r.weekdays.isNotEmpty()) parts += "BYDAY=" + r.weekdays.mapNotNull { DAYS.getOrNull(it - 1) }.joinToString(",")
            r.monthDay?.let { parts += "BYMONTHDAY=" + if (it >= 31) -1 else it }
            r.month?.let { parts += "BYMONTH=$it" }
        }
        if (r.interval > 1) parts += "INTERVAL=${r.interval}"
        r.untilDate?.let { parts += "UNTIL=" + it.format(D) }
        r.count?.let { parts += "COUNT=$it" }
        return parts.joinToString(";")
    }

    fun export(events: Collection<Event>, groups: Collection<Group>): String {
        val out = StringBuilder()
        val stamp = LocalDateTime.now(ZoneOffset.UTC).format(DT) + "Z"
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Pact//Pact Calendar//EN", "CALSCALE:GREGORIAN").forEach { fold(it, out) }
        for (e in events.filter { !it.deleted }) {
            fold("BEGIN:VEVENT", out)
            fold("UID:" + (e.uid ?: "${e.id}@pact"), out)
            fold("DTSTAMP:$stamp", out)
            val start = e.start
            if (e.allDay) {
                fold("DTSTART;VALUE=DATE:" + start.format(D), out)
                fold("DTEND;VALUE=DATE:" + e.end.toLocalDate().plusDays(1).format(D), out)
            } else {
                fold("DTSTART:" + start.format(DT), out)
                fold("DTEND:" + e.end.format(DT), out)
            }
            fold("SUMMARY:" + escape(e.title), out)
            if (e.notes.isNotEmpty()) fold("DESCRIPTION:" + escape(e.notes), out)
            if (e.location.isNotEmpty()) fold("LOCATION:" + escape(e.location), out)
            if (e.url.isNotEmpty()) fold("URL:" + e.url, out)
            groups.firstOrNull { it.id == e.groupId }?.let { fold("CATEGORIES:" + escape(it.name), out) }
            e.recurrence?.let { fold("RRULE:" + rrule(it), out) }
            for (x in e.exdates) {
                val d = runCatching { LocalDate.parse(x) }.getOrNull() ?: continue
                fold(if (e.allDay) "EXDATE;VALUE=DATE:" + d.format(D) else "EXDATE:" + d.atTime(start.toLocalTime()).format(DT), out)
            }
            for (m in e.reminders) {
                listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + escape(e.title), "TRIGGER:-PT${m}M", "END:VALARM").forEach { fold(it, out) }
            }
            fold("END:VEVENT", out)
        }
        fold("END:VCALENDAR", out)
        return out.toString()
    }

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private fun parseLine(line: String): Prop? {
        var quoted = false
        var split = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) {
                split = i
                break
            }
        }
        if (split < 0) return null
        val head = line.substring(0, split).split(';')
        val params = head.drop(1).mapNotNull { p ->
            val i = p.indexOf('=')
            if (i < 0) null else p.substring(0, i).uppercase() to p.substring(i + 1).trim('"')
        }.toMap()
        return Prop(head[0].trim().uppercase(), params, line.substring(split + 1))
    }

    /** (local date-time, is date-only). */
    private fun parseWhen(value: String, p: Prop): Pair<LocalDateTime, Boolean>? = runCatching {
        val v = value.trim()
        if (p.params["VALUE"].equals("DATE", true) || v.length == 8) {
            LocalDate.parse(v.take(8), D).atStartOfDay() to true
        } else if (v.endsWith("Z")) {
            val utc = LocalDateTime.parse(v.dropLast(1), DT)
            LocalDateTime.ofInstant(utc.toInstant(ZoneOffset.UTC), ZoneId.systemDefault()) to false
        } else {
            LocalDateTime.parse(v, DT) to false
        }
    }.getOrNull()

    /** ISO 8601 duration like `PT1H30M`, `P1D`, `-PT15M` → minutes. */
    private fun parseDuration(s: String): Long? {
        var t = s.trim()
        val neg = t.startsWith("-")
        t = t.removePrefix("-").removePrefix("+")
        if (!t.startsWith("P")) return null
        var total = 0L
        val num = StringBuilder()
        for (c in t.drop(1)) {
            when (c) {
                in '0'..'9' -> num.append(c)
                'T' -> {}
                'W', 'D', 'H', 'M', 'S' -> {
                    val n = num.toString().toLongOrNull() ?: return null
                    num.clear()
                    total += when (c) { 'W' -> n * 7 * 1440; 'D' -> n * 1440; 'H' -> n * 60; 'M' -> n; else -> n / 60 }
                }
                else -> return null
            }
        }
        return if (neg) -total else total
    }

    private fun parseRrule(v: String, start: LocalDate): Recurrence? {
        var r = Recurrence()
        var freq: Freq? = null
        for (part in v.split(';')) {
            val i = part.indexOf('=')
            if (i < 0) return null
            val value = part.substring(i + 1)
            when (part.substring(0, i).uppercase()) {
                "FREQ" -> freq = when (value.uppercase()) {
                    "DAILY" -> Freq.DAILY; "WEEKLY" -> Freq.WEEKLY; "MONTHLY" -> Freq.MONTHLY; "YEARLY" -> Freq.YEARLY; else -> return null
                }
                "INTERVAL" -> r = r.copy(interval = value.toIntOrNull() ?: 1)
                "COUNT" -> r = r.copy(count = value.toIntOrNull())
                "UNTIL" -> r = r.copy(until = runCatching { LocalDate.parse(value.take(8), D).toString() }.getOrNull() ?: return null)
                // Plain weekdays only; ordinals like "2MO" (second Monday) aren't supported.
                "BYDAY" -> r = r.copy(weekdays = value.split(',').mapNotNull { d -> DAYS.indexOfFirst { it.equals(d, true) }.takeIf { it >= 0 }?.plus(1) }.sorted())
                "BYMONTHDAY" -> r = r.copy(monthDay = value.split(',').first().toIntOrNull()?.let { if (it < 0) 31 else it } ?: return null)
                "BYMONTH" -> r = r.copy(month = value.split(',').first().toIntOrNull())
            }
        }
        r = r.copy(freq = freq ?: return null)
        r = when {
            r.freq == Freq.WEEKLY && r.weekdays == listOf(1, 2, 3, 4, 5) && r.interval == 1 -> r.copy(freq = Freq.DAILY, workdays = true, weekdays = emptyList())
            r.freq == Freq.WEEKLY -> r
            else -> r.copy(weekdays = emptyList())
        }
        if (r.freq == Freq.YEARLY && r.month != null && r.monthDay == null) r = r.copy(monthDay = start.dayOfMonth)
        return r
    }

    /** Parses VEVENTs; [groupFor] maps a CATEGORIES name to a group id. */
    fun import(text: String, groupFor: (String) -> String?): List<Event> {
        val lines = mutableListOf<StringBuilder>()
        for (raw0 in text.split('\n')) {
            val raw = raw0.removeSuffix("\r")
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines.last().append(raw.substring(1)) else lines += StringBuilder(raw)
        }
        val out = mutableListOf<Event>()
        var cur: MutableList<Prop>? = null
        val alarms = mutableListOf<Long>()
        var inAlarm = false
        for (line in lines) {
            val p = parseLine(line.toString()) ?: continue
            val v = p.value.uppercase()
            when {
                p.name == "BEGIN" && v == "VEVENT" -> { cur = mutableListOf(); alarms.clear() }
                p.name == "BEGIN" && v == "VALARM" -> inAlarm = true
                p.name == "END" && v == "VALARM" -> inAlarm = false
                p.name == "END" && v == "VEVENT" -> {
                    cur?.let { props -> build(props, alarms, groupFor)?.let { out += it } }
                    cur = null
                }
                p.name == "TRIGGER" && inAlarm -> parseDuration(p.value)?.let { if (it <= 0 && -it !in alarms) alarms += -it }
                !inAlarm -> cur?.add(p)
            }
        }
        return out
    }

    private fun build(props: List<Prop>, alarms: List<Long>, groupFor: (String) -> String?): Event? {
        fun get(n: String) = props.firstOrNull { it.name == n }
        val sp = get("DTSTART") ?: return null
        val (start, allDay) = parseWhen(sp.value, sp) ?: return null
        val title = get("SUMMARY")?.let { unescape(it.value) }?.takeIf { it.isNotBlank() } ?: "(no title)"
        val endP = get("DTEND")?.let { parseWhen(it.value, it) }
        val end = when {
            endP != null && endP.second -> endP.first.minusDays(1) // all-day DTEND is exclusive
            endP != null -> endP.first
            else -> {
                val m = get("DURATION")?.let { parseDuration(it.value) }
                when {
                    m != null && allDay -> start.plusMinutes(m).minusDays(1)
                    m != null -> start.plusMinutes(m)
                    allDay -> start
                    else -> start.plusHours(1)
                }
            }
        }
        val exdates = props.filter { it.name == "EXDATE" }.flatMap { p ->
            p.value.split(',').mapNotNull { parseWhen(it, p)?.first?.toLocalDate()?.toString() }
        }
        val group = get("CATEGORIES")?.value?.split(',')?.firstOrNull()?.let { groupFor(unescape(it).trim()) }
        return Event.create(title, start.toLocalDate(), if (allDay) null else start.toLocalTime())
            .withSpan(start, if (end.isBefore(start)) start else end, allDay)
            .copy(
                notes = get("DESCRIPTION")?.let { unescape(it.value) } ?: "",
                location = get("LOCATION")?.let { unescape(it.value) } ?: "",
                url = get("URL")?.value ?: "",
                uid = get("UID")?.value,
                recurrence = get("RRULE")?.let { parseRrule(it.value, start.toLocalDate()) },
                exdates = exdates,
                reminders = alarms.toList(),
                groupId = group,
            )
    }
}
