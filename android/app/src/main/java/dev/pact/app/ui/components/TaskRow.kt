package dev.pact.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pact.app.core.Recur
import dev.pact.app.core.Task
import dev.pact.app.data.formatOffset
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.Format
import dev.pact.app.ui.theme.LocalPalette
import dev.pact.app.ui.theme.color
import dev.pact.app.ui.theme.groupColor
import dev.pact.app.ui.theme.priorityColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Round check circle colored by priority, Todoist style. */
@Composable
fun PriorityCheck(done: Boolean, priority: Int, onToggle: () -> Unit) {
    val c = priorityColor(priority)
    IconButton(onClick = onToggle) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (done) c else c.copy(alpha = 0.12f))
                .border(2.dp, c, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.background)
        }
    }
}

@Composable
fun Meta(icon: ImageVector?, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(3.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    vm: AppViewModel,
    t: Task,
    tasks: Map<String, Task>,
    depth: Int = 0,
    tree: Boolean = false,
    showGroup: Boolean = true,
    showParent: Boolean = false,
    showCompleted: Boolean = false,
) {
    val children = tasks.values.filter { it.parentId == t.id && !it.deleted }.sortedWith(dev.pact.app.core.TaskOrder)
    val doneChildren = children.count { it.completed }
    val collapsed = t.id in vm.collapsed
    var menu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val today = LocalDate.now()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = (depth * 20).dp, top = 3.dp, bottom = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = { vm.openTask(t.id) }, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tree && children.isNotEmpty()) {
                IconButton(onClick = { vm.toggleCollapsed(t.id) }, Modifier.size(32.dp)) {
                    Icon(if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, "Expand", tint = muted)
                }
            } else Spacer(Modifier.width(if (tree) 32.dp else 4.dp))
            PriorityCheck(t.completed, t.priority) { vm.setDone(t.id, !t.completed) }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.completed) TextDecoration.LineThrough else null,
                    color = if (t.completed) muted else MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val p = LocalPalette.current
                    t.date?.let { d ->
                        val tint = when {
                            t.isOverdue() -> color(p.red)
                            d == today -> color(p.green)
                            ChronoUnit.DAYS.between(today, d) < 7 -> color(p.purple)
                            else -> muted
                        }
                        Meta(Icons.Filled.Event, Format.due(t), tint)
                    }
                    t.recurrence?.let { Meta(Icons.Filled.Repeat, Recur.describe(it), muted) }
                    t.deadline?.let { d ->
                        val left = ChronoUnit.DAYS.between(today, d)
                        val tint = when {
                            t.completed -> muted
                            left <= 1 -> color(p.red)
                            left <= 7 -> color(p.orange)
                            else -> muted
                        }
                        Meta(Icons.Filled.HourglassBottom, Format.deadline(t), tint)
                    }
                    if (t.urgent) Meta(Icons.Filled.Alarm, "alarm", color(p.red))
                    if (t.reminders.isNotEmpty()) Meta(Icons.Filled.Notifications, formatOffset(t.reminders.min()), muted)
                    if (t.pinned) Meta(Icons.Filled.PushPin, "", MaterialTheme.colorScheme.primary)
                    if (children.isNotEmpty()) Meta(Icons.Outlined.Checklist, "$doneChildren/${children.size}", muted)
                    if (t.attachments.isNotEmpty()) Meta(Icons.Filled.AttachFile, "${t.attachments.size}", muted)
                    t.labels.forEach { Meta(null, "@$it", color(p.blue)) }
                    if (showParent) vm.store.task(t.parentId)?.let { Meta(null, "↪ ${it.title}", muted) }
                    if (showGroup) vm.store.group(t.groupId)?.let { Meta(null, "● ${it.name}", groupColor(it.color)) }
                }
            }
        }
        TaskMenu(vm, t, menu) { menu = false }
    }
    if (tree && !collapsed) {
        children.filter { showCompleted || !it.completed }.forEach {
            TaskRow(vm, it, tasks, depth + 1, tree = true, showGroup = false, showCompleted = showCompleted)
        }
    }
}

@Composable
private fun TaskMenu(vm: AppViewModel, t: Task, open: Boolean, onClose: () -> Unit) {
    val today = LocalDate.now()
    val nextWeekDay = vm.settingsRepo.value.nlp.nextWeekDay
    val nextWeek = today.plusDays((7 - today.dayOfWeek.value + nextWeekDay).toLong())
    @Suppress("LocalVariableName")
    val Item: @Composable (String, () -> Unit) -> Unit = { label, action -> DropdownMenuItem(text = { Text(label) }, onClick = { action(); onClose() }) }

    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        Item(if (t.completed) "Mark not done" else "Complete") { vm.setDone(t.id, !t.completed) }
        HorizontalDivider()
        Item("Today") { vm.save(t.withDate(today)) }
        Item("Tomorrow") { vm.save(t.withDate(today.plusDays(1))) }
        Item("Next week") { vm.save(t.withDate(nextWeek)) }
        Item("No date") { vm.save(t.withDate(null).copy(recurrence = null)) }
        HorizontalDivider()
        for (p in 1..4) Item(if (p == 4) "No priority" else "Priority $p") { vm.save(t.copy(priority = p)) }
        HorizontalDivider()
        Item(if (t.pinned) "Unpin notification" else "Pin as notification") { vm.save(t.copy(pinned = !t.pinned)) }
        Item(if (t.urgent) "Turn alarm off" else "Urgent (ring alarm)") { vm.save(t.copy(urgent = !t.urgent)) }
        Item("Delete") { vm.delete(t.id) }
    }
}
