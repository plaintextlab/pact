package dev.pact.app.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// Mirrors core/src/model.rs; field order matches so both apps write identical JSON.
// See spec/data-format.md.

@Serializable
enum class Freq {
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
    @SerialName("monthly") MONTHLY,
    @SerialName("yearly") YEARLY,
}

@Serializable
data class Recurrence(
    val freq: Freq = Freq.DAILY,
    val interval: Int = 1,
    /** 1 = Monday … 7 = Sunday (weekly only). */
    val weekdays: List<Int> = emptyList(),
    /** Monday–Friday only (daily only). */
    val workdays: Boolean = false,
    @SerialName("month_day") val monthDay: Int? = null,
    val month: Int? = null,
)

@Serializable
enum class AttachmentKind {
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
    @SerialName("file") FILE,
    @SerialName("text") TEXT;

    companion object {
        fun fromName(name: String): AttachmentKind = when (name.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> IMAGE
            "wav", "mp3", "m4a", "ogg", "opus", "flac", "aac", "3gp" -> AUDIO
            else -> FILE
        }
    }
}

@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    /** Relative to the data dir, `/`-separated. */
    val path: String? = null,
    val text: String? = null,
    val created: Long = 0,
)

@Serializable
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    val recurrence: Recurrence? = null,
    val priority: Int = 4,
    val labels: List<String> = emptyList(),
    val urgent: Boolean = false,
    val reminders: List<Long> = emptyList(),
    val pinned: Boolean = false,
    val completed: Boolean = false,
    @SerialName("completed_at") val completedAt: Long? = null,
    val attachments: List<Attachment> = emptyList(),
    val order: Long = System.currentTimeMillis(),
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val date: LocalDate? get() = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val time: LocalTime? get() = dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    /** Due moment; all-day tasks are due at the end of their day. */
    val dueAt: LocalDateTime? get() = date?.let { d -> d.atTime(time ?: LocalTime.of(23, 59, 59)) }

    fun isOverdue(now: LocalDateTime = LocalDateTime.now()) = !completed && (dueAt?.isBefore(now) ?: false)

    fun withDate(d: LocalDate?, t: LocalTime? = if (d == null) null else time): Task =
        copy(dueDate = d?.toString(), dueTime = if (d == null) null else t?.format(TIME_FMT))
}

@Serializable
data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Theme key (`red`, `blue`, …) or `#RRGGBB`. */
    val color: String = "blue",
    val order: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

val GROUP_COLORS = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple")

val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

val PactJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = true
}

/** Open first, then by due moment (undated last), priority, manual order. */
val TaskOrder: Comparator<Task> = compareBy<Task> { it.completed }
    .thenBy(nullsLast()) { it.dueAt }
    .thenBy { it.priority }
    .thenBy { it.order }
