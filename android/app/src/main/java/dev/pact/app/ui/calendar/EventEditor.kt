package dev.pact.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pact.app.core.Event
import dev.pact.app.core.Freq
import dev.pact.app.core.GROUP_COLORS
import dev.pact.app.core.Nlp
import dev.pact.app.core.Recur
import dev.pact.app.core.Recurrence
import dev.pact.app.data.formatOffset
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.EditScope
import dev.pact.app.ui.Format
import dev.pact.app.ui.Screen
import dev.pact.app.ui.theme.LocalPalette
import dev.pact.app.ui.theme.color
import dev.pact.app.ui.theme.groupColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val REMINDER_CHOICES = listOf(0L, 5, 10, 15, 30, 60, 120, 1440, 10080)
private val DAY_SHORT = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

private fun nextHalfHour(): LocalDateTime {
    val n = LocalDateTime.now().plusMinutes(30)
    return n.withMinute(if (n.minute < 30) 0 else 30).withSecond(0).withNano(0)
}

/** Repeat presets offered for an event starting on [d]. */
private fun presets(d: LocalDate): List<Pair<String, Recurrence?>> {
    val dow = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val mon = d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return listOf(
        "Does not repeat" to null,
        "Every day" to Recurrence(freq = Freq.DAILY),
        "Every weekday (Mon–Fri)" to Recurrence(workdays = true),
        "Every week on $dow" to Recurrence(freq = Freq.WEEKLY, weekdays = listOf(d.dayOfWeek.value)),
        "Every month on the ${Recur.ordinal(d.dayOfMonth)}" to Recurrence(freq = Freq.MONTHLY, monthDay = d.dayOfMonth),
        "Every year on $mon ${d.dayOfMonth}" to Recurrence(freq = Freq.YEARLY, month = d.monthValue, monthDay = d.dayOfMonth),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditor(vm: AppViewModel, screen: Screen.EventEdit) {
    val settings by vm.settingsRepo.state.collectAsState()
    val original = remember(screen) { screen.eventId?.let { vm.store.event(it) } }
    if (screen.eventId != null && original == null) {
        LaunchedEffect(screen) { vm.back() }
        return
    }
    val initial = remember(screen) {
        when {
            original != null && screen.occurrence != null ->
                original.occurrences(screen.occurrence, screen.occurrence).firstOrNull()?.let { original.withSpan(it.start, it.end, it.allDay) } ?: original
            original != null -> original
            else -> {
                val start = screen.start ?: nextHalfHour()
                Event.create("", start.toLocalDate(), if (screen.allDay) null else start.toLocalTime())
                    .let { if (screen.allDay) it else it.withSpan(start, start.plusMinutes(settings.eventMinutes.toLong()), false) }
                    .copy(groupId = settings.defaultCalendar?.takeIf { vm.store.group(it) != null }, reminders = settings.eventReminders)
            }
        }
    }
    var e by remember(screen) { mutableStateOf(initial) }
    val recurringInstance = original?.recurrence != null && screen.occurrence != null
    var scopeFor by remember { mutableStateOf<String?>(null) } // "save" or "delete"
    var picker by remember { mutableStateOf<String?>(null) } // startDate, endDate, startTime, endTime, until
    var repeatMenu by remember { mutableStateOf(false) }
    var customRepeat by remember { mutableStateOf(false) }
    var reminderMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    val p = LocalPalette.current

    fun setStart(s: LocalDateTime) {
        val length = java.time.Duration.between(e.start, e.end)
        e = e.withSpan(s, s.plus(length), e.allDay)
    }

    fun setEnd(end: LocalDateTime) {
        e = e.withSpan(e.start, if (end.isBefore(e.start)) e.start else end, e.allDay)
    }

    fun save(scope: EditScope) {
        vm.saveEvent(e.copy(title = e.title.trim()), original, screen.occurrence, scope)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.Close, "Close") } },
                title = { Text(if (original == null) "New event" else "Edit event") },
                actions = {
                    TextButton(enabled = e.title.isNotBlank(), onClick = { if (recurringInstance) scopeFor = "save" else save(EditScope.ALL) }) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp)) {
            if (original == null) {
                OutlinedTextField(
                    quick, { quick = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large,
                    placeholder = { Text("Quick: Lunch tomorrow 1-2pm #Work") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val q = Nlp.parseEvent(quick, cfg = settings.nlp)
                        val st = q.startTime
                        val et = q.endTime
                        var x = if (st != null && et != null) e.withSpan(q.date.atTime(st), q.endDate.atTime(et), false)
                        else e.withSpan(q.date.atStartOfDay(), q.endDate.atStartOfDay(), true)
                        x = x.copy(title = q.title.ifBlank { e.title }, recurrence = q.recurrence ?: e.recurrence, urgent = e.urgent || q.urgent)
                        if (q.reminders.isNotEmpty()) x = x.copy(reminders = q.reminders)
                        q.group?.let { x = x.copy(groupId = vm.store.groupIdForName(it)) }
                        e = x
                        quick = ""
                    }),
                )
                if (quick.isNotBlank()) {
                    val q = remember(quick) { Nlp.parseEvent(quick, cfg = settings.nlp) }
                    val whenText = if (q.startTime == null) Format.date(q.date) + " · all day"
                    else Format.date(q.date) + " " + Format.time(q.startTime) + "–" + Format.time(q.endTime ?: q.startTime)
                    Text("→ “${q.title}” · $whenText" + (q.recurrence?.let { " · " + Recur.describe(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            TextField(
                e.title, { e = e.copy(title = it) }, Modifier.fillMaxWidth(), placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.headlineSmall,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            )

            Line(Icons.Filled.Schedule) {
                Text("All day", Modifier.weight(1f))
                Switch(e.allDay, { all ->
                    e = if (all) e.withSpan(e.start.toLocalDate().atStartOfDay(), e.end.toLocalDate().atStartOfDay(), true)
                    else e.start.toLocalDate().atTime(9, 0).let { s -> e.withSpan(s, e.end.toLocalDate().atTime(9, 0).plusMinutes(settings.eventMinutes.toLong()), false) }
                })
            }
            Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { picker = "startDate" }, label = { Text(e.start.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) })
                Spacer(Modifier.width(8.dp))
                if (!e.allDay) AssistChip(onClick = { picker = "startTime" }, label = { Text(Format.time(e.start.toLocalTime())) })
            }
            Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { picker = "endDate" }, label = { Text(e.end.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))) })
                Spacer(Modifier.width(8.dp))
                if (!e.allDay) AssistChip(onClick = { picker = "endTime" }, label = { Text(Format.time(e.end.toLocalTime())) })
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line(Icons.Filled.Repeat) {
                Box(Modifier.weight(1f)) {
                    Text(e.recurrence?.let { Recur.describe(it) } ?: "Does not repeat", Modifier.clickable { repeatMenu = true }.padding(vertical = 8.dp))
                    DropdownMenu(repeatMenu, { repeatMenu = false }) {
                        presets(e.start.toLocalDate()).forEach { (label, r) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                e = e.copy(recurrence = r?.copy(until = e.recurrence?.until, count = e.recurrence?.count))
                                repeatMenu = false
                            })
                        }
                        DropdownMenuItem(text = { Text("Custom…") }, onClick = { repeatMenu = false; customRepeat = true })
                    }
                }
            }
            e.recurrence?.let { r ->
                FlowRow(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(r.until == null && r.count == null, { e = e.copy(recurrence = r.copy(until = null, count = null)) }, label = { Text("Forever") })
                    FilterChip(r.until != null, { picker = "until" }, label = { Text(r.untilDate?.let { "Until " + Format.date(it) } ?: "Until…") })
                    FilterChip(r.count != null, { e = e.copy(recurrence = r.copy(until = null, count = r.count ?: 10)) }, label = { Text(r.count?.let { "$it times" } ?: "Times…") })
                }
                if (r.count != null) {
                    OutlinedTextField(
                        r.count.toString(), { v -> v.toIntOrNull()?.takeIf { it in 1..999 }?.let { e = e.copy(recurrence = r.copy(count = it)) } },
                        Modifier.padding(start = 40.dp).width(140.dp), label = { Text("Occurrences") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line(Icons.Filled.Event) {
                Box(Modifier.weight(1f)) {
                    val g = vm.store.group(e.groupId)
                    Row(Modifier.clickable { groupMenu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(g?.let { groupColor(it.color) } ?: cs.primary))
                        Spacer(Modifier.width(8.dp))
                        Text(g?.name ?: "No calendar")
                    }
                    DropdownMenu(groupMenu, { groupMenu = false }) {
                        DropdownMenuItem(text = { Text("No calendar") }, onClick = { e = e.copy(groupId = null); groupMenu = false })
                        vm.store.liveGroups().forEach { gr ->
                            DropdownMenuItem(
                                text = { Text(gr.name) },
                                leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(groupColor(gr.color))) },
                                onClick = { e = e.copy(groupId = gr.id); groupMenu = false },
                            )
                        }
                    }
                }
            }
            FlowRow(Modifier.padding(start = 40.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).border(2.dp, if (e.color == null) cs.onSurface else Color.Transparent, CircleShape)
                        .clickable { e = e.copy(color = null) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", style = MaterialTheme.typography.labelSmall) }
                GROUP_COLORS.forEach { key ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(color(p.named(key)))
                            .then(if (e.color == key) Modifier.border(3.dp, cs.onSurface, CircleShape) else Modifier)
                            .clickable { e = e.copy(color = key) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line(Icons.Filled.Place) {
                TextField(e.location, { e = e.copy(location = it) }, Modifier.weight(1f), placeholder = { Text("Location") }, singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
            }
            Line(Icons.Filled.Link) {
                TextField(e.url, { e = e.copy(url = it) }, Modifier.weight(1f), placeholder = { Text("Link (video call, ticket…)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
            }
            OutlinedTextField(e.notes, { e = e.copy(notes = it) }, Modifier.fillMaxWidth().padding(top = 4.dp).height(110.dp), placeholder = { Text("Notes") },
                shape = MaterialTheme.shapes.medium)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line(Icons.Filled.Notifications) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    e.reminders.sorted().forEach { m ->
                        InputChip(false, { e = e.copy(reminders = e.reminders - m) },
                            label = { Text(if (m == 0L) "At start" else "${formatOffset(m)} before") },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) })
                    }
                    Box {
                        AssistChip(onClick = { reminderMenu = true }, label = { Text("+ Reminder") })
                        DropdownMenu(reminderMenu, { reminderMenu = false }) {
                            REMINDER_CHOICES.filter { it !in e.reminders }.forEach { m ->
                                DropdownMenuItem(text = { Text(if (m == 0L) "At start" else "${formatOffset(m)} before") }, onClick = {
                                    e = e.copy(reminders = e.reminders + m)
                                    reminderMenu = false
                                })
                            }
                        }
                    }
                }
            }
            Line(Icons.Filled.Alarm) {
                Column(Modifier.weight(1f)) {
                    Text("Ring an alarm")
                    Text("Full-screen alarm instead of a notification", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(e.urgent, { e = e.copy(urgent = it) })
            }

            if (original != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val copy = e.copy(id = java.util.UUID.randomUUID().toString(), uid = null, exdates = emptyList(), created = System.currentTimeMillis())
                        vm.saveEvent(copy, null, null, EditScope.ALL)
                        vm.say("Duplicated “${e.title}”")
                    }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Duplicate") }
                    OutlinedButton(onClick = { if (recurringInstance) scopeFor = "delete" else vm.deleteEvent(original.id, null, EditScope.ALL) }) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = cs.error); Spacer(Modifier.width(6.dp)); Text("Delete", color = cs.error)
                    }
                }
            }
        }
    }

    // Pickers and dialogs.
    when (val pk = picker) {
        "startDate", "endDate", "until" -> {
            val current = when (pk) {
                "startDate" -> e.start.toLocalDate()
                "endDate" -> e.end.toLocalDate()
                else -> e.recurrence?.untilDate ?: e.start.toLocalDate().plusMonths(3)
            }
            val state = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            when (pk) {
                                "startDate" -> setStart(d.atTime(e.start.toLocalTime()))
                                "endDate" -> setEnd(d.atTime(e.end.toLocalTime()))
                                else -> e.recurrence?.let { r -> e = e.copy(recurrence = r.copy(until = d.toString(), count = null)) }
                            }
                        }
                        picker = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } },
            ) { DatePicker(state) }
        }
        "startTime", "endTime" -> {
            val t = if (pk == "startTime") e.start.toLocalTime() else e.end.toLocalTime()
            val state = rememberTimePickerState(t.hour, t.minute, is24Hour = Format.use24h)
            AlertDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        val nt = LocalTime.of(state.hour, state.minute)
                        if (pk == "startTime") setStart(e.start.toLocalDate().atTime(nt)) else setEnd(e.end.toLocalDate().atTime(nt))
                        picker = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } },
                text = { TimePicker(state) },
            )
        }
    }
    if (customRepeat) CustomRepeatDialog(e.recurrence, e.start.toLocalDate(), onDismiss = { customRepeat = false }) {
        e = e.copy(recurrence = it)
        customRepeat = false
    }
    scopeFor?.let { what ->
        AlertDialog(
            onDismissRequest = { scopeFor = null },
            title = { Text(if (what == "save") "Save recurring event" else "Delete recurring event") },
            text = { Text("Apply to this event only, or to every event in the series?") },
            confirmButton = {
                TextButton(onClick = {
                    scopeFor = null
                    if (what == "save") save(EditScope.ALL) else vm.deleteEvent(original!!.id, screen.occurrence, EditScope.ALL)
                }) { Text("All events") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scopeFor = null
                    if (what == "save") save(EditScope.THIS) else vm.deleteEvent(original!!.id, screen.occurrence, EditScope.THIS)
                }) { Text("This event") }
            },
        )
    }
}

@Composable
private fun Line(icon: ImageVector, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRepeatDialog(current: Recurrence?, start: LocalDate, onDismiss: () -> Unit, onSet: (Recurrence) -> Unit) {
    var freq by remember { mutableStateOf(current?.freq ?: Freq.WEEKLY) }
    var interval by remember { mutableStateOf((current?.interval ?: 1).toString()) }
    var days by remember { mutableStateOf(current?.weekdays?.takeIf { it.isNotEmpty() } ?: listOf(start.dayOfWeek.value)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every")
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, Modifier.width(80.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Freq.DAILY to "days", Freq.WEEKLY to "weeks", Freq.MONTHLY to "months", Freq.YEARLY to "years").forEach { (f, l) ->
                        FilterChip(freq == f, { freq = f }, label = { Text(l) })
                    }
                }
                if (freq == Freq.WEEKLY) {
                    Spacer(Modifier.height(8.dp))
                    Text("On", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_SHORT.forEachIndexed { i, l ->
                            val d = i + 1
                            FilterChip(d in days, { days = if (d in days && days.size > 1) days - d else (days + d).distinct().sorted() }, label = { Text(l) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = interval.toIntOrNull()?.coerceIn(1, 999) ?: 1
                onSet(
                    Recurrence(
                        freq = freq, interval = n,
                        weekdays = if (freq == Freq.WEEKLY) days else emptyList(),
                        monthDay = if (freq == Freq.MONTHLY || freq == Freq.YEARLY) start.dayOfMonth else null,
                        month = if (freq == Freq.YEARLY) start.monthValue else null,
                        until = current?.until, count = current?.count,
                    ),
                )
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
