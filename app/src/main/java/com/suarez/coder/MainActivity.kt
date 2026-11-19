package com.suarez.coder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.suarez.coder.ui.ChatScreen
import com.suarez.coder.ui.ChatViewModel
import com.suarez.coder.ui.CoderTheme
import com.suarez.coder.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            @Suppress("UNUSED_VARIABLE") val version = vm.settingsVersion
            CoderTheme(
                themeMode = vm.settings.themeMode,
                dynamicColor = vm.settings.dynamicColor,
            ) {
                var showSettings by rememberSaveable { mutableStateOf(needsSetup()) }
                if (showSettings) {
                    BackHandler { showSettings = false; vm.refreshSettings() }
                    SettingsScreen(vm) { showSettings = false; vm.refreshSettings() }
                } else {
                    ChatScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    private fun needsSetup() = vm.settings.geminiKey.isBlank() && vm.settings.deepseekKey.isBlank()
}
