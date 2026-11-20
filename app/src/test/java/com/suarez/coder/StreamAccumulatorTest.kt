package com.suarez.coder

import com.suarez.coder.llm.StreamAccumulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAccumulatorTest {
    private fun chunk(json: String) = JSONObject(json)

    @Test fun joinsStreamedPiecesLikeANonStreamingResponse() {
        val acc = StreamAccumulator()
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"**Planning the change**\nI need...","thought":true}]}}]}"""))
        assertEquals("Planning the change", acc.progress().lastThought)
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":" more thinking","thought":true}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"world","thoughtSignature":"sig1"}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"github_read_files","args":{"paths":["a"]}},"thoughtSignature":"sig2"}]}}]}"""))
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}],
            "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":50},"serviceTier":"standard"}"""))
        val r = acc.result()
        val cand = r.getJSONArray("candidates").getJSONObject(0)
        assertEquals("STOP", cand.getString("finishReason"))
        val parts = cand.getJSONObject("content").getJSONArray("parts")
        // thought (joined), text (joined, signature kept), function call (signature kept), trailing empty text
        assertEquals("**Planning the change**\nI need... more thinking", parts.getJSONObject(0).getString("text"))
        assertTrue(parts.getJSONObject(0).getBoolean("thought"))
        assertEquals("Hello world", parts.getJSONObject(1).getString("text"))
        assertEquals("sig1", parts.getJSONObject(1).getString("thoughtSignature"))
        assertEquals("sig2", parts.getJSONObject(2).getString("thoughtSignature"))
        assertEquals(100, r.getJSONObject("usageMetadata").getInt("promptTokenCount"))
        assertEquals(1, acc.progress().toolCalls)
        assertFalse(r.has("promptFeedback"))
    }

    @Test fun keepsContinuationToken() {
        val acc = StreamAccumulator()
        acc.add(chunk("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"CONTINUATION","continuationToken":"T1"}]}"""))
        val cand = acc.result().getJSONArray("candidates").getJSONObject(0)
        assertEquals("CONTINUATION", cand.getString("finishReason"))
        assertEquals("T1", cand.getString("continuationToken"))
    }
}
