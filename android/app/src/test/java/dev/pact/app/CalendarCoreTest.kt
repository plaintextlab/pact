package dev.pact.app

import dev.pact.app.core.Event
import dev.pact.app.core.Freq
import dev.pact.app.core.Group
import dev.pact.app.core.Ics
import dev.pact.app.core.Nlp
import dev.pact.app.core.PactJson
import dev.pact.app.core.Recurrence
import dev.pact.app.data.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Same checks as the Rust event/ics/store tests, against the shared spec files. */
class CalendarCoreTest {
    @Test
    fun sharedEventCases() {
        val spec = Json.parseToJsonElement(File("../../spec/event_nlp_cases.json").readText()).jsonObject
        val now = LocalDateTime.parse(spec["now"]!!.jsonPrimitive.content)
        val failures = mutableListOf<String>()
        for (c in spec["cases"]!!.jsonArray.map { it.jsonObject }) {
            val input = c["input"]!!.jsonPrimitive.content
            val p = Nlp.parseEvent(input, now)
            fun t(x: LocalTime?): JsonElement = x?.let { JsonPrimitive(it.toString().take(5)) } ?: JsonNull
            val got = mapOf(
                "title" to JsonPrimitive(p.title),
                "date" to JsonPrimitive(p.date.toString()),
                "start" to t(p.startTime),
                "end_date" to JsonPrimitive(p.endDate.toString()),
                "end" to t(p.endTime),
                "rec" to (p.recurrence?.let { PactJson.encodeToJsonElement(Recurrence.serializer(), it) } ?: JsonNull),
                "group" to (p.group?.let { JsonPrimitive(it) } ?: JsonNull),
            )
            for ((k, want) in c) {
                if (k == "input") continue
                val g = got[k]
                val ok = if (k == "rec" && g is JsonObject) want.jsonObject.all { (rk, rv) -> g[rk] == rv } else g == want
                if (!ok) failures += "\"$input\": $k = $g, want $want"
            }
        }
        assertTrue("${failures.size} failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun icsSampleAndRoundTrip() {
        val events = Ics.import(File("../../spec/ics_sample.ics").readText()) { if (it == "Personal") "personal" else null }
        assertEquals(3, events.size)
        val s = events[0]
        assertEquals("Team standup", s.title)
        assertEquals("Daily sync, keep it short\nAgenda in the doc", s.notes)
        assertEquals("Room 4; floor 2", s.location)
        assertEquals(Triple("2026-10-19", "09:30", "09:45"), Triple(s.startDate, s.startTime, s.endTime))
        assertEquals(Freq.WEEKLY, s.recurrence!!.freq)
        assertEquals(listOf(1, 3, 5), s.recurrence!!.weekdays)
        assertEquals("2026-12-31", s.recurrence!!.until)
        assertEquals(listOf("2026-10-21"), s.exdates)
        assertEquals(listOf(15L), s.reminders)
        val h = events[1]
        assertTrue(h.allDay)
        assertEquals("2026-12-24" to "2026-12-26", h.startDate to h.endDate)
        assertEquals("personal", h.groupId)
        val d = events[2]
        assertTrue(d.title.endsWith("folded by the exporter"))
        assertEquals("15:45", d.endTime)
        assertEquals(Triple(Freq.MONTHLY, 6, 4), Triple(d.recurrence!!.freq, d.recurrence!!.interval, d.recurrence!!.count))

        val text = Ics.export(events, listOf(Group(id = "personal", name = "Personal")))
        assertTrue("folded", text.lines().all { it.toByteArray().size <= 76 })
        val again = Ics.import(text) { if (it == "Personal") "personal" else null }
        for ((a, b) in events.zip(again)) {
            assertEquals(a.copy(id = "", created = 0, updated = 0), b.copy(id = "", created = 0, updated = 0))
        }
    }

    @Test
    fun storeEvents() {
        val store = Store(Files.createTempDirectory("pact-events").toFile())
        val now = LocalDateTime.of(2026, 10, 16, 9, 0)
        val day = { n: Long -> now.toLocalDate().plusDays(n) }
        val e = store.quickAddEvent("Standup every weekday 9:30-9:45am #Work", store.let { dev.pact.app.core.NlpConfig() }, now = now)!!
        assertEquals("work", e.groupId)
        assertEquals(5, store.agenda(day(0), day(6)).size)
        val one = store.detachOccurrence(e.id, day(3))!!
        assertNull(one.recurrence)
        assertEquals(5, store.agenda(day(0), day(6)).size)
        store.skipOccurrence(e.id, day(4))
        assertEquals(4, store.agenda(day(0), day(6)).size)
        val lunch = store.quickAddEvent("Lunch 1-2pm", dev.pact.app.core.NlpConfig(), date = day(5), now = now)!!
        assertEquals(day(5).toString(), lunch.startDate)

        val n = store.liveEvents().size
        assertEquals(0 to n, store.importIcs(store.exportIcs()))
        assertEquals(n, store.liveEvents().size)

        val overnight = Event.create("late", LocalDate.of(2026, 10, 16), null)
            .withSpan(LocalDateTime.of(2026, 10, 16, 23, 0), LocalDateTime.of(2026, 10, 17, 0, 0), false)
        assertEquals(LocalDate.of(2026, 10, 16), overnight.occurrences(day(0), day(1)).single().lastDay)
    }
}
