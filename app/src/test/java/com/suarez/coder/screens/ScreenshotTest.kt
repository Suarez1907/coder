package com.suarez.coder.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.llm.Usage
import com.suarez.coder.ui.ChatScreen
import com.suarez.coder.ui.ChatViewModel
import com.suarez.coder.ui.CoderTheme
import com.suarez.coder.ui.SettingsScreen
import org.json.JSONObject
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to PNG files (build/outputs/roborazzi) so the layout can be checked without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun vm(): ChatViewModel {
        val vm = ChatViewModel(ApplicationProvider.getApplicationContext())
        vm.settings.defaultRepo = "Suarez1907/kay-p-kuleler"
        vm.settings.architectMode = true
        vm.settings.smartRouting = true
        vm.settings.architectModel = "gemini/gemini-4-hydrogen"
        vm.settings.editorModel = "gemini/gemini-3.8-flash"
        vm.settings.thinkingLevel = "medium"
        vm.messages.clear()
        val c1 = ToolCall("c1", "github_read_files", JSONObject().put("repo", "Suarez1907/kay-p-kuleler").put("paths", org.json.JSONArray(listOf("web/js/engine.js", "web/style.css"))))
        val c2 = ToolCall("c2", "github_read_file", JSONObject().put("repo", "Suarez1907/kay-p-kuleler").put("path", "tools/levels.src.mjs"))
        val c3 = ToolCall("c3", "github_apply_diff", JSONObject().put("repo", "Suarez1907/kay-p-kuleler").put("branch", "feat/levels").put("diff", "x"))
        vm.messages.addAll(listOf(
            ChatMessage.User("Oyuna 5 yeni seviye ekle ve zorluğu kademeli artır"),
            ChatMessage.Assistant("Sürüm hazır: https://github.com/Suarez1907/kay-p-kuleler/releases/download/v1.3.0/app-release.apk"),
            ChatMessage.RouteInfo("Decision 1 → Mimar Modu (gemini-4-hydrogen + gemini-3.8-flash, %88 güven, zorluk 2.1/3) • 3/50 dosya seçildi • 289 ms", "detay", "COMPLEX_ARCHITECT", false),
            ChatMessage.Notice("🗺 Repo haritası: 52 dosya, ~5.8k token (önbellekten, 165715a)", "harita"),
            ChatMessage.Assistant("", listOf(c1)),
            ChatMessage.ToolResult("c1", c1.name, "..."),
            ChatMessage.UsageRecord("gemini-4-hydrogen", "architect", Usage(18_000, 900, 9_000, 2_000)),
            ChatMessage.Assistant("", listOf(c2)),
            ChatMessage.ToolResult("c2", c2.name, "..."),
            ChatMessage.Assistant("", listOf(c3)),
            ChatMessage.ToolResult("c3", c3.name, "commit ok"),
            ChatMessage.Assistant("✅ Plan uygulandı.\n\n`tools/levels.src.mjs` +84 −3 · `web/js/engine.js` +12 −2\nhttps://github.com/Suarez1907/kay-p-kuleler/commit/abc1234"),
            ChatMessage.Notice("💰 Karar 7.3k→0 \$0.00073 · Mimar (gemini-4-hydrogen) 157.7k→4.8k \$0.133 · Toplam \$0.136 (₺6.79) · önbellek %62", "adımlar"),
        ))
        return vm
    }

    @Test fun chatDark() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { ChatScreen(vm) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_dark.png")
    }

    @Test fun chatOled() {
        val vm = vm()
        compose.setContent { CoderTheme("oled") { ChatScreen(vm) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_oled.png")
    }

    @Test fun settingsArchitect() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { SettingsScreen(vm) {} } }
        compose.onNode(androidx.compose.ui.test.hasText("Mimar & Yönlendirme")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_architect.png")
    }

    @Test fun controlSheetAndExpandedTools() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { ChatScreen(vm) {} } }
        compose.onNode(androidx.compose.ui.test.hasText("3 işlem tamamlandı", substring = true)).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat_tools_open.png")
        compose.onNode(androidx.compose.ui.test.hasText("Bağlam: 0", substring = true)).performClick()
        compose.waitForIdle()
        // The bottom sheet is its own window: capture that root.
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].captureRoboImage("build/outputs/roborazzi/chat_sheet.png")
    }

    @Test fun settingsHome() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { SettingsScreen(vm) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_home.png")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRoot() =
    onNode(androidx.compose.ui.test.isRoot())
