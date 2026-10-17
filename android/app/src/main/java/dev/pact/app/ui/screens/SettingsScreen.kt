package dev.pact.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.pact.app.LauncherNames
import dev.pact.app.Shortcuts
import dev.pact.app.data.PhoneCalendar
import dev.pact.app.core.Themes
import dev.pact.app.notify.ReminderScheduler
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.theme.color
import dev.pact.app.ui.theme.isDark
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.math.roundToInt

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settingsRepo.state.collectAsState()
    val sync by vm.app.sync.collectAsState()
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dark = isDark(s, isSystemInDarkTheme())
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.updateSettings { it.copy(showPhoneCalendars = ok) }
        if (!ok) vm.say("Calendar permission is needed to show phone calendars")
    }
    val importIcs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importIcs(it) } }
    val exportIcs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri -> uri?.let { vm.exportIcs(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 48.dp)) {
            item {
                Header("Appearance")
                Text("Color scheme", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Themes.all(context).forEach { th ->
                        val pal = if (dark) th.dark else th.light
                        val selected = s.theme == th.id
                        Surface(
                            onClick = { vm.updateSettings { it.copy(theme = th.id) } },
                            shape = MaterialTheme.shapes.medium,
                            color = color(pal.bg),
                            modifier = Modifier.width(104.dp).then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(pal.primary, pal.red, pal.green, pal.blue).forEach {
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(color(it)))
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(th.name, color = color(pal.fg), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Segmented("Mode", listOf("system" to "System", "light" to "Light", "dark" to "Dark"), s.themeMode) { m -> vm.updateSettings { it.copy(themeMode = m) } }
                Segmented("Time format", listOf("system" to "System", "12h" to "12-hour", "24h" to "24-hour"), s.timeFormat) { f -> vm.updateSettings { it.copy(timeFormat = f) } }
                Text("The launcher icon uses the selected color scheme. Some launchers take a moment to refresh it.",
                    style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 6.dp))

                Header("App name")
                var name by remember { mutableStateOf(s.appName) }
                OutlinedTextField(name, { name = it; vm.updateSettings { st -> st.copy(appName = it.ifBlank { "Pact" }) } }, Modifier.fillMaxWidth(),
                    label = { Text("Title shown in the app") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Launcher icon name", style = MaterialTheme.typography.titleSmall)
                Text("Android only allows names from this list for the home-screen icon.", style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherNames.names.forEach { n ->
                        FilterChip(selected = s.launcherName == n, onClick = { vm.updateSettings { it.copy(launcherName = n, appName = n) }; name = n }, label = { Text(n) })
                    }
                }

                Header("Dates & natural language")
                Text("“next week” means", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAYS.forEachIndexed { i, d ->
                        FilterChip(selected = s.nlp.nextWeekDay == i + 1, onClick = { vm.updateSettings { it.copy(nlp = it.nlp.copy(nextWeekDay = i + 1)) } }, label = { Text(d) })
                    }
                }
                Segmented("“next month / year” means", listOf(false to "The 1st", true to "Same day"), s.nlp.nextPeriodSameDay) { v ->
                    vm.updateSettings { it.copy(nlp = it.nlp.copy(nextPeriodSameDay = v)) }
                }
                Segmented("Numeric dates", listOf(false to "day/month", true to "month/day"), s.nlp.monthFirst) { v ->
                    vm.updateSettings { it.copy(nlp = it.nlp.copy(monthFirst = v)) }
                }
                Segmented("Week starts on", listOf(1 to "Monday", 7 to "Sunday"), s.nlp.weekStart) { v ->
                    vm.updateSettings { it.copy(nlp = it.nlp.copy(weekStart = v)) }
                }
                HourSlider("Morning", s.nlp.morningHour) { h -> vm.updateSettings { it.copy(nlp = it.nlp.copy(morningHour = h)) } }
                HourSlider("Afternoon", s.nlp.afternoonHour) { h -> vm.updateSettings { it.copy(nlp = it.nlp.copy(afternoonHour = h)) } }
                HourSlider("Evening", s.nlp.eveningHour) { h -> vm.updateSettings { it.copy(nlp = it.nlp.copy(eveningHour = h)) } }
                HourSlider("Night / tonight", s.nlp.nightHour) { h -> vm.updateSettings { it.copy(nlp = it.nlp.copy(nightHour = h)) } }
                HourSlider("End of day (eod)", s.nlp.endOfDayHour) { h -> vm.updateSettings { it.copy(nlp = it.nlp.copy(endOfDayHour = h)) } }

                Header("Reminders")
                SwitchLine("Notifications", s.notifications) { v -> vm.updateSettings { it.copy(notifications = v) } }
                var presets by remember { mutableStateOf(s.reminderPresets.joinToString(", ")) }
                OutlinedTextField(presets, {
                    presets = it
                    vm.updateSettings { st -> st.copy(reminderPresets = parseNums(it)) }
                }, Modifier.fillMaxWidth(), label = { Text("Reminder choices (minutes before)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                var defaults by remember { mutableStateOf(s.defaultReminders.joinToString(", ")) }
                OutlinedTextField(defaults, {
                    defaults = it
                    vm.updateSettings { st -> st.copy(defaultReminders = parseNums(it)) }
                }, Modifier.fillMaxWidth(), label = { Text("Default for timed tasks (minutes, empty = none)") }, singleLine = true)
                HourSlider("Snooze (minutes)", s.snoozeMinutes, 1..60, "") { m -> vm.updateSettings { it.copy(snoozeMinutes = m) } }
                if (!ReminderScheduler.canExact(context) && Build.VERSION.SDK_INT >= 31) {
                    Text("Exact alarms are off, so reminders may be late.", color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }) { Text("Allow exact alarms") }
                }

                Header("Calendar")
                SwitchLine("Show tasks in the calendar", s.calendarShowTasks) { v -> vm.updateSettings { it.copy(calendarShowTasks = v) } }
                SwitchLine("Show phone calendars (Google, Exchange…)", s.showPhoneCalendars) { v ->
                    if (v && !PhoneCalendar.hasPermission(context)) askCalendar.launch(Manifest.permission.READ_CALENDAR)
                    else vm.updateSettings { it.copy(showPhoneCalendars = v) }
                }
                Text("Phone calendars are shown read-only; tap one of their events to open it in its own app.", style = MaterialTheme.typography.bodySmall, color = muted)
                Segmented("New events last", listOf(30 to "30 min", 60 to "1 h", 90 to "1.5 h", 120 to "2 h"), s.eventMinutes) { m -> vm.updateSettings { it.copy(eventMinutes = m) } }
                Text("Default calendar for new events", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.defaultCalendar == null, { vm.updateSettings { it.copy(defaultCalendar = null) } }, label = { Text("None") })
                    vm.store.liveGroups().forEach { g ->
                        FilterChip(s.defaultCalendar == g.id, { vm.updateSettings { it.copy(defaultCalendar = g.id) } }, label = { Text(g.name) })
                    }
                }
                var eventRem by remember { mutableStateOf(s.eventReminders.joinToString(", ")) }
                OutlinedTextField(eventRem, {
                    eventRem = it
                    vm.updateSettings { st -> st.copy(eventReminders = parseNums(it)) }
                }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Reminders for new events (minutes before)") }, singleLine = true)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { importIcs.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "*/*")) }) { Text("Import .ics") }
                    FilledTonalButton(onClick = { exportIcs.launch("pact-calendar.ics") }) { Text("Export .ics") }
                }
                Button(onClick = {
                    if (!Shortcuts.pinCalendar(context)) vm.say("Your launcher can't pin shortcuts; long-press the Pact icon and drag “Calendar” instead")
                }, Modifier.padding(top = 8.dp)) { Text("Add Calendar to home screen") }
                Text("Tip: long-press the Pact icon for Calendar, New event and New task shortcuts.", style = MaterialTheme.typography.bodySmall, color = muted)

                Header("Sync (git)")
                Text(
                    "Your tasks live in a folder of a git repository, which can be shared with other apps. Point it at an HTTPS remote (GitHub, GitLab, Gitea…) and use a personal access token as the password. Use the same remote and folder on your computer.",
                    style = MaterialTheme.typography.bodySmall, color = muted,
                )
                Spacer(Modifier.height(8.dp))
                var remote by remember { mutableStateOf(s.gitRemote) }
                var user by remember { mutableStateOf(s.gitUsername) }
                var token by remember { mutableStateOf(s.gitToken) }
                var branch by remember { mutableStateOf(s.gitBranch) }
                OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), label = { Text("Remote URL") }, singleLine = true,
                    placeholder = { Text("https://github.com/you/pact-data.git") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Access token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("Branch") }, singleLine = true)
                var folder by remember { mutableStateOf(s.gitFolder) }
                OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), label = { Text("Pact folder in repository") }, singleLine = true,
                    placeholder = { Text("pact (empty = repository root)") },
                    supportingText = { Text("Lets one repo (e.g. appsync) hold data for several apps. Pact only commits this folder.") })
                HourSlider("Sync every (minutes, 0 = off)", s.autoSyncMinutes, 0..240, "") { m -> vm.updateSettings { it.copy(autoSyncMinutes = m) } }
                SwitchLine("Sync after edits", s.syncOnChange) { v -> vm.updateSettings { it.copy(syncOnChange = v) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val newFolder = folder.trim().trim('/')
                        val folderChanged = newFolder != s.gitFolder
                        vm.updateSettings {
                            it.copy(gitRemote = remote.trim(), gitUsername = user.trim(), gitToken = token.trim(), gitBranch = branch.trim().ifEmpty { "main" }, gitFolder = newFolder)
                        }
                        if (folderChanged) vm.say("Restart Pact to use the new folder") else vm.syncNow()
                    }, enabled = !sync.busy) { Text("Save & sync now") }
                    Spacer(Modifier.width(12.dp))
                    Text(sync.status, color = muted)
                }
                sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                Header("Quick add cheat sheet")
                listOf(
                    "tomorrow 10am · tonight · this evening",
                    "next week · next month · next year",
                    "in 2 days · in 3 hours · in 5 business days",
                    "fri · next tue · 15 march · 20/11 · the 5th",
                    "every day · every weekday · every mon, wed",
                    "every 2 weeks · every 15th · every last day of the month",
                    "#Work  @label  p1",
                    "remind me 15 min before · !30m",
                    "!! · !urgent · alarm 10 min before",
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

private fun parseNums(s: String): List<Long> = s.split(',', ' ').mapNotNull { it.trim().toLongOrNull() }.filter { it >= 0 }.distinct().sorted()

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, text) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(text) }
        }
    }
}

@Composable
private fun HourSlider(label: String, value: Int, range: IntRange = 0..23, suffix: String = ":00", onChange: (Int) -> Unit) {
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text("${v.roundToInt()}$suffix", color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}
