package dev.pact.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import dev.pact.app.ui.screens.HomeScreen
import dev.pact.app.ui.screens.SettingsScreen
import dev.pact.app.ui.screens.TaskScreen

@Composable
fun PactRoot(vm: AppViewModel) {
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    when (val s = vm.screen) {
        Screen.Home -> HomeScreen(vm)
        is Screen.TaskDetail -> TaskScreen(vm, s.id)
        Screen.SettingsScreen -> SettingsScreen(vm)
        is Screen.EventEdit -> dev.pact.app.ui.calendar.EventEditor(vm, s)
    }
}
