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
import androidx.compose.ui.test.performTextInput
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

    @Test fun diffViewer() {
        val diff = "web/js/engine.js\n<<<<<<< SEARCH\nfunction startLevel(n) {\n  const speed = 1.0;\n  spawn(n);\n}\n=======\n" +
            "function startLevel(n) {\n  const speed = 1.0 + n * 0.15;\n  spawn(n);\n  hud.show(`Seviye ${'$'}{n}`);\n}\n>>>>>>> REPLACE\n"
        compose.setContent { CoderTheme("dark") { com.suarez.coder.ui.DiffViewer(com.suarez.coder.diff.DiffLines.fromSearchReplace(diff)) {} } }
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.isRoot()).let { n -> n[n.fetchSemanticsNodes().size - 1] }
            .captureRoboImage("build/outputs/roborazzi/diff_viewer.png")
    }

    @Test fun slashSuggestions() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { ChatScreen(vm) {} } }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Ne yapmamı istersin?")).performTextInput("/")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/slash.png")
    }

    @Test fun historyDrawerProfilesAndDashboard() {
        val vm = vm()
        vm.saveProfile(com.suarez.coder.data.ProjectProfile(repo = "Suarez1907/coder", branch = "ccr-dev",
            instructions = "Kotlin, Compose", architectModel = "gemini/gemini-4-hydrogen"))
        vm.newChat() // stores the sample chat in history
        compose.setContent { CoderTheme("dark") { ChatScreen(vm) {} } }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Sohbetler")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/history_drawer.png")
        compose.onNode(androidx.compose.ui.test.hasText("Maliyet Panosu", substring = true)).performClick()
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.isRoot()).let { n -> n[n.fetchSemanticsNodes().size - 1] }
            .captureRoboImage("build/outputs/roborazzi/cost_dashboard.png")
    }

    @Test fun profileSheet() {
        val vm = vm()
        vm.saveProfile(com.suarez.coder.data.ProjectProfile(repo = "Suarez1907/coder", branch = "ccr-dev",
            instructions = "Kotlin, Compose", architectModel = "gemini/gemini-4-hydrogen"))
        compose.setContent { CoderTheme("dark") { ChatScreen(vm) {} } }
        compose.onNode(androidx.compose.ui.test.hasText("kay-p-kuleler ▾")).performClick()
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].captureRoboImage("build/outputs/roborazzi/profile_sheet.png")
    }

    @Test fun orchestratorPlanCard() {
        val vm = vm()
        vm.setOrchestratorMode(com.suarez.coder.orchestrator.OrchestratorMode.FULL_MULTI_AGENT)
        val t = com.suarez.coder.orchestrator.TaskStatus.entries
        vm.editPlan(com.suarez.coder.orchestrator.OrchestratorPlan(
            mode = com.suarez.coder.orchestrator.OrchestratorMode.FULL_MULTI_AGENT, request = "Skor tablosu",
            repo = "Suarez1907/kay-p-kuleler", branch = "feat/skor", base = null, commitMessage = "Skor tablosu",
            tasks = listOf(
                com.suarez.coder.orchestrator.SubTask("t1", "Skor modeli", "d", listOf(com.suarez.coder.orchestrator.TaskFile("web/js/score.js", "create", "x")),
                    status = com.suarez.coder.orchestrator.TaskStatus.DONE, costUsd = 0.004),
                com.suarez.coder.orchestrator.SubTask("t2", "Tablo arayüzü", "d", listOf(com.suarez.coder.orchestrator.TaskFile("web/js/ui.js", "modify", "x")),
                    dependsOn = listOf("t1"), status = com.suarez.coder.orchestrator.TaskStatus.FAILED, error = "Uygulayıcı 2 denemede bloğu eşleştiremedi"),
                com.suarez.coder.orchestrator.SubTask("t3", "Seviye kaydı", "d", listOf(com.suarez.coder.orchestrator.TaskFile("tools/levels.src.mjs", "modify", "x")),
                    requiresCi = true),
            ),
            state = com.suarez.coder.orchestrator.PlanState.PAUSED,
            note = "Başarısız: t2. Devam Et bunları hatalarıyla yeniden dener; diğer değişiklikler bekliyor.",
            roleCosts = mapOf("Planlayıcı" to 0.012, "İşçi #1" to 0.004, "İşçi #2" to 0.003),
        ))
        compose.setContent { CoderTheme("dark") { ChatScreen(vm) {} } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/orchestrator_card.png")
        compose.onNode(androidx.compose.ui.test.hasText("Ork: Tam", substring = true)).performClick()
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.isRoot())[1].captureRoboImage("build/outputs/roborazzi/orchestrator_sheet.png")
    }

    @Test fun settingsProviders() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { SettingsScreen(vm) {} } }
        compose.onNode(androidx.compose.ui.test.hasText("Sağlayıcılar", substring = true)).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_providers.png")
    }

    @Test fun evolutionPage() {
        val vm = vm()
        vm.evoStore.savePending(com.suarez.coder.evolution.PendingSelfPr(42, "SEARCH elips eşleşmesi boşlukları yok saysın",
            "https://github.com/Suarez1907/coder/pull/42", "evo/ellipsis-202611221200",
            "`...` satırı girintili olduğunda SEARCH bloğu eşleşmiyordu; elips satırı trim edilmeden karşılaştırılıyordu.",
            listOf("app/src/test/java/com/suarez/coder/EllipsisTest.kt", "app/src/main/java/com/suarez/coder/diff/SearchReplace.kt"),
            true, 0.031, "ellipsis", 0))
        vm.evoStore.addLog(com.suarez.coder.evolution.EvoTier.MEMORY, "Suarez1907/kay-p-kuleler: 1 ders öğrenildi: Seviye dosyaları tools/levels.src.mjs üzerinden üretilir.", 0.004)
        vm.evoStore.addLog(com.suarez.coder.evolution.EvoTier.ROUTING, "1 yönlendirme ipucu (\"seviye rengi\" → SIMPLE)", 0.003, undo = "{}")
        vm.evoStore.addLog(com.suarez.coder.evolution.EvoTier.SELF_PR, "PR #42 açıldı: SEARCH elips eşleşmesi boşlukları yok saysın. PR #42 açıldı.", 0.031)
        vm.refreshEvolution()
        compose.setContent { CoderTheme("dark") { SettingsScreen(vm) {} } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_home.png")
        compose.onNode(androidx.compose.ui.test.hasText("Öz-Gelişim")).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/evolution.png")
    }

    @Test fun settingsHome() {
        val vm = vm()
        compose.setContent { CoderTheme("dark") { SettingsScreen(vm) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_home.png")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRoot() =
    onNode(androidx.compose.ui.test.isRoot())
