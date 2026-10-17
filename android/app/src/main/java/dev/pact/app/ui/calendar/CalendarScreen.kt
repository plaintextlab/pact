package dev.pact.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pact.app.data.PhoneCalendar
import dev.pact.app.data.PhoneEvent
import dev.pact.app.data.Settings
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.CalMode
import dev.pact.app.ui.Format
import dev.pact.app.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private fun startOfWeek(d: LocalDate, weekStart: Int): LocalDate = d.minusDays(((d.dayOfWeek.value + 7 - weekStart) % 7).toLong())

/** The day range a mode shows around [day]. */
private fun range(mode: CalMode, day: LocalDate, month: YearMonth, weekStart: Int): Pair<LocalDate, LocalDate> = when (mode) {
    CalMode.MONTH -> {
        val start = startOfWeek(month.atDay(1), weekStart)
        start to start.plusDays(41)
    }
    CalMode.WEEK -> startOfWeek(day, weekStart).let { it to it.plusDays(6) }
    CalMode.DAY -> day to day
    CalMode.AGENDA -> day to day.plusDays(60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: AppViewModel, settings: Settings) {
    val context = LocalContext.current
    val events by vm.store.events.collectAsState()
    val tasks by vm.store.tasks.collectAsState()
    val groups by vm.store.groups.collectAsState()
    val p = LocalPalette.current
    val mode = vm.calMode
    val weekStart = if (settings.nlp.weekStart == 7) 7 else 1
    val (from, to) = range(mode, vm.calDay, vm.calMonth, weekStart)

    var phone by remember { mutableStateOf(emptyList<PhoneEvent>()) }
    LaunchedEffect(from, to, settings.showPhoneCalendars) {
        phone = if (settings.showPhoneCalendars) withContext(Dispatchers.IO) { PhoneCalendar.load(context, from, to) } else emptyList()
    }
    val entries = remember(events, tasks, groups, phone, from, to, settings.calendarShowTasks, settings.showCompleted, p) {
        buildEntries(vm.store, events, tasks, groups, phone, from, to, settings.calendarShowTasks, settings.showCompleted, p)
    }

    fun move(step: Long) {
        when (mode) {
            CalMode.MONTH -> {
                vm.calMonth = vm.calMonth.plusMonths(step)
                vm.calDay = vm.calMonth.atDay(minOf(vm.calDay.dayOfMonth, vm.calMonth.lengthOfMonth()))
            }
            CalMode.WEEK -> vm.calDay = vm.calDay.plusWeeks(step)
            CalMode.DAY -> vm.calDay = vm.calDay.plusDays(step)
            CalMode.AGENDA -> vm.calDay = vm.calDay.plusDays(30 * step)
        }
        if (mode != CalMode.MONTH) vm.calMonth = YearMonth.from(vm.calDay)
    }

    fun open(e: CalEntry) {
        when (e.kind) {
            EntryKind.EVENT -> vm.openEvent(e.occurrence!!.event.id, e.occurrence.date)
            EntryKind.TASK -> vm.openTask(e.task!!.id)
            EntryKind.PHONE -> runCatching { context.startActivity(PhoneCalendar.openIntent(e.phone!!.id)) }
        }
    }

    var picking by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }

    Column(
        Modifier.fillMaxSize().pointerInput(mode) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (drag > 160) move(-1) else if (drag < -160) move(1)
                    drag = 0f
                },
                onHorizontalDrag = { _, dx -> drag += dx },
            )
        },
    ) {
        // Header: period title, navigation, view switcher.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = when (mode) {
                CalMode.MONTH -> vm.calMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                CalMode.WEEK -> "${from.format(DateTimeFormatter.ofPattern("d MMM"))} – ${to.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
                CalMode.DAY -> vm.calDay.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy"))
                CalMode.AGENDA -> "From " + vm.calDay.format(DateTimeFormatter.ofPattern("EEE d MMM"))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).clickable { picking = true }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { move(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
            TextButton(onClick = { vm.showCalendar(LocalDate.now()) }) { Text("Today") }
            IconButton(onClick = { move(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            CalMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = mode == m, onClick = { vm.setCalendarMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, CalMode.entries.size), icon = {}) {
                    Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 13.sp)
                }
            }
        }

        when (mode) {
            CalMode.MONTH -> MonthView(vm, entries, from, weekStart, ::open)
            CalMode.WEEK, CalMode.DAY -> TimeGrid(
                days = (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) },
                entries = entries,
                onEntry = ::open,
                onNew = { vm.newEvent(it) },
                onDay = { d -> vm.calDay = d; vm.setCalendarMode(CalMode.DAY) },
            )
            CalMode.AGENDA -> AgendaView(vm, entries, from, to, ::open)
        }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.calDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.showCalendar(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthView(vm: AppViewModel, entries: List<CalEntry>, gridStart: LocalDate, weekStart: Int, open: (CalEntry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val month = vm.calMonth
    val day = vm.calDay
    val dayEntries = entries.filter { it.covers(day) }
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    Text(
                        gridStart.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEE")), Modifier.weight(1f),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            for (w in 0 until 6) {
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val d = gridStart.plusDays(w * 7L + i)
                        val inMonth = d.month == month.month
                        val list = entries.filter { it.covers(d) }
                        Column(
                            Modifier.weight(1f).aspectRatio(0.62f).padding(1.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (d == day) cs.secondaryContainer else if (inMonth) cs.surfaceContainer else cs.surfaceContainerLow)
                                .then(if (d == day) Modifier.border(1.5.dp, cs.primary, RoundedCornerShape(8.dp)) else Modifier)
                                .combinedClickable(
                                    onClick = {
                                        vm.calDay = d
                                        if (!inMonth) vm.calMonth = YearMonth.from(d)
                                    },
                                    onLongClick = { vm.newEvent(d.atTime(9, 0)) },
                                )
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(if (d == today) cs.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(
                                    "${d.dayOfMonth}", fontSize = 11.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                    color = when { d == today -> cs.onPrimary; inMonth -> cs.onSurface; else -> cs.onSurfaceVariant },
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                list.take(3).forEach { Chip(it, Modifier.fillMaxWidth(), fontSize = 8) }
                            }
                            if (list.size > 3) Text("+${list.size - 3}", fontSize = 8.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                "${day.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))} · ${Format.date(day)}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
            )
            if (dayEntries.isEmpty()) {
                Text("Nothing scheduled. Tap + to add an event, or long-press a day.", color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
        items(dayEntries, key = { it.key }) { EntryRow(vm, it, open) }
    }
}

@Composable
private fun AgendaView(vm: AppViewModel, entries: List<CalEntry>, from: LocalDate, to: LocalDate, open: (CalEntry) -> Unit) {
    val days = (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }.mapNotNull { d ->
        // Multi-day entries show on their first visible day only.
        val list = entries.filter { it.covers(d) && (it.date == d || d == from) }
        if (list.isEmpty()) null else d to list
    }
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
        if (days.isEmpty()) item {
            Text("Nothing in the next two months.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        for ((d, list) in days) {
            item(key = "h$d") {
                Text(
                    "${d.format(DateTimeFormatter.ofPattern("EEE d MMM"))} · ${Format.date(d)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (d == LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 2.dp),
                )
            }
            items(list, key = { "$d/${it.key}" }) { EntryRow(vm, it, open) }
        }
    }
}

/** One event/task/phone entry in a list. */
@Composable
fun EntryRow(vm: AppViewModel, e: CalEntry, open: (CalEntry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer)
            .clickable { open(e) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.kind == EntryKind.TASK) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).border(2.dp, e.color, CircleShape)
                    .background(if (e.done) e.color else Color.Transparent)
                    .clickable(enabled = !e.projected) { vm.setDone(e.task!!.id, !e.done) },
                contentAlignment = Alignment.Center,
            ) { if (e.done) Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = cs.background) }
        } else {
            Box(Modifier.width(5.dp).height(36.dp).clip(RoundedCornerShape(3.dp)).background(if (e.projected) e.color.copy(alpha = 0.4f) else e.color))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (e.done) TextDecoration.LineThrough else null,
                color = if (e.done || e.projected) cs.onSurfaceVariant else cs.onSurface,
            )
            val time = when {
                e.allDay && e.lastDay != e.date -> "${Format.date(e.date)} – ${Format.date(e.lastDay)}"
                e.allDay -> if (e.kind == EntryKind.TASK) "Task" else "All day"
                e.kind == EntryKind.TASK -> "Task · " + Format.time(e.start.toLocalTime())
                else -> Format.time(e.start.toLocalTime()) + " – " + Format.time(e.end.toLocalTime()) +
                    if (e.end.toLocalDate() != e.date) " (" + Format.date(e.end.toLocalDate()) + ")" else ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                if (e.repeats) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Repeat, null, Modifier.size(13.dp), tint = cs.onSurfaceVariant)
                }
                if (e.kind == EntryKind.PHONE) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(13.dp), tint = cs.onSurfaceVariant)
                    Text(" " + e.phone!!.calendar, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
            if (e.location.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, null, Modifier.size(13.dp), tint = cs.onSurfaceVariant)
                    Text(e.location, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
