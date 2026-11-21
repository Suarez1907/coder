package com.suarez.coder

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.suarez.coder.apk.ApkInstaller
import com.suarez.coder.apk.ApkLinks
import com.suarez.coder.ci.CiWatchService
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
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
        applySystemBars(isDarkTheme(vm.settings.themeMode) ?: isSystemDark())
        handleInstallIntent(intent)
        setContent {
            @Suppress("UNUSED_VARIABLE") val v = vm.settingsVersion // re-read the theme when settings change
            val dark = isDarkTheme(vm.settings.themeMode) ?: isSystemInDarkTheme()
            LaunchedEffect(dark) { applySystemBars(dark) }
            CoderTheme(vm.settings.themeMode) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallIntent(intent)
    }

    /** "APK Kur" from the CI result notification: download (token-aware) and open the package installer. */
    private fun handleInstallIntent(intent: Intent?) {
        val url = intent?.getStringExtra(CiWatchService.EXTRA_INSTALL_URL) ?: return
        intent.removeExtra(CiWatchService.EXTRA_INSTALL_URL)
        val target = ApkLinks.find(url).firstOrNull()?.second ?: return
        Toast.makeText(this, "APK indiriliyor…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = vm.fetchApk(url, target)
            if (file == null) {
                Toast.makeText(this@MainActivity, "APK indirilemedi", Toast.LENGTH_LONG).show()
            } else if (!ApkInstaller.install(this@MainActivity, file)) {
                Toast.makeText(this@MainActivity, "Coder'a uygulama yükleme izni verin, sonra tekrar dokunun", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun needsSetup() = vm.settings.geminiKey.isBlank() && vm.settings.deepseekKey.isBlank() && vm.settings.openrouterKey.isBlank()

    /** Explicit theme decision: "light" -> false, "system" -> null (follow OS), "dark"/"oled" -> true. */
    private fun isDarkTheme(mode: String): Boolean? = when (mode) {
        "light" -> false
        "system" -> null
        else -> true
    }

    private fun isSystemDark() = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Force white system-bar icons in dark/OLED modes even when the OS is in light mode. */
    private fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
