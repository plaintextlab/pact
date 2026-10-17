package dev.pact.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import dev.pact.app.PactApp
import dev.pact.app.ui.theme.PactTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Picks the calendar widget's layout; also reachable later via "reconfigure". */
class CalendarWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val app = application as PactApp

        setContent {
            val settings by app.settings.state.collectAsState()
            PactTheme(settings) {
                var mode by remember { mutableStateOf("month") }
                var opacity by remember { mutableFloatStateOf(100f) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Calendar widget", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text("Layout", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(mode == "month", { mode = "month" }, label = { Text("Month + upcoming") })
                            FilterChip(mode == "agenda", { mode = "agenda" }, label = { Text("Upcoming list") })
                        }
                        Text("Background opacity: ${opacity.roundToInt()}%", Modifier.padding(top = 16.dp))
                        Slider(opacity, { opacity = it }, valueRange = 0f..100f)
                        Text(
                            "Shows your Pact events" + (if (settings.calendarShowTasks) ", dated tasks" else "") +
                                (if (settings.showPhoneCalendars) " and phone calendars" else "") + ". Change this in Settings → Calendar.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            lifecycleScope.launch {
                                val glanceId = GlanceAppWidgetManager(this@CalendarWidgetConfigActivity).getGlanceIdBy(widgetId)
                                updateAppWidgetState(this@CalendarWidgetConfigActivity, glanceId) { prefs ->
                                    prefs[CalendarWidgetKeys.MODE] = mode
                                    prefs[CalendarWidgetKeys.OPACITY] = opacity.roundToInt()
                                    prefs[CalendarWidgetKeys.OFFSET] = 0
                                }
                                CalendarWidget().update(this@CalendarWidgetConfigActivity, glanceId)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }
                        }, Modifier.fillMaxWidth()) { Text("Add widget") }
                    }
                }
            }
        }
    }
}
