package dev.pact.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.pact.app.core.Nlp
import dev.pact.app.core.NlpConfig
import dev.pact.app.core.Parsed
import dev.pact.app.core.Recur
import dev.pact.app.data.QuickAddDefaults
import dev.pact.app.data.formatOffset
import dev.pact.app.ui.Format
import dev.pact.app.ui.theme.priorityColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
private fun PreviewChip(icon: ImageVector?, text: String, tint: Color = MaterialTheme.colorScheme.onSurface) {
    AssistChip(
        onClick = {},
        label = { Text(text, color = tint) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(AssistChipDefaults.IconSize), tint = tint) } },
    )
}

/** Shows what the natural-language parser understood. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParsedChips(p: Parsed, defaultDate: LocalDate? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val date = p.date ?: defaultDate
        if (date != null) {
            val far = date.year != LocalDate.now().year || date.isAfter(LocalDate.now().plusDays(6))
            val label = (if (far) date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")) else Format.date(date)) +
                (p.time?.let { " " + Format.time(it) } ?: "")
            PreviewChip(Icons.Filled.Event, label)
        } else p.time?.let { PreviewChip(Icons.Filled.Schedule, Format.time(it)) }
        p.recurrence?.let { PreviewChip(Icons.Filled.Repeat, Recur.describe(it)) }
        p.group?.let { PreviewChip(Icons.Filled.Tag, it) }
        p.priority?.let { PreviewChip(Icons.Filled.Flag, "P$it", priorityColor(it)) }
        p.labels.forEach { PreviewChip(null, "@$it") }
        p.reminders.forEach { PreviewChip(Icons.Filled.Notifications, "${formatOffset(it)} before") }
        if (p.urgent) PreviewChip(Icons.Filled.Alarm, "alarm", MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    cfg: NlpConfig,
    defaults: QuickAddDefaults,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    eventMode: Boolean = false,
    onModeChange: ((Boolean) -> Unit)? = null,
    onAddEvent: (String) -> Unit = {},
    onMoreOptions: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    val hint = if (eventMode) "e.g. Lunch with Sam tomorrow 1-2pm #Work" else "e.g. Meeting tomorrow at 10am #Work p1"
    fun submit() {
        if (text.isBlank()) return
        if (eventMode) onAddEvent(text) else onAdd(text)
        text = ""
    }
    val focus = remember { FocusRequester() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (eventMode) "New event" else "New task", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onModeChange != null) {
                    FilterChip(!eventMode, { onModeChange(false) }, label = { Text("Task") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(eventMode, { onModeChange(true) }, label = { Text("Event") })
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(hint) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    shape = MaterialTheme.shapes.large,
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Add") }
            }
            Spacer(Modifier.size(8.dp))
            if (eventMode) {
                if (text.isNotBlank()) {
                    val e = remember(text, cfg) { Nlp.parseEvent(text, cfg = cfg) }
                    val day = if (!e.dateExplicit && e.recurrence == null) defaults.date ?: e.date else e.date
                    val whenText = if (e.startTime == null) Format.date(day) + " · all day"
                    else Format.date(day) + " · " + Format.time(e.startTime) + " – " + Format.time(e.endTime ?: e.startTime)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreviewChip(Icons.Filled.Event, whenText)
                        e.recurrence?.let { PreviewChip(Icons.Filled.Repeat, Recur.describe(it)) }
                        e.group?.let { PreviewChip(Icons.Filled.Tag, it) }
                        e.reminders.forEach { PreviewChip(Icons.Filled.Notifications, "${formatOffset(it)} before") }
                        if (e.urgent) PreviewChip(Icons.Filled.Alarm, "alarm", MaterialTheme.colorScheme.error)
                    }
                    if (e.title.isNotEmpty()) Text("→ “${e.title}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "Try: tomorrow 3-5pm · friday 10am for 45 min · every monday 9:30 · next week all day · #group",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onMoreOptions != null) {
                    TextButton(onClick = onMoreOptions) { Text("More options…") }
                }
            } else if (text.isNotBlank()) {
                val p = remember(text, cfg) { Nlp.parse(text, cfg = cfg) }
                ParsedChips(p, defaults.date)
                if (p.title.isNotEmpty()) Text("→ “${p.title}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "Try: tomorrow 9am · next week · in 3 business days · every mon, wed · #group @label p1 · remind me 15 min before · !!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
