package dev.pact.app.notify

import android.app.KeyguardManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pact.app.PactApp
import dev.pact.app.ui.theme.PactTheme

/** Full-screen alarm for urgent tasks, shown over the lock screen. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)

        val app = application as PactApp
        val id = intent.getStringExtra(Notifier.EXTRA_TASK) ?: return finish()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val task = app.store.task(id) ?: return finish()

        setContent {
            val settings by app.settings.state.collectAsState()
            PactTheme(settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Alarm, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(24.dp))
                        Text(task.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(48.dp))
                        Button(onClick = { Notifier.cancel(this@AlarmActivity, id); finish() }, Modifier.fillMaxWidth(0.7f)) { Text("Dismiss") }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = {
                                Notifier.cancel(this@AlarmActivity, id)
                                ReminderScheduler.snooze(this@AlarmActivity, id, settings.snoozeMinutes)
                                finish()
                            }) { Text("Snooze ${settings.snoozeMinutes} min") }
                            FilledTonalButton(onClick = {
                                Notifier.cancel(this@AlarmActivity, id)
                                app.store.setCompleted(id, true)
                                finish()
                            }) { Text("Complete") }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
