package dev.pact.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pact.app.notify.Notifier
import dev.pact.app.ui.AppViewModel
import dev.pact.app.ui.PactRoot
import dev.pact.app.ui.theme.PactTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as PactApp
        setContent {
            val settings by app.settings.state.collectAsState()
            PactTheme(settings) { PactRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifier.EXTRA_TASK)?.let { vm.openTask(it) }
        if (intent.getBooleanExtra(EXTRA_QUICK_ADD, false)) vm.showQuickAdd = true
    }

    companion object {
        const val EXTRA_QUICK_ADD = "quick_add"
    }
}
