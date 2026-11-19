package com.suarez.coder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.suarez.coder.ui.ChatScreen
import com.suarez.coder.ui.ChatViewModel
import com.suarez.coder.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(
                primary = Color(0xFF7FD1FF), secondary = Color(0xFFB4F08C),
                background = Color(0xFF101418), surface = Color(0xFF101418),
            ) else lightColorScheme(primary = Color(0xFF0B6BA8), secondary = Color(0xFF3B7A12))
            MaterialTheme(colorScheme = colors) {
                var showSettings by rememberSaveable { mutableStateOf(needsSetup()) }
                if (showSettings) {
                    BackHandler { showSettings = false; vm.refreshLabel() }
                    SettingsScreen(vm) { showSettings = false; vm.refreshLabel() }
                } else {
                    ChatScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    private fun needsSetup() = vm.settings.geminiKey.isBlank() && vm.settings.deepseekKey.isBlank()
}
