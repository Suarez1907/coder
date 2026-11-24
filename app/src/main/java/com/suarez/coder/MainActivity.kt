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
        // Crashes are written to disk: a learning signal and the trigger for rolling back a bad self-update.
        com.suarez.coder.evolution.CrashRecorder.install(this)
        enableEdgeToEdge()
        handleInstallIntent(intent)
        setContent {
            @Suppress("UNUSED_VARIABLE") val v = vm.settingsVersion // re-read the theme when settings change
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

    override fun onStop() {
        vm.flushSaves()
        super.onStop()
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
}
