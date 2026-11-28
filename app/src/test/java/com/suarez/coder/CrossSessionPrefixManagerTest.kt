package com.suarez.coder

import com.suarez.coder.context.CrossSessionPrefixManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossSessionPrefixManagerTest {
    @Test
    fun producesIdenticalPrefixHashAcrossSessionsRegardlessOfToolMapOrder() {
        val s1 = CrossSessionPrefixManager.buildStaticPrefix(
            repo = "Suarez1907/coder",
            model = "gemini-2.5-pro",
            treeSha = "abc1234",
            systemPrompt = "SYSTEM",
            toolSchemas = linkedMapOf("b_tool" to "{}", "a_tool" to "{}"),
            stableRepoSummary = "MAP",
        )
        val s2 = CrossSessionPrefixManager.buildStaticPrefix(
            repo = "Suarez1907/coder",
            model = "gemini-2.5-pro",
            treeSha = "abc1234",
            systemPrompt = "SYSTEM",
            toolSchemas = linkedMapOf("a_tool" to "{}", "b_tool" to "{}"),
            stableRepoSummary = "MAP",
        )
        assertEquals(s1.prefixHash, s2.prefixHash)
        assertEquals(s1.reuseKey, s2.reuseKey)
        val composed = CrossSessionPrefixManager.composeWithDynamicTail(s1, "Bugün 18:00 görevi")
        assertTrue(composed.startsWith(s1.text))
    }
}
