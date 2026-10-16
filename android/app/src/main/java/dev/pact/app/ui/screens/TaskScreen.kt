package dev.pact.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.pact.app.core.Attachment
import dev.pact.app.core.AttachmentKind
import dev.pact.app.core.Nlp
import dev.pact.app.core.Recur
import dev.pact.app.core.Task
import dev.pact.app.data.QuickAddDefaults
import dev.pact.app.data.formatOffset
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.Format
import dev.pact.app.ui.components.ParsedChips
import dev.pact.app.ui.components.PriorityCheck
import dev.pact.app.ui.components.TaskRow
import dev.pact.app.ui.theme.groupColor
import dev.pact.app.ui.theme.priorityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(vm: AppViewModel, id: String) {
    val tasks by vm.store.tasks.collectAsState()
    val settings by vm.settingsRepo.state.collectAsState()
    val t = tasks[id]?.takeIf { !it.deleted }
    if (t == null) {
        LaunchedEffect(id) { vm.back() }
        return
    }
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Text fields edit a local copy and save on change.
    var title by remember(id) { mutableStateOf(t.title) }
    var notes by remember(id) { mutableStateOf(t.notes) }
    LaunchedEffect(title, notes) {
        delay(400)
        val cur = vm.store.task(id) ?: return@LaunchedEffect
        if (cur.title != title || cur.notes != notes) vm.save(cur.copy(title = title, notes = notes))
    }
    fun update(f: (Task) -> Task) {
        vm.store.task(id)?.let { vm.save(f(it).copy(title = title, notes = notes)) }
    }

    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var reschedule by remember(id) { mutableStateOf("") }
    var subtask by remember(id) { mutableStateOf("") }
    var label by remember(id) { mutableStateOf("") }
    var groupMenu by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<Recording?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attach(id, it, AttachmentKind.IMAGE) } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.attach(id, it, null) } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recorder = Recording.start(context) else vm.say("Microphone permission is needed for voice notes")
    }
    DisposableEffect(id) { onDispose { recorder?.cancel() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    val parent = vm.store.task(t.parentId)
                    Text(parent?.let { "↪ ${it.title}" } ?: vm.store.group(t.groupId)?.name ?: "Inbox", maxLines = 1,
                        style = MaterialTheme.typography.titleMedium)
                },
                actions = { IconButton(onClick = { vm.delete(id) }) { Icon(Icons.Filled.Delete, "Delete") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityCheck(t.completed, t.priority) { vm.setDone(id, !t.completed) }
                    TextField(
                        title, { title = it }, Modifier.weight(1f), textStyle = MaterialTheme.typography.headlineSmall,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                }
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp), placeholder = { Text("Description") },
                    shape = MaterialTheme.shapes.medium)

                Section("Due")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pickDate = true }, label = { Text(t.date?.let { Format.date(it) } ?: "Pick date") },
                        leadingIcon = { Icon(Icons.Filled.Event, null, Modifier.size(18.dp)) })
                    if (t.date != null) AssistChip(onClick = { pickTime = true }, label = { Text(t.dueTime ?: "Add time") },
                        leadingIcon = { Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp)) },
                        trailingIcon = if (t.dueTime != null) {
                            { IconButton(onClick = { update { it.copy(dueTime = null) } }, Modifier.size(18.dp)) { Icon(Icons.Filled.Close, "Clear time") } }
                        } else null)
                    AssistChip(onClick = { update { it.withDate(LocalDate.now()) } }, label = { Text("Today") })
                    AssistChip(onClick = { update { it.withDate(LocalDate.now().plusDays(1)) } }, label = { Text("Tomorrow") })
                    if (t.date != null) AssistChip(onClick = { update { it.withDate(null).copy(recurrence = null) } }, label = { Text("Clear") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Icons.Filled.Repeat, null, Modifier.size(18.dp), tint = muted)
                    Spacer(Modifier.width(8.dp))
                    Text(t.recurrence?.let { Recur.describe(it) } ?: "Does not repeat", color = if (t.recurrence == null) muted else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    if (t.recurrence != null) TextButton(onClick = { update { it.copy(recurrence = null) } }) { Text("Stop repeating") }
                }
                OutlinedTextField(
                    reschedule, { reschedule = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Type: next fri 3pm, every monday…") }, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val p = Nlp.parse(reschedule, cfg = settings.nlp)
                        update { cur ->
                            var x = cur
                            if (p.date != null) x = x.withDate(p.date, p.time)
                            if (p.recurrence != null) x = x.copy(recurrence = p.recurrence)
                            p.priority?.let { x = x.copy(priority = it) }
                            x.copy(reminders = (x.reminders + p.reminders).distinct(), urgent = x.urgent || p.urgent)
                        }
                        reschedule = ""
                    }),
                )
                if (reschedule.isNotBlank()) ParsedChips(Nlp.parse(reschedule, cfg = settings.nlp).copy(title = ""))

                Section("Priority & group")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in 1..4) FilterChip(
                        selected = t.priority == p, onClick = { update { it.copy(priority = p) } },
                        label = { Text(if (p == 4) "None" else "P$p") },
                        leadingIcon = { Icon(Icons.Filled.Flag, null, Modifier.size(18.dp), tint = priorityColor(p)) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Column {
                    val g = vm.store.group(t.groupId)
                    OutlinedButton(onClick = { groupMenu = true }) {
                        Text("●", color = g?.let { groupColor(it.color) } ?: muted)
                        Spacer(Modifier.width(8.dp))
                        Text(g?.name ?: "Inbox")
                    }
                    DropdownMenu(groupMenu, { groupMenu = false }) {
                        DropdownMenuItem(text = { Text("Inbox") }, onClick = { update { it.copy(groupId = null) }; groupMenu = false })
                        vm.store.liveGroups().forEach { gr ->
                            DropdownMenuItem(text = { Text(gr.name) }, leadingIcon = { Text("●", color = groupColor(gr.color)) },
                                onClick = { update { it.copy(groupId = gr.id) }; groupMenu = false })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    t.labels.forEach { l ->
                        InputChip(selected = false, onClick = { update { it.copy(labels = it.labels - l) } }, label = { Text("@$l") },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) })
                    }
                    OutlinedTextField(label, { label = it }, Modifier.width(150.dp), singleLine = true, placeholder = { Text("+ label") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val l = label.trim().removePrefix("@")
                            if (l.isNotEmpty()) update { if (l in it.labels) it else it.copy(labels = it.labels + l) }
                            label = ""
                        }))
                }

                Section("Reminders & alarm")
                SwitchRow(Icons.Filled.Alarm, "Urgent", "Ring an alarm when due and at each reminder", t.urgent) { v -> update { it.copy(urgent = v) } }
                SwitchRow(Icons.Filled.PushPin, "Pin notification", "Keep this task in the notification shade", t.pinned) { v -> update { it.copy(pinned = v) } }
                if (t.reminders.isNotEmpty() && t.date == null) Text("Reminders need a due date.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.reminders.sorted().forEach { m ->
                        InputChip(selected = false, onClick = { update { it.copy(reminders = it.reminders - m) } }, label = { Text("${formatOffset(m)} before") },
                            leadingIcon = { Icon(Icons.Filled.Notifications, null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) })
                    }
                    Column {
                        AssistChip(onClick = { reminderMenu = true }, label = { Text("+ Reminder") })
                        DropdownMenu(reminderMenu, { reminderMenu = false }) {
                            settings.reminderPresets.filter { it !in t.reminders }.forEach { m ->
                                DropdownMenuItem(text = { Text("${formatOffset(m)} before") }, onClick = {
                                    update { it.copy(reminders = it.reminders + m) }
                                    reminderMenu = false
                                })
                            }
                        }
                    }
                }

                val children = tasks.values.filter { it.parentId == id && !it.deleted }
                Section(if (children.isEmpty()) "Subtasks" else "Subtasks · ${children.count { it.completed }}/${children.size}")
            }
            val kids = tasks.values.filter { it.parentId == id && !it.deleted }.sortedWith(dev.pact.app.core.TaskOrder)
            kids.forEach { k -> item(key = k.id) { TaskRow(vm, k, tasks, tree = true, showGroup = false, showCompleted = true) } }
            item {
                OutlinedTextField(
                    subtask, { subtask = it }, Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true,
                    placeholder = { Text("+ Add subtask (dates work here too)") }, shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (subtask.isNotBlank()) vm.quickAdd(subtask, QuickAddDefaults(parentId = id, groupId = t.groupId, reminders = settings.defaultReminders))
                        subtask = ""
                    }),
                )

                Section("Attachments")
                t.attachments.forEach { a -> AttachmentCard(vm, t, a) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Filled.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Image")
                    }
                    FilledTonalButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("File")
                    }
                    FilledTonalButton(onClick = { noteDialog = true }) {
                        Icon(Icons.Filled.TextSnippet, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Text")
                    }
                    val rec = recorder
                    if (rec == null) {
                        FilledTonalButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                recorder = Recording.start(context)
                            } else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Voice") }
                    } else {
                        var secs by remember { mutableLongStateOf(0L) }
                        LaunchedEffect(rec) { while (true) { secs = (System.currentTimeMillis() - rec.started) / 1000; delay(500) } }
                        Button(onClick = {
                            rec.stop()?.let { vm.attachFile(id, it, AttachmentKind.AUDIO) }
                            recorder = null
                        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop %d:%02d".format(secs / 60, secs % 60))
                        }
                    }
                }
                Text("Files are copied into the synced data folder.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (t.date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> update { it.withDate(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val now = t.time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(now.hour, now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = { update { it.withDate(it.date, LocalTime.of(state.hour, state.minute)) }; pickTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
    if (noteDialog) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteDialog = false },
            title = { Text("Text attachment") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp))
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.attachText(id, name, text); noteDialog = false }) { Text("Attach") } },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AttachmentCard(vm: AppViewModel, t: Task, a: Attachment) {
    val context = LocalContext.current
    val file = vm.store.attachmentFile(a)
    fun open() {
        file ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { vm.say("No app can open ${a.name}") }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { open() }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (a.kind) {
                    AttachmentKind.IMAGE -> Icons.Filled.Image
                    AttachmentKind.AUDIO -> Icons.Filled.Mic
                    AttachmentKind.TEXT -> Icons.Filled.Description
                    AttachmentKind.FILE -> Icons.Filled.InsertDriveFile
                }
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(a.name, Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { vm.removeAttachment(t.id, a.id) }) { Icon(Icons.Filled.Close, "Remove") }
            }
            when {
                a.kind == AttachmentKind.TEXT -> Text(a.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                a.kind == AttachmentKind.IMAGE && file != null -> {
                    val bmp by produceState<ImageBitmap?>(null, file) { value = withContext(Dispatchers.IO) { thumbnail(file) } }
                    bmp?.let { Image(it, a.name, Modifier.fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop) }
                }
            }
        }
    }
    HorizontalDivider(color = Color.Transparent)
}

private fun thumbnail(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 800) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** A voice note being recorded to an .m4a in the cache dir. */
private class Recording(private val recorder: MediaRecorder, val file: File) {
    val started = System.currentTimeMillis()

    fun stop(): File? = runCatching { recorder.stop(); recorder.release(); file }.getOrElse { recorder.release(); null }
    fun cancel() { runCatching { recorder.stop() }; recorder.release(); file.delete() }

    companion object {
        fun start(context: android.content.Context): Recording? = runCatching {
            val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
            Recording(r, file)
        }.getOrNull()
    }
}
