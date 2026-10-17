package dev.pact.app

import dev.pact.app.core.Deadline
import dev.pact.app.core.DeadlineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Same cases as the Rust deadline tests (spec/deadline_cases.json). */
class DeadlineTest {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    @Test
    fun sharedCases() {
        val spec = Json.parseToJsonElement(File("../../spec/deadline_cases.json").readText()).jsonObject
        for (c in spec["schedules"]!!.jsonArray.map { it.jsonObject }) {
            val cfgJson = c["config"]!!.jsonObject
            val cfg = DeadlineConfig(cfgJson["day_start"]!!.jsonPrimitive.int, cfgJson["day_end"]!!.jsonPrimitive.int, cfgJson["final_hours"]!!.jsonPrimitive.int)
            val date = LocalDate.parse(c["date"]!!.jsonPrimitive.content)
            val time = c["time"]!!.jsonPrimitive.contentOrNull?.let { LocalTime.parse(it) }
            val got = Deadline.alerts(Deadline.moment(date, time, cfg), cfg).map { it.format(fmt) }
            val want = c["alerts"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(c["name"]!!.jsonPrimitive.content, want, got)
        }
        for (c in spec["messages"]!!.jsonArray.map { it.jsonObject }) {
            val got = Deadline.message(LocalDateTime.parse(c["deadline"]!!.jsonPrimitive.content), LocalDateTime.parse(c["at"]!!.jsonPrimitive.content))
            assertEquals(c.toString(), c["text"]!!.jsonPrimitive.content, got)
        }
    }
}
