package dev.pact.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.pact.app.core.Attachment
import dev.pact.app.core.AttachmentKind
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
    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    private val _groups = MutableStateFlow<Map<String, Group>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks
    val groups: StateFlow<Map<String, Group>> = _groups

    /** Emits after every local change (not after reloads). */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes

    private val taskDir get() = File(root, "tasks")
    private val groupDir get() = File(root, "groups")

    init {
        listOf("tasks", "groups", "attachments").forEach { File(root, it).mkdirs() }
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
        if (done && rec != null && due != null) {
            val next = Recur.advance(rec, due, LocalDate.now())
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
        reload()
    }

    /** Creates a task from quick-add text. `#Group` picks (or creates) a group. */
    fun quickAdd(text: String, cfg: NlpConfig, defaults: QuickAddDefaults, now: LocalDateTime = LocalDateTime.now()): Task? {
        val p = Nlp.parse(text, now, cfg)
        if (p.title.isEmpty()) return null
        var groupId = defaults.groupId
        p.group?.let { name ->
            groupId = (findGroupByName(name) ?: Group(name = name, color = GROUP_COLORS[_groups.value.size % GROUP_COLORS.size]).also { saveGroup(it) }).id
        }
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
        ).withDate(p.date ?: defaults.date, time)
        saveTask(t)
        return t
    }
}
