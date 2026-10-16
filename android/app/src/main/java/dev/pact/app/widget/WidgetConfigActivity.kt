package dev.pact.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import dev.pact.app.PactApp
import dev.pact.app.ui.theme.PactTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Lets the user pick what a widget shows. Also reachable later via "reconfigure". */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val app = application as PactApp

        setContent {
            val settings by app.settings.state.collectAsState()
            PactTheme(settings) {
                var filter by remember { mutableStateOf("today") }
                var title by remember { mutableStateOf("") }
                var subtasks by remember { mutableStateOf(false) }
                var done by remember { mutableStateOf(false) }
                var opacity by remember { mutableFloatStateOf(100f) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("Widget", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text("Show", style = MaterialTheme.typography.titleSmall)
                        Text("You can also tap the widget's title to switch views.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val options = widgetFilters(app).map { it to filterLabel(app, it) }
                            options.forEach { (key, label) -> FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) }) }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title (optional)") }, singleLine = true)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Include subtasks", Modifier.weight(1f)); Switch(subtasks, { subtasks = it })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Include completed", Modifier.weight(1f)); Switch(done, { done = it })
                        }
                        Text("Background opacity: ${opacity.roundToInt()}%", Modifier.padding(top = 12.dp))
                        Slider(opacity, { opacity = it }, valueRange = 0f..100f)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            lifecycleScope.launch {
                                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
                                updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                                    prefs[WidgetKeys.FILTER] = filter
                                    prefs[WidgetKeys.TITLE] = title
                                    prefs[WidgetKeys.SHOW_SUBTASKS] = subtasks
                                    prefs[WidgetKeys.SHOW_DONE] = done
                                    prefs[WidgetKeys.OPACITY] = opacity.roundToInt()
                                }
                                PactWidget().update(this@WidgetConfigActivity, glanceId)
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
