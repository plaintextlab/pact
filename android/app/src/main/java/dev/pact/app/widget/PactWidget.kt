package dev.pact.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.pact.app.MainActivity
import dev.pact.app.PactApp
import dev.pact.app.R
import dev.pact.app.core.Palette
import dev.pact.app.core.Task
import dev.pact.app.core.TaskOrder
import dev.pact.app.core.Themes
import dev.pact.app.core.hexColor
import dev.pact.app.notify.Notifier
import dev.pact.app.ui.Format
import java.time.LocalDate

/** Per-widget configuration, stored in Glance state. */
object WidgetKeys {
    /** "today", "upcoming", "inbox", "all" or "group:<id>". */
    val FILTER = stringPreferencesKey("filter")
    val TITLE = stringPreferencesKey("title")
    val SHOW_SUBTASKS = booleanPreferencesKey("show_subtasks")
    val SHOW_DONE = booleanPreferencesKey("show_done")
    /** Background opacity 0–100. */
    val OPACITY = intPreferencesKey("opacity")
    val TASK_ID = ActionParameters.Key<String>("task_id")
}

fun widgetTasks(all: Collection<Task>, filter: String, showSubtasks: Boolean, showDone: Boolean): List<Task> {
    val today = LocalDate.now()
    return all.filter { !it.deleted && (showDone || !it.completed) && (showSubtasks || it.parentId == null) }.filter { t ->
        when {
            filter == "today" -> t.date?.let { !it.isAfter(today) } == true
            filter == "upcoming" -> t.date?.let { !it.isAfter(today.plusDays(7)) } == true
            filter == "inbox" -> t.groupId == null
            filter.startsWith("group:") -> t.groupId == filter.removePrefix("group:")
            else -> true
        }
    }.sortedWith(TaskOrder)
}

class PactWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as PactApp
        provideContent {
            val prefs = currentState<Preferences>()
            val tasks by app.store.tasks.collectAsState()
            val settings by app.settings.state.collectAsState()
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (settings.themeMode) { "dark" -> true; "light" -> false; else -> night }
            val theme = Themes.get(context, settings.theme)
            Content(context, app, prefs, tasks.values, if (dark) theme.dark else theme.light)
        }
    }

    @Composable
    private fun Content(context: Context, app: PactApp, prefs: Preferences, tasks: Collection<Task>, p: Palette) {
        val filter = prefs[WidgetKeys.FILTER] ?: "today"
        val opacity = (prefs[WidgetKeys.OPACITY] ?: 100).coerceIn(0, 100)
        val title = prefs[WidgetKeys.TITLE]?.takeIf { it.isNotBlank() } ?: when {
            filter.startsWith("group:") -> app.store.group(filter.removePrefix("group:"))?.name ?: "Tasks"
            else -> filter.replaceFirstChar { it.uppercase() }
        }
        val list = widgetTasks(tasks, filter, prefs[WidgetKeys.SHOW_SUBTASKS] ?: false, prefs[WidgetKeys.SHOW_DONE] ?: false)
        fun c(hex: String) = Color(hexColor(hex))
        val fg = ColorProvider(c(p.fg))
        val muted = ColorProvider(c(p.muted))

        Column(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(c(p.bg).copy(alpha = opacity / 100f)).padding(12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                    style = TextStyle(color = ColorProvider(c(p.primary)), fontSize = 17.sp, fontWeight = FontWeight.Bold),
                )
                Text("${list.count { !it.completed }}", style = TextStyle(color = muted, fontSize = 13.sp))
                Spacer(GlanceModifier.width(10.dp))
                Image(
                    ImageProvider(R.drawable.ic_add), "Add task",
                    GlanceModifier.size(32.dp).cornerRadius(16.dp).background(c(p.primary)).padding(6.dp)
                        .clickable(actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_QUICK_ADD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))),
                )
            }
            if (list.isEmpty()) {
                Text("All clear ✓", GlanceModifier.padding(8.dp), style = TextStyle(color = muted, fontSize = 14.sp))
            }
            LazyColumn {
                items(list, itemId = { it.id.hashCode().toLong() }) { t ->
                    val prio = c(when (t.priority) { 1 -> p.red; 2 -> p.orange; 3 -> p.blue; else -> p.muted })
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckBox(
                            checked = t.completed,
                            onCheckedChange = actionRunCallback<ToggleTaskAction>(actionParametersOf(WidgetKeys.TASK_ID to t.id)),
                            colors = CheckboxDefaults.colors(checkedColor = ColorProvider(c(p.primary)), uncheckedColor = ColorProvider(prio)),
                        )
                        Column(
                            GlanceModifier.defaultWeight().clickable(
                                actionStartActivity(Intent(context, MainActivity::class.java).putExtra(Notifier.EXTRA_TASK, t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
                            ),
                        ) {
                            Text(t.title, maxLines = 1, style = TextStyle(color = if (t.completed) muted else fg, fontSize = 14.sp))
                            val due = Format.due(t)
                            if (due.isNotEmpty()) {
                                val dueColor = if (t.isOverdue()) c(p.red) else if (t.date == LocalDate.now()) c(p.green) else c(p.muted)
                                Text(due + (if (t.urgent) "  ⏰" else ""), style = TextStyle(color = ColorProvider(dueColor), fontSize = 11.sp))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(2.dp))
                }
            }
        }
    }
}

class ToggleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.TASK_ID] ?: return
        val app = context.applicationContext as PactApp
        val t = app.store.task(id) ?: return
        app.store.setCompleted(id, !t.completed)
        PactWidget().update(context, glanceId)
    }
}

class PactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PactWidget()
}
