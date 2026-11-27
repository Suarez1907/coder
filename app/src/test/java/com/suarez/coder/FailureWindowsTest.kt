package com.suarez.coder

import com.suarez.coder.evolution.EvalGate
import com.suarez.coder.evolution.FailureWindows
import com.suarez.coder.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureWindowsTest {
    @Test fun searchWindowIsTakenFromTheReadFileAroundTheBlock() {
        val body = (1..100).joinToString("\n") { "line $it" } + "\n    val score = 1\n" + (102..140).joinToString("\n") { "line $it" }
        val run = listOf(
            ChatMessage.ToolResult("r", "github_read_file", "app/src/Hud.kt (140 satır)\n$body"),
            ChatMessage.Assistant(
                "app/src/Hud.kt\n<<<<<<< SEARCH\n    val score = 1\n=======\n    val score = 2\n>>>>>>> REPLACE\n",
            ),
        )
        val windows = FailureWindows.from(run, "skoru artir", upgradedToArchitect = false)
        val snippet = windows.firstOrNull { EvalGate.caseFromSnippet(it)?.optString("kind") == "search_replace" }
        val parsed = EvalGate.caseFromSnippet(snippet ?: "")
        assertNotNull(windows.joinToString("\n---\n"), parsed)
        assertTrue(parsed!!.optString("content").contains("val score = 1"))
        assertTrue(parsed.optString("content").lineSequence().count() <= 82)
        assertEquals("ok", parsed.optString("expect"))
        assertTrue(parsed.optString("search").contains("val score = 1"))
    }

    @Test fun ciWindowKeepsTheErrorLineAndDropsTheLicense() {
        val noise = (1..50).joinToString("\n") { "Downloading artifact $it" }
        val log = noise + "\ne: app/src/Game.kt:3 Unresolved reference 'Foo'\n" + (1..10).joinToString("\n") { "more $it" }
        val report = "Hatalar (Build):\n$log"
        val windows = FailureWindows.from(listOf(ChatMessage.ToolResult("c", "ci_wait", "\nHatalar ($report")), "istek", false)
        val parsed = windows.firstNotNullOf { EvalGate.caseFromSnippet(it) }
        assertEquals("log_filter", parsed.optString("kind"))
        assertTrue(parsed.optString("log").contains("Unresolved reference 'Foo'"))
        assertTrue(parsed.optString("log").lineSequence().count() <= 82)
    }
}
