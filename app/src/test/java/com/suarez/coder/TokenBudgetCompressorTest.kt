package com.suarez.coder

import com.suarez.coder.context.TokenBudgetCompressor
import com.suarez.coder.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBudgetCompressorTest {

    @Test
    fun preservesCachePrefixAndFirstUserMessageExactlyWhileCompressingToolNoise() {
        val firstTask = ChatMessage.User("STABLE CONTEXT + ilk görev mesajı hiç değişmemeli.")
        val repeatedLogs = (1..25).joinToString("\n") { "WARNING: Gradle daemon idle tick" }
        val noisyTrace = buildString {
            append("java.lang.RuntimeException: Boom\n")
            append("\tat com.suarez.coder.agent.Agent.run(Agent.kt:120)\n")
            for (i in 1..15) {
                append("\tat org.gradle.internal.Worker$i.exec(Worker.java:$i)\n")
            }
            append("\tat com.suarez.coder.llm.OpenAiCompatClient.complete(OpenAiCompatClient.kt:80)\n")
        }
        val toolMsg = ChatMessage.ToolResult("c1", "ci_errors", repeatedLogs + "\n" + noisyTrace)

        val (compressed, stats) = TokenBudgetCompressor.compressWithStats(listOf(firstTask, toolMsg))
        assertEquals(firstTask, compressed[0])
        val outTool = compressed[1] as ChatMessage.ToolResult
        assertTrue(outTool.content.contains("[x25 tekrar]"))
        assertTrue(outTool.content.contains("com.suarez.coder.agent.Agent.run"))
        assertTrue(outTool.content.contains("com.suarez.coder.llm.OpenAiCompatClient.complete"))
        assertTrue(outTool.content.contains("çerçeve satırı gizlendi"))
        assertTrue(stats.savedChars > 400)
        assertEquals(1, stats.toolResultsCompressed)
    }

    @Test
    fun truncatesOversizedToolOutputDeterministically() {
        val big = "HEADER_START\n" + "a".repeat(12_000) + "\nFOOTER_END"
        val out = TokenBudgetCompressor.compressToolText(big, maxChars = 2_000)
        assertTrue(out.length <= 2_100)
        assertTrue(out.startsWith("HEADER_START"))
        assertTrue(out.endsWith("FOOTER_END"))
        assertTrue(out.contains("kırpıldı"))
    }
}
