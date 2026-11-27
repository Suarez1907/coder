package com.suarez.coder

import com.suarez.coder.llm.CallOptions
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.JsonBodyWatch
import com.suarez.coder.llm.MidStreamWatchdog
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.llm.WatchdogCut
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonBodyWatchTest {
    private fun repeatingJson(): String {
        val line = "x".repeat(70)
        val content = "$line\n$line\n$line\n" + "Z".repeat(8_000)
        return JSONObject().put("choices", JSONArray().put(
            JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content).put("role", "assistant")),
        )).put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 400)).toString()
    }

    @Test fun cancelsMidJsonBodyWhenTheWatchdogCuts() {
        val raw = repeatingJson()
        val source = Buffer().writeUtf8(raw)
        var cancelled = false
        val dog = MidStreamWatchdog(proseGuard = true, diffToolAvailable = false)
        val result = JsonBodyWatch.read(source, dog, cancel = { cancelled = true }, chunkCodePoints = 32)
        assertTrue(cancelled)
        assertTrue(result.cut != null)
        assertEquals(MidStreamWatchdog.Reason.REPEAT, result.cut!!.reason)
        assertFalse(source.exhausted())
        assertTrue(result.raw.length < raw.length)
    }

    @Test fun aShortJsonBodyIsNotCut() {
        val raw = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", "selam")),
        )).toString()
        val source = Buffer().writeUtf8(raw)
        var cancelled = false
        val dog = MidStreamWatchdog(proseGuard = true, diffToolAvailable = true)
        val result = JsonBodyWatch.read(source, dog, cancel = { cancelled = true }, chunkCodePoints = 16)
        assertFalse(cancelled)
        assertEquals(null, result.cut)
        assertTrue(source.exhausted())
        assertEquals("selam", result.text)
    }

    @Test fun openAiClientCancelsAJsonCompletionAndThrowsWatchdogCut() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(repeatingJson()))
        server.start()
        try {
            val client = OpenAiCompatClient("OpenRouter", server.url("/v1").toString().trimEnd('/'), "k", "m", openRouter = true, backoffMs = emptyList())
            val dog = MidStreamWatchdog(proseGuard = true, diffToolAvailable = false)
            val error = runCatching {
                client.complete("S", listOf(ChatMessage.User("hi")), emptyList(), CallOptions(watchdog = dog))
            }.exceptionOrNull()
            assertTrue(error is WatchdogCut)
            assertEquals(MidStreamWatchdog.Reason.REPEAT, (error as WatchdogCut).reason)
        } finally {
            server.shutdown()
        }
    }

    @Test fun openAiClientKeepsANormalJsonCompletion() = runBlocking {
        val server = MockWebServer()
        val body = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", "tamam")),
        )).put("usage", JSONObject().put("prompt_tokens", 4).put("completion_tokens", 1)).toString()
        server.enqueue(MockResponse().setBody(body))
        server.start()
        try {
            val client = OpenAiCompatClient("OpenRouter", server.url("/v1").toString().trimEnd('/'), "k", "m", openRouter = true, backoffMs = emptyList())
            val dog = MidStreamWatchdog(proseGuard = true, diffToolAvailable = false)
            val reply = client.complete("S", listOf(ChatMessage.User("hi")), emptyList(), CallOptions(watchdog = dog))
            assertEquals("tamam", reply.message.text)
        } finally {
            server.shutdown()
        }
    }
}
