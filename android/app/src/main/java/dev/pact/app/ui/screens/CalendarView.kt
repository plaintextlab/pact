package dev.pact.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pact.app.core.Recur
import dev.pact.app.core.Task
import dev.pact.app.core.TaskOrder
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.Format
import dev.pact.app.ui.components.TaskRow
import dev.pact.app.ui.theme.groupColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private data class Occurrence(val task: Task, val projected: Boolean)

/** Real due dates plus projected future occurrences of recurring tasks. */
private fun occurrences(tasks: Collection<Task>, from: LocalDate, to: LocalDate, showCompleted: Boolean): Map<LocalDate, List<Occurrence>> {
    val map = mutableMapOf<LocalDate, MutableList<Occurrence>>()
    for (t in tasks.filter { !it.deleted && (showCompleted || !it.completed) }.sortedWith(TaskOrder)) {
        val d = t.date ?: continue
        if (!d.isBefore(from) && !d.isAfter(to)) map.getOrPut(d) { mutableListOf() } += Occurrence(t, false)
        val r = t.recurrence ?: continue
        if (t.completed) continue
        var x = Recur.nextAfter(r, d)
        var guard = 0
        while (!x.isAfter(to) && guard++ < 400) {
            if (!x.isBefore(from)) map.getOrPut(x) { mutableListOf() } += Occurrence(t, true)
            x = Recur.nextAfter(r, x)
        }
    }
    return map
}

@Composable
fun CalendarView(vm: AppViewModel, tasks: Map<String, Task>, showCompleted: Boolean) {
    val month: YearMonth = vm.calMonth
    val weekStart = if (vm.settingsRepo.value.nlp.weekStart == 7) 7 else 1
    val first = month.atDay(1)
    val offset = (first.dayOfWeek.value + 7 - weekStart) % 7
    val gridStart = first.minusDays(offset.toLong())
    val weeks = ((offset + month.lengthOfMonth() + 6) / 7)
    val gridEnd = gridStart.plusDays(weeks * 7L - 1)
    val occ = occurrences(tasks.values, gridStart, gridEnd, showCompleted)
    val today = LocalDate.now()
    val day = vm.calDay
    val groupColors = vm.store.liveGroups().associate { it.id to groupColor(it.color) }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { vm.calMonth = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                FilledTonalButton(onClick = { vm.calMonth = YearMonth.now(); vm.calDay = today }) { Text("Today") }
                IconButton(onClick = { vm.calMonth = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (i in 0 until 7) {
                    Text(gridStart.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEEEE")), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until 7) {
                        val d = gridStart.plusDays(w * 7L + i)
                        DayCell(d, d.month == first.month, d == today, d == day, occ[d].orEmpty(), groupColors, Modifier.weight(1f)) {
                            vm.calDay = d
                            if (d.month != first.month) vm.calMonth = YearMonth.from(d)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Text("${day.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))} · ${Format.date(day)}", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
        }
        val forDay = occ[day].orEmpty()
        val real = forDay.filter { !it.projected }.map { it.task }
        if (forDay.isEmpty()) item {
            Text("Nothing scheduled. Tap + to add a task for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(real, key = { it.id }) { TaskRow(vm, it, tasks, showParent = true, showCompleted = showCompleted) }
        items(forDay.filter { it.projected }, key = { "p" + it.task.id }) { o ->
            Row(Modifier.fillMaxWidth().clickable { vm.openTask(o.task.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("${o.task.title} (repeats)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DayCell(d: LocalDate, inMonth: Boolean, isToday: Boolean, selected: Boolean, items: List<Occurrence>, groupColors: Map<String, Color>, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        selected -> cs.secondaryContainer
        inMonth -> cs.surfaceContainer
        else -> cs.surfaceContainerLow
    }
    Column(
        modifier.aspectRatio(0.72f).clip(MaterialTheme.shapes.small).background(bg)
            .then(if (selected) Modifier.border(1.5.dp, cs.primary, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (isToday) cs.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text("${d.dayOfMonth}", fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when { isToday -> cs.onPrimary; inMonth -> cs.onSurface; else -> cs.onSurfaceVariant })
        }
        items.take(3).forEach { o ->
            val c = o.task.groupId?.let { id -> groupColors[id] } ?: cs.primary
            Box(
                Modifier.fillMaxWidth().padding(top = 2.dp).height(5.dp).clip(CircleShape)
                    .background(if (o.projected) c.copy(alpha = 0.35f) else c),
            )
        }
        if (items.size > 3) Text("+${items.size - 3}", fontSize = 9.sp, color = cs.onSurfaceVariant)
    }
}
