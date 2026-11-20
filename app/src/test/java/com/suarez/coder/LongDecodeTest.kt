package com.suarez.coder

import com.suarez.coder.llm.GeminiClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LongDecodeTest {
    private fun hop(text: String, finish: String, input: Long, output: Long, thoughts: Long) = JSONObject(
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"$finish",
        "continuationToken":"tok"}],"usageMetadata":{"promptTokenCount":$input,"candidatesTokenCount":$output,
        "thoughtsTokenCount":$thoughts,"cachedContentTokenCount":10}}"""
    )

    @Test fun hopsAreJoinedIntoOneModelTurn() {
        val merged = GeminiClient.mergeHops(listOf(
            hop("", "CONTINUATION", 1000, 0, 32_000),          // a hop may be pure thinking
            hop("part one ", "CONTINUATION", 1000, 30_000, 2_000),
            hop("part two", "STOP", 1000, 500, 0),
        ))
        val cand = merged.getJSONArray("candidates").getJSONObject(0)
        assertEquals("STOP", cand.getString("finishReason"))
        val parts = cand.getJSONObject("content").getJSONArray("parts")
        assertEquals(3, parts.length())
        assertEquals("part one part two", (0 until parts.length()).joinToString("") { parts.getJSONObject(it).getString("text") })
        val u = merged.getJSONObject("usageMetadata")
        assertEquals(3000, u.getLong("promptTokenCount"))
        assertEquals(30_500, u.getLong("candidatesTokenCount"))
        assertEquals(34_000, u.getLong("thoughtsTokenCount"))
        assertEquals(30, u.getLong("cachedContentTokenCount"))
    }

    @Test fun singleHopIsUnchanged() {
        val h = hop("x", "STOP", 1, 1, 0)
        assertEquals(h, GeminiClient.mergeHops(listOf(h)))
    }
}
