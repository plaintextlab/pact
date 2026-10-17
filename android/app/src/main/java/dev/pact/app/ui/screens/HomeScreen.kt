package dev.pact.app.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pact.app.core.GROUP_COLORS
import dev.pact.app.core.Group
import dev.pact.app.core.Task
import dev.pact.app.core.TaskOrder
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.Format
import dev.pact.app.ui.View
import dev.pact.app.ui.components.QuickAddSheet
import dev.pact.app.ui.components.TaskRow
import dev.pact.app.ui.theme.LocalPalette
import dev.pact.app.ui.theme.color
import dev.pact.app.ui.theme.groupColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val tasks by vm.store.tasks.collectAsState()
    val groups by vm.store.groups.collectAsState()
    val settings by vm.settingsRepo.state.collectAsState()
    val sync by vm.app.sync.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val live = tasks.values.filter { !it.deleted }
    val open = live.filter { !it.completed }
    val today = LocalDate.now()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Text(settings.appName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    fun go(v: View) {
                        vm.view = v
                        vm.search = ""
                        searching = false
                        scope.launch { drawer.close() }
                    }
                    @Suppress("LocalVariableName")
                    val Item: @Composable (View, String, androidx.compose.ui.graphics.vector.ImageVector, Int) -> Unit = { v, label, icon, count ->
                        NavigationDrawerItem(
                            label = { Text(label) },
                            selected = vm.view == v,
                            onClick = { go(v) },
                            icon = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            badge = { if (count > 0) Text("$count", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                    Item(View.Inbox, "Inbox", Icons.Filled.Inbox, open.count { it.groupId == null && it.parentId == null })
                    Item(View.Today, "Today", Icons.Filled.Today, open.count { it.date?.let { d -> !d.isAfter(today) } == true })
                    Item(View.Upcoming, "Upcoming", Icons.Filled.DateRange, open.count { it.date?.isAfter(today) == true })
                    Item(View.Calendar, "Calendar", Icons.Filled.CalendarMonth, 0)
                    Item(View.All, "All tasks", Icons.Filled.ViewList, open.count { it.parentId == null })
                    Item(View.Completed, "Completed", Icons.Filled.CheckCircle, 0)
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Groups", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.editingGroup = Group(name = "", color = "blue") }) { Icon(Icons.Filled.Add, "New group") }
                    }
                    groups.values.filter { !it.deleted }.sortedWith(compareBy({ it.order }, { it.name.lowercase() })).forEach { g ->
                        val v = View.GroupView(g.id)
                        NavigationDrawerItem(
                            label = { Text(g.name) },
                            selected = vm.view == v,
                            onClick = { go(v) },
                            icon = { Box(Modifier.size(14.dp).clip(CircleShape).background(groupColor(g.color))) },
                            badge = {
                                val n = open.count { it.groupId == g.id && it.parentId == null }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (n > 0) Text("$n", style = MaterialTheme.typography.labelMedium)
                                    IconButton(onClick = { vm.editingGroup = g }, Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Settings, "Edit group", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                        )
                    }
                    val labels = live.flatMap { it.labels }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
                    if (labels.isNotEmpty()) {
                        Text("Labels", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        labels.forEach { Item(View.LabelView(it), "@$it", Icons.AutoMirrored.Filled.Label, 0) }
                    }
                    Spacer(Modifier.height(12.dp))
                    NavigationDrawerItem(
                        label = { Text("Settings") }, selected = false, icon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { scope.launch { drawer.close() }; vm.openSettings() },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") } },
                    title = {
                        if (searching) {
                            TextField(
                                value = vm.search, onValueChange = { vm.search = it }, placeholder = { Text("Search tasks") }, singleLine = true,
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else Text(title(vm, groups), maxLines = 1)
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) vm.search = "" }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                        }
                        IconButton(onClick = { vm.updateSettings { it.copy(showCompleted = !it.showCompleted) } }) {
                            Icon(if (settings.showCompleted) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show completed")
                        }
                        if (settings.gitRemote.isNotBlank()) {
                            IconButton(onClick = { vm.syncNow() }, enabled = !sync.busy) {
                                Icon(if (sync.error != null) Icons.Filled.SyncProblem else Icons.Filled.Sync, "Sync",
                                    tint = if (sync.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            floatingActionButton = {
                val inCalendar = vm.view == View.Calendar && vm.search.isEmpty()
                ExtendedFloatingActionButton(
                    onClick = {
                        vm.quickAddEvent = inCalendar
                        vm.showQuickAdd = true
                    },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(if (inCalendar) "New event" else "Add task") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (vm.view == View.Calendar && vm.search.isEmpty()) {
                    dev.pact.app.ui.calendar.CalendarScreen(vm, settings)
                } else {
                    TaskList(vm, tasks, settings.showCompleted)
                }
            }
        }
    }

    if (vm.showQuickAdd) {
        val defaults = vm.defaults()
        QuickAddSheet(
            settings.nlp, defaults,
            onAdd = { vm.quickAdd(it, defaults) },
            onDismiss = { vm.showQuickAdd = false },
            eventMode = vm.quickAddEvent,
            onModeChange = { vm.quickAddEvent = it },
            onAddEvent = { vm.quickAddEvent(it, if (vm.view == View.Calendar) vm.calDay else null) },
            onMoreOptions = {
                vm.showQuickAdd = false
                val day = if (vm.view == View.Calendar) vm.calDay else java.time.LocalDate.now()
                vm.newEvent(day.atTime(java.time.LocalTime.now().plusHours(1).withMinute(0)))
            },
        )
    }
    vm.editingGroup?.let { g -> GroupDialog(vm, g) }
}

private fun title(vm: AppViewModel, groups: Map<String, Group>): String = when (val v = vm.view) {
    View.Inbox -> "Inbox"
    View.Today -> "Today · " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM"))
    View.Upcoming -> "Upcoming"
    View.Calendar -> "Calendar"
    View.All -> "All tasks"
    View.Completed -> "Completed"
    is View.GroupView -> groups[v.id]?.name ?: "Group"
    is View.LabelView -> "@${v.label}"
}

@Composable
private fun TaskList(vm: AppViewModel, tasks: Map<String, Task>, showCompleted: Boolean) {
    val today = LocalDate.now()
    val live = tasks.values.filter { !it.deleted }
    val visible = { t: Task -> showCompleted || !t.completed }
    val dated = { f: (Task) -> Boolean -> live.filter { f(it) && visible(it) }.sortedWith(TaskOrder) }
    val topLevel = { f: (Task) -> Boolean -> live.filter { it.parentId == null && f(it) && visible(it) }.sortedWith(TaskOrder) }
    val pinned = live.filter { it.pinned && !it.completed }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
        if (pinned.isNotEmpty() && vm.search.isEmpty()) item { PinnedStrip(vm, pinned) }

        fun LazyListScope.rows(list: List<Task>, tree: Boolean, empty: String = "") {
            if (list.isEmpty() && empty.isNotEmpty()) item { Empty(empty) }
            items(list, key = { it.id + tree }) {
                TaskRow(vm, it, tasks, tree = tree, showGroup = !tree || vm.view == View.All, showParent = !tree, showCompleted = showCompleted)
            }
        }
        fun LazyListScope.header(text: String, tint: Color? = null) = item {
            Text(text, style = MaterialTheme.typography.titleSmall, color = tint ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 4.dp))
        }

        if (vm.search.isNotEmpty()) {
            val q = vm.search.lowercase()
            rows(dated { it.title.lowercase().contains(q) || it.notes.lowercase().contains(q) || it.labels.any { l -> l.lowercase().contains(q) } }, false, "No matching tasks.")
            return@LazyColumn
        }
        when (val v = vm.view) {
            View.Inbox -> rows(topLevel { it.groupId == null }, true, "Inbox zero. Tap + to add a task.")
            is View.GroupView -> rows(topLevel { it.groupId == v.id }, true, "No tasks in this group yet.")
            View.All -> rows(topLevel { true }, true, "Nothing here yet.")
            is View.LabelView -> rows(dated { t -> t.labels.any { it.equals(v.label, true) } }, false, "No tasks with this label.")
            View.Today -> {
                val overdue = dated { !it.completed && it.date?.isBefore(today) == true }
                if (overdue.isNotEmpty()) {
                    item { OverdueHeader() }
                    rows(overdue, false)
                    header("Today")
                }
                rows(dated { it.date == today }, false, if (overdue.isEmpty()) "Nothing due today. Enjoy! 🎉" else "")
            }
            View.Upcoming -> {
                val overdue = dated { !it.completed && it.date?.isBefore(today) == true }
                if (overdue.isNotEmpty()) {
                    item { OverdueHeader() }
                    rows(overdue, false)
                }
                for (i in 0L until 14L) {
                    val d = today.plusDays(i)
                    header(if (i < 2) "${d.format(DateTimeFormatter.ofPattern("EEE d MMM"))} · ${Format.date(d)}" else d.format(DateTimeFormatter.ofPattern("EEEE d MMM")))
                    rows(dated { it.date == d }, false)
                }
                val later = dated { it.date?.isAfter(today.plusDays(13)) == true }
                if (later.isNotEmpty()) {
                    header("Later")
                    rows(later, false)
                }
            }
            View.Completed -> rows(live.filter { it.completed }.sortedByDescending { it.completedAt ?: 0 }, false, "No completed tasks yet.")
            View.Calendar -> {}
        }
    }
}

@Composable
private fun OverdueHeader() = Text("Overdue", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinnedStrip(vm: AppViewModel, pinned: List<Task>) {
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pinned.forEach { t ->
            AssistChip(onClick = { vm.openTask(t.id) }, label = { Text(listOf(t.title, Format.due(t)).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupDialog(vm: AppViewModel, g: Group) {
    var name by remember(g.id) { mutableStateOf(g.name) }
    var colorKey by remember(g.id) { mutableStateOf(g.color) }
    val isNew = vm.store.group(g.id) == null
    AlertDialog(
        onDismissRequest = { vm.editingGroup = null },
        title = { Text(if (isNew) "New group" else "Edit group") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val p = LocalPalette.current
                    GROUP_COLORS.forEach { key ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(color(p.named(key)))
                                .then(if (colorKey == key) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { colorKey = key },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Colors follow the active color scheme.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                vm.saveGroup(g.copy(name = name.trim(), color = colorKey))
                vm.editingGroup = null
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { vm.deleteGroup(g.id); vm.editingGroup = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.editingGroup = null }) { Text("Cancel") }
            }
        },
    )
}
