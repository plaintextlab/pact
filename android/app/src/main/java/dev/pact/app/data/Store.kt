package dev.pact.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.pact.app.core.Attachment
import dev.pact.app.core.AttachmentKind
import dev.pact.app.core.Event
import dev.pact.app.core.Ics
import dev.pact.app.core.Occurrence
import dev.pact.app.core.OccurrenceOrder
import dev.pact.app.core.GROUP_COLORS
import dev.pact.app.core.Group
import dev.pact.app.core.Nlp
import dev.pact.app.core.NlpConfig
import dev.pact.app.core.PactJson
import dev.pact.app.core.Recur
import dev.pact.app.core.Task
import dev.pact.app.core.TaskOrder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class QuickAddDefaults(
    val parentId: String? = null,
    val groupId: String? = null,
    val date: LocalDate? = null,
    val reminders: List<Long> = emptyList(),
)

/** File-per-object storage inside the git data dir; mirrors core/src/store.rs. */
class Store(val root: File) {
    companion object {
        /** Pact's data folder inside a (possibly shared) sync repository. */
        fun dataDir(repo: File, folder: String): File = folder.trim().trim('/').let { if (it.isEmpty()) repo else File(repo, it) }

        /** Moves data kept at the repository root (older layout) into [folder]. */
        fun migrateIntoFolder(repo: File, folder: String) {
            val dst = dataDir(repo, folder)
            if (dst == repo || !File(repo, "tasks").isDirectory || File(dst, "tasks").exists()) return
            dst.mkdirs()
            for (name in listOf("tasks", "groups", "attachments", ".gitattributes")) {
                val src = File(repo, name)
                if (src.exists() && !src.renameTo(File(dst, name))) error("could not move ${src.path}")
            }
        }
    }

    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    private val _groups = MutableStateFlow<Map<String, Group>>(emptyMap())
    private val _events = MutableStateFlow<Map<String, Event>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks
    val groups: StateFlow<Map<String, Group>> = _groups
    val events: StateFlow<Map<String, Event>> = _events

    /** Emits after every local change (not after reloads). */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes

    private val taskDir get() = File(root, "tasks")
    private val groupDir get() = File(root, "groups")
    private val eventDir get() = File(root, "events")

    init {
        listOf("tasks", "groups", "events", "attachments").forEach { File(root, it).mkdirs() }
        val attrs = File(root, ".gitattributes")
        if (!attrs.exists()) attrs.writeText("*.json text eol=lf\nattachments/** binary\n")
        reload()
        if (_groups.value.isEmpty()) {
            // Fixed ids so two fresh devices don't create duplicate default groups.
            listOf(Triple("work", "Work", "blue"), Triple("personal", "Personal", "green"), Triple("study", "Study", "purple"))
                .forEachIndexed { i, (id, name, color) -> saveGroup(Group(id = id, name = name, color = color, order = i.toLong(), updated = 0)) }
        }
    }

    @Synchronized
    fun reload() {
        _tasks.value = readDir(taskDir) { PactJson.decodeFromString(Task.serializer(), it) }.associateBy { it.id }
        _groups.value = readDir(groupDir) { PactJson.decodeFromString(Group.serializer(), it) }.associateBy { it.id }
        _events.value = readDir(eventDir) { PactJson.decodeFromString(Event.serializer(), it) }.associateBy { it.id }
    }

    private fun <T> readDir(dir: File, parse: (String) -> T): List<T> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text + "\n")
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun task(id: String?): Task? = id?.let { _tasks.value[it] }?.takeIf { !it.deleted }
    fun liveTasks(): List<Task> = _tasks.value.values.filter { !it.deleted }
    fun liveGroups(): List<Group> = _groups.value.values.filter { !it.deleted }.sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
    fun group(id: String?): Group? = id?.let { _groups.value[it] }?.takeIf { !it.deleted }
    fun findGroupByName(name: String): Group? = liveGroups().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun children(parent: String?): List<Task> = liveTasks().filter { it.parentId == parent }.sortedWith(TaskOrder)

    fun descendants(id: String): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque(listOf(id))
        val all = liveTasks()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            all.filter { it.parentId == cur }.forEach { out += it.id; stack += it.id }
        }
        return out
    }

    @Synchronized
    fun saveTask(t: Task, notify: Boolean = true) {
        val stamped = t.copy(updated = System.currentTimeMillis())
        writeAtomic(File(taskDir, "${t.id}.json"), PactJson.encodeToString(Task.serializer(), stamped))
        _tasks.value = _tasks.value + (t.id to stamped)
        if (notify) _changes.tryEmit(Unit)
    }

    @Synchronized
    fun saveGroup(g: Group) {
        val stamped = g.copy(updated = System.currentTimeMillis())
        writeAtomic(File(groupDir, "${g.id}.json"), PactJson.encodeToString(Group.serializer(), stamped))
        _groups.value = _groups.value + (g.id to stamped)
        _changes.tryEmit(Unit)
    }

    /** Tombstones the task and its subtasks and removes their attachments. */
    fun deleteTask(id: String) {
        (descendants(id) + id).forEach { tid ->
            val t = _tasks.value[tid] ?: return@forEach
            File(root, "attachments/$tid").deleteRecursively()
            saveTask(t.copy(deleted = true, attachments = emptyList()))
        }
    }

    /** Tombstones the group; its tasks move to the Inbox. */
    fun deleteGroup(id: String) {
        liveTasks().filter { it.groupId == id }.forEach { saveTask(it.copy(groupId = null)) }
        _groups.value[id]?.let { saveGroup(it.copy(deleted = true)) }
    }

    /** Marks done/undone. A recurring task moves to its next date instead of closing. */
    fun setCompleted(id: String, done: Boolean) {
        val t = task(id) ?: return
        val rec = t.recurrence
        val due = t.date
        // A series past its "until" date closes like a normal task.
        val next = if (done && rec != null && due != null) {
            Recur.advance(rec, due, LocalDate.now()).takeIf { n -> rec.untilDate?.let { !n.isAfter(it) } ?: true }
        } else null
        if (next != null) {
            descendants(id).mapNotNull { task(it) }.forEach { saveTask(it.copy(completed = false, completedAt = null)) }
            saveTask(t.withDate(next).copy(completed = false, completedAt = null))
            return
        }
        saveTask(t.copy(completed = done, completedAt = if (done) System.currentTimeMillis() else null))
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it in ".-_ ") it else '_' }.joinToString("").trim().trimStart('.')
        return cleaned.ifEmpty { "file" }
    }

    /** Copies a picked document/photo into the task's attachment folder. */
    fun attachUri(taskId: String, resolver: ContentResolver, uri: Uri, kind: AttachmentKind? = null): Attachment? {
        val t = task(taskId) ?: return null
        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        return resolver.openInputStream(uri)?.use { input -> attachStream(t, safeFileName(display), kind) { out -> input.copyTo(out) } }
    }

    fun attachFile(taskId: String, file: File, kind: AttachmentKind? = null): Attachment? {
        val t = task(taskId) ?: return null
        return attachStream(t, safeFileName(file.name), kind) { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun attachStream(t: Task, name: String, kind: AttachmentKind?, write: (java.io.OutputStream) -> Unit): Attachment {
        val id = UUID.randomUUID().toString()
        val rel = "attachments/${t.id}/${id.take(8)}-$name"
        val dst = File(root, rel)
        dst.parentFile?.mkdirs()
        dst.outputStream().use(write)
        val a = Attachment(id = id, kind = kind ?: AttachmentKind.fromName(name), name = name, path = rel, created = System.currentTimeMillis())
        saveTask(t.copy(attachments = t.attachments + a))
        return a
    }

    fun attachText(taskId: String, name: String, text: String) {
        val t = task(taskId) ?: return
        val a = Attachment(UUID.randomUUID().toString(), AttachmentKind.TEXT, name.trim().ifEmpty { "Note" }, null, text, System.currentTimeMillis())
        saveTask(t.copy(attachments = t.attachments + a))
    }

    fun removeAttachment(taskId: String, attId: String) {
        val t = task(taskId) ?: return
        t.attachments.firstOrNull { it.id == attId }?.path?.let { File(root, it).delete() }
        saveTask(t.copy(attachments = t.attachments.filter { it.id != attId }))
    }

    fun attachmentFile(a: Attachment): File? = a.path?.let { File(root, it) }

    /** Removes tombstone files older than 30 days. */
    fun purgeTombstones() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        _tasks.value.values.filter { it.deleted && it.updated < cutoff }.forEach { File(taskDir, "${it.id}.json").delete() }
        _groups.value.values.filter { it.deleted && it.updated < cutoff }.forEach { File(groupDir, "${it.id}.json").delete() }
        _events.value.values.filter { it.deleted && it.updated < cutoff }.forEach { File(eventDir, "${it.id}.json").delete() }
        reload()
    }

    /** Creates a task from quick-add text. `#Group` picks (or creates) a group. */
    fun quickAdd(text: String, cfg: NlpConfig, defaults: QuickAddDefaults, now: LocalDateTime = LocalDateTime.now()): Task? {
        val p = Nlp.parse(text, now, cfg)
        if (p.title.isEmpty()) return null
        val groupId = p.group?.let { groupIdForName(it) } ?: defaults.groupId
        val time = p.time
        val t = Task(
            title = p.title,
            parentId = defaults.parentId,
            groupId = groupId,
            recurrence = p.recurrence,
            priority = p.priority ?: 4,
            labels = p.labels,
            urgent = p.urgent,
            reminders = if (p.reminders.isEmpty() && time != null) defaults.reminders else p.reminders,
        ).withDate(if (p.dateExplicit || p.recurrence != null) p.date else defaults.date ?: p.date, time)
            .withDeadline(p.deadline, p.deadlineTime)
        saveTask(t)
        return t
    }

    /** The id of the group named [name], creating it if needed. */
    fun groupIdForName(name: String): String =
        (findGroupByName(name) ?: Group(name = name, color = GROUP_COLORS[_groups.value.size % GROUP_COLORS.size]).also { saveGroup(it) }).id

    // ---- Events (mirrors core/src/store.rs) ------------------------------------------------

    fun event(id: String?): Event? = id?.let { _events.value[it] }?.takeIf { !it.deleted }
    fun liveEvents(): List<Event> = _events.value.values.filter { !it.deleted }

    @Synchronized
    fun saveEvent(e: Event) {
        val stamped = e.copy(updated = System.currentTimeMillis())
        writeAtomic(File(eventDir, "${e.id}.json"), PactJson.encodeToString(Event.serializer(), stamped))
        _events.value = _events.value + (e.id to stamped)
        _changes.tryEmit(Unit)
    }

    fun deleteEvent(id: String) {
        _events.value[id]?.let { saveEvent(it.copy(deleted = true)) }
    }

    /** Cancels one occurrence of a recurring event. */
    fun skipOccurrence(id: String, date: LocalDate) {
        val e = event(id) ?: return
        if (date.toString() !in e.exdates) saveEvent(e.copy(exdates = e.exdates + date.toString()))
    }

    /** Splits one occurrence off a recurring event so it can be edited on its own. */
    fun detachOccurrence(id: String, date: LocalDate): Event? {
        val series = event(id) ?: return null
        val occ = series.occurrences(date, date).firstOrNull { it.date == date } ?: return null
        val one = series.copy(id = UUID.randomUUID().toString(), uid = null, recurrence = null, exdates = emptyList(), created = System.currentTimeMillis())
            .withSpan(occ.start, occ.end, occ.allDay)
        skipOccurrence(id, date)
        saveEvent(one)
        return one
    }

    /** All event occurrences touching [from]..[to], all-day first, then by start. */
    fun agenda(from: LocalDate, to: LocalDate, events: Collection<Event> = _events.value.values): List<Occurrence> =
        events.filter { !it.deleted }.flatMap { it.occurrences(from, to) }.sortedWith(OccurrenceOrder)

    /** Creates an event from quick-add text; [date] applies when the text names no day. */
    fun quickAddEvent(text: String, cfg: NlpConfig, groupId: String? = null, date: LocalDate? = null, now: LocalDateTime = LocalDateTime.now()): Event? {
        var p = Nlp.parseEvent(text, now, cfg)
        if (p.title.isEmpty()) return null
        if (date != null && !p.dateExplicit && p.recurrence == null) {
            val shift = java.time.temporal.ChronoUnit.DAYS.between(p.date, date)
            p = p.copy(date = date, endDate = p.endDate.plusDays(shift))
        }
        val st = p.startTime
        val et = p.endTime
        var e = Event.create(p.title, p.date, st)
        e = if (st != null && et != null) e.withSpan(p.date.atTime(st), p.endDate.atTime(et), false) else e.copy(endDate = p.endDate.toString())
        e = e.copy(
            recurrence = p.recurrence,
            reminders = p.reminders,
            urgent = p.urgent,
            groupId = p.group?.let { groupIdForName(it) } ?: groupId,
        )
        saveEvent(e)
        return e
    }

    /** Imports an iCalendar file; known UIDs (incl. our own `<id>@pact`) update in place. Returns (added, updated). */
    fun importIcs(text: String): Pair<Int, Int> {
        val events = Ics.import(text) { cat -> groupIdForName(cat) }
        var added = 0
        var updated = 0
        for (e in events) {
            val uid = e.uid
            val known = uid?.let { u -> u.removeSuffix("@pact").takeIf { u.endsWith("@pact") }?.let { _events.value[it] } ?: _events.value.values.firstOrNull { it.uid == u } }
            if (known != null) {
                saveEvent(e.copy(id = known.id, created = known.created, uid = known.uid))
                updated++
            } else {
                saveEvent(e)
                added++
            }
        }
        return added to updated
    }

    fun exportIcs(): String = Ics.export(liveEvents(), liveGroups())
}
