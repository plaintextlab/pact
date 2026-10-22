package dev.pact.app.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.pact.app.MainActivity
import dev.pact.app.PactApp
import dev.pact.app.R
import dev.pact.app.Shortcuts
import dev.pact.app.core.Palette
import dev.pact.app.core.Themes
import dev.pact.app.core.hexColor
import dev.pact.app.data.PhoneCalendar
import dev.pact.app.notify.Notifier
import dev.pact.app.ui.Format
import dev.pact.app.ui.calendar.CalEntry
import dev.pact.app.ui.calendar.buildEntries
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object CalendarWidgetKeys {
    /** "month" (grid + upcoming) or "agenda" (list only). */
    val MODE = stringPreferencesKey("cal_mode")
    /** Months away from the current month, changed by the ‹ › buttons. */
    val OFFSET = intPreferencesKey("month_offset")
    val OPACITY = intPreferencesKey("cal_opacity")
}

private fun openCalendar(context: Context, date: LocalDate?): Intent =
    Intent(context, MainActivity::class.java)
        // Unique data so each day gets its own PendingIntent.
        .setData(Uri.parse("pact://calendar/${date ?: "today"}"))
        .putExtra(MainActivity.EXTRA_OPEN, Shortcuts.CALENDAR)
        .apply { if (date != null) putExtra(Notifier.EXTRA_DATE, date.toString()) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

class CalendarWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        if (widgetKind(context, id) == WidgetKind.TASKS) return PactWidget().provideGlance(context, id)
        val app = context.applicationContext as PactApp
        val s = app.settings.value
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (s.themeMode) { "dark" -> true; "light" -> false; else -> night }
        val theme = Themes.get(context, s.theme)
        val p = if (dark) theme.dark else theme.light
        val today = LocalDate.now()
        // Wide enough for any month offset the user is likely to page through in one session.
        val from = today.minusMonths(13).withDayOfMonth(1)
        val to = today.plusMonths(14)
        val phone = if (s.showPhoneCalendars) PhoneCalendar.load(context, today.minusMonths(2), today.plusMonths(3)) else emptyList()
        val entries = buildEntries(app.store, app.store.events.value, app.store.tasks.value, app.store.groups.value, phone, from, to, s.calendarShowTasks, false, p)
        provideContent {
            Content(context, currentState(), entries, p, today)
        }
    }

    @Composable
    private fun Content(context: Context, prefs: Preferences, entries: List<CalEntry>, p: Palette, today: LocalDate) {
        fun c(hex: String) = Color(hexColor(hex))
        val fg = ColorProvider(c(p.fg))
        val muted = ColorProvider(c(p.muted))
        val primary = c(p.primary)
        val mode = prefs[CalendarWidgetKeys.MODE] ?: "month"
        val opacity = (prefs[CalendarWidgetKeys.OPACITY] ?: 100).coerceIn(0, 100)
        val month = YearMonth.from(today).plusMonths((prefs[CalendarWidgetKeys.OFFSET] ?: 0).toLong())

        Column(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(c(p.bg).copy(alpha = opacity / 100f)).padding(10.dp)) {
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mode == "month") month.format(DateTimeFormatter.ofPattern("MMMM yyyy")) else "Upcoming",
                    GlanceModifier.defaultWeight().clickable(actionStartActivity(openCalendar(context, if (mode == "month") month.atDay(1).takeIf { month != YearMonth.from(today) } else null))),
                    style = TextStyle(color = ColorProvider(primary), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (mode == "month") {
                    Text("‹", GlanceModifier.padding(horizontal = 10.dp).clickable(actionRunCallback<PrevMonthAction>()), style = TextStyle(color = fg, fontSize = 20.sp))
                    Text("•", GlanceModifier.padding(horizontal = 4.dp).clickable(actionRunCallback<ThisMonthAction>()), style = TextStyle(color = muted, fontSize = 16.sp))
                    Text("›", GlanceModifier.padding(horizontal = 10.dp).clickable(actionRunCallback<NextMonthAction>()), style = TextStyle(color = fg, fontSize = 20.sp))
                }
                Spacer(GlanceModifier.width(4.dp))
                Image(
                    ImageProvider(R.drawable.ic_add), "New event",
                    colorFilter = ColorFilter.tint(ColorProvider(c(p.bg))),
                    modifier = GlanceModifier.size(30.dp).shapeBackground(primary, R.drawable.widget_circle).padding(5.dp)
                        .clickable(actionStartActivity(Shortcuts.intent(context, Shortcuts.NEW_EVENT).setData(Uri.parse("pact://new_event")))),
                )
            }
            if (mode == "month") {
                MonthGrid(context, month, entries, p, today)
                Spacer(GlanceModifier.height(4.dp))
                Upcoming(context, entries.filter { !it.lastDay.isBefore(today) }.take(3), p, today, compact = true)
            } else {
                Upcoming(context, entries.filter { !it.lastDay.isBefore(today) && !it.date.isAfter(today.plusDays(30)) }.take(40), p, today, compact = false)
            }
        }
    }

    @Composable
    private fun MonthGrid(context: Context, month: YearMonth, entries: List<CalEntry>, p: Palette, today: LocalDate) {
        fun c(hex: String) = Color(hexColor(hex))
        val app = context.applicationContext as PactApp
        val weekStart = if (app.settings.value.nlp.weekStart == 7) 7 else 1
        val first = month.atDay(1)
        val start = first.minusDays(((first.dayOfWeek.value + 7 - weekStart) % 7).toLong())
        val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(start, month.atEndOfMonth()) / 7) + 1).toInt()
        Row(GlanceModifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(
                    start.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("EEEEE")), GlanceModifier.defaultWeight(),
                    style = TextStyle(color = ColorProvider(c(p.muted)), fontSize = 10.sp, textAlign = TextAlign.Center),
                )
            }
        }
        for (w in 0 until weeks) {
            Row(GlanceModifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = start.plusDays(w * 7L + i)
                    val inMonth = d.month == month.month
                    val dayEntries = entries.filter { it.covers(d) }
                    Column(
                        GlanceModifier.defaultWeight().padding(vertical = 1.dp).clickable(actionStartActivity(openCalendar(context, d))),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            GlanceModifier.size(22.dp).let { if (d == today) it.shapeBackground(c(p.primary), R.drawable.widget_circle) else it },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${d.dayOfMonth}",
                                style = TextStyle(
                                    color = ColorProvider(if (d == today) c(p.bg) else if (inMonth) c(p.fg) else c(p.muted)),
                                    fontSize = 11.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                                ),
                            )
                        }
                        // Up to three dots in the colors of the day's entries.
                        Text(
                            if (dayEntries.isEmpty()) " " else "•".repeat(minOf(3, dayEntries.size)),
                            style = TextStyle(color = ColorProvider(dayEntries.firstOrNull()?.color ?: Color.Transparent), fontSize = 9.sp, textAlign = TextAlign.Center),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Upcoming(context: Context, list: List<CalEntry>, p: Palette, today: LocalDate, compact: Boolean) {
        fun c(hex: String) = Color(hexColor(hex))
        if (list.isEmpty()) {
            Text("Nothing coming up", GlanceModifier.padding(6.dp), style = TextStyle(color = ColorProvider(c(p.muted)), fontSize = 12.sp))
            return
        }
        LazyColumn {
            items(list, itemId = { it.key.hashCode().toLong() }) { e ->
                val day = if (e.date.isBefore(today)) today else e.date
                Row(
                    GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(actionStartActivity(openCalendar(context, day))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(GlanceModifier.width(4.dp).height(if (compact) 26.dp else 32.dp).shapeBackground(e.color, R.drawable.widget_bar)) {}
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(e.title, maxLines = 1, style = TextStyle(color = ColorProvider(c(p.fg)), fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        val time = if (e.allDay) "All day" else Format.time(e.start.toLocalTime())
                        Text("${Format.date(day, today)} · $time", maxLines = 1, style = TextStyle(color = ColorProvider(c(p.muted)), fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

private suspend fun shiftMonth(context: Context, glanceId: GlanceId, by: Int?) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[CalendarWidgetKeys.OFFSET] = if (by == null) 0 else (prefs[CalendarWidgetKeys.OFFSET] ?: 0) + by
    }
    CalendarWidget().update(context, glanceId)
}

class PrevMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = shiftMonth(context, glanceId, -1)
}

class NextMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = shiftMonth(context, glanceId, 1)
}

class ThisMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = shiftMonth(context, glanceId, null)
}

class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()
}
