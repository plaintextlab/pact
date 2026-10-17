package dev.pact.app

import dev.pact.app.core.Nlp
import dev.pact.app.core.NlpConfig
import dev.pact.app.core.PactJson
import dev.pact.app.core.Recurrence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Runs the same cases as the Rust parser (spec/nlp_cases.json). */
class NlpCasesTest {
    @Test
    fun sharedCases() {
        val spec = Json.parseToJsonElement(File("../../spec/nlp_cases.json").readText()).jsonObject
        val now = LocalDateTime.parse(spec["now"]!!.jsonPrimitive.content)
        val failures = mutableListOf<String>()
        for (c in spec["cases"]!!.jsonArray.map { it.jsonObject }) {
            val input = c["input"]!!.jsonPrimitive.content
            val cfgJson = buildJsonObject {
                PactJson.encodeToJsonElement(NlpConfig()).jsonObject.forEach { (k, v) -> put(k, v) }
                (c["config"] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
            }
            val cfg = PactJson.decodeFromJsonElement(NlpConfig.serializer(), cfgJson)
            val p = Nlp.parse(input, now, cfg)

            fun check(field: String, got: JsonElement) {
                val want = c[field] ?: JsonNull
                if (got != want) failures += "\"$input\": $field = $got, want $want"
            }
            check("title", JsonPrimitive(p.title))
            check("date", p.date?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            check("time", p.time?.let { JsonPrimitive(it.toString().take(5)) } ?: JsonNull)
            c["rec"]?.jsonObject?.let { want ->
                val got = p.recurrence?.let { PactJson.encodeToJsonElement(Recurrence.serializer(), it).jsonObject }
                val ok = got != null && want.all { (k, v) -> got[k] == v }
                if (!ok) failures += "\"$input\": rec = $got, want $want"
            }
            if ("priority" in c) check("priority", p.priority?.let { JsonPrimitive(it) } ?: JsonNull)
            if ("group" in c) check("group", p.group?.let { JsonPrimitive(it) } ?: JsonNull)
            if ("labels" in c) check("labels", PactJson.encodeToJsonElement(p.labels))
            if ("reminders" in c) check("reminders", PactJson.encodeToJsonElement(p.reminders))
            if ("urgent" in c) check("urgent", JsonPrimitive(p.urgent))
            if ("deadline" in c) {
                val d = p.deadline?.let { d -> p.deadlineTime?.let { "$d ${it.toString().take(5)}" } ?: d.toString() }
                check("deadline", d?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        assertTrue("${failures.size} failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
