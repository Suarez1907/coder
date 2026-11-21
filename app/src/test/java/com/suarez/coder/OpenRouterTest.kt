package com.suarez.coder

import com.suarez.coder.llm.CallOptions
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.llm.OpenRouterCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenRouterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val modelsJson = """
        {"data":[
          {"id":"anthropic/claude-sonnet-4.5","pricing":{"prompt":"0.000003","completion":"0.000015","input_cache_read":"0.0000003"},
           "architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools","max_tokens"]},
          {"id":"qwen/qwen3-coder","pricing":{"prompt":"0.0000002","completion":"0.0000008"},
           "architecture":{"input_modalities":["text"]},"supported_parameters":["tools"]},
          {"id":"some/no-tools","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["max_tokens"]}
        ]}
    """.trimIndent()

    @Test fun parsesPricesPerMillionVisionAndTools() {
        val list = OpenRouterCatalog.parse(modelsJson)
        assertEquals(3, list.size)
        val claude = list[0]
        assertEquals(3.0, claude.price.input, 1e-9)
        assertEquals(15.0, claude.price.output, 1e-9)
        assertEquals(0.3, claude.price.cachedInput, 1e-9)
        assertTrue(claude.vision && claude.tools)
        val qwen = list[1]
        assertEquals(0.2, qwen.price.cachedInput, 1e-9) // no cache price → full input price
        assertFalse(qwen.vision)
        assertFalse(list[2].tools)
    }

    @Test fun metaRoundTrip() {
        val meta = OpenRouterCatalog.fromMeta(OpenRouterCatalog.toMeta(OpenRouterCatalog.parse(modelsJson)))
        assertEquals(15.0, meta.getValue("anthropic/claude-sonnet-4.5").price.output, 1e-9)
        assertTrue(meta.getValue("anthropic/claude-sonnet-4.5").vision)
        assertTrue(OpenRouterCatalog.fromMeta("").isEmpty())
    }

    @Test fun anthropicGetsCacheBreakpointsOnSystemStableAndLatestUser() {
        val client = OpenAiCompatClient("OpenRouter", "http://x", "k", "anthropic/claude-sonnet-4.5", cacheControl = true)
        val history = listOf(
            ChatMessage.User("STABLE CONTEXT"), ChatMessage.Assistant("ok"),
            ChatMessage.User("ilk görev"), ChatMessage.Assistant("tamam"),
            ChatMessage.User("ikinci görev"),
        )
        val msgs = client.messagesForTest("SYS", history)
        fun hasBreakpoint(i: Int) = (msgs.getJSONObject(i).opt("content") as? JSONArray)
            ?.getJSONObject(0)?.has("cache_control") == true
        assertTrue(hasBreakpoint(0)) // system
        assertTrue(hasBreakpoint(1)) // stable context
        assertFalse(hasBreakpoint(3)) // older user turn stays plain text
        assertEquals("ilk görev", msgs.getJSONObject(3).getString("content"))
        assertTrue(hasBreakpoint(5)) // latest user turn
    }

    @Test fun plainModelsKeepStringContent() {
        val client = OpenAiCompatClient("DeepSeek", "http://x", "k", "deepseek-chat")
        val msgs = client.messagesForTest("SYS", listOf(ChatMessage.User("merhaba")))
        assertEquals("SYS", msgs.getJSONObject(0).getString("content"))
        assertEquals("merhaba", msgs.getJSONObject(1).getString("content"))
    }

    @Test fun visionModelsGetImageParts() {
        val img = File(tmp.root, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val vision = OpenAiCompatClient("OpenRouter", "http://x", "k", "m", vision = true)
            .messagesForTest("S", listOf(ChatMessage.User("bu ne?", listOf(img.path))))
        val parts = vision.getJSONObject(1).getJSONArray("content")
        assertEquals("bu ne?", parts.getJSONObject(0).getString("text"))
        assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        val blind = OpenAiCompatClient("DeepSeek", "http://x", "k", "m")
            .messagesForTest("S", listOf(ChatMessage.User("bu ne?", listOf(img.path))))
        assertTrue(blind.getJSONObject(1).getString("content").contains("göremiyor"))
    }

    @Test fun sendsAuthAndTitleHeadersAndReadsCachedUsageAndReasoning() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", "selam").put("reasoning", "düşündüm"))))
            .put("usage", JSONObject().put("prompt_tokens", 1000).put("completion_tokens", 20)
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", 800)))
            .toString()))
        server.start()
        try {
            val client = OpenAiCompatClient("OpenRouter", server.url("/api/v1").toString().trimEnd('/'), "sk-or-test", "qwen/qwen3-coder",
                extraHeaders = mapOf("X-Title" to "Coder"))
            val r = client.complete("S", listOf(ChatMessage.User("hi")), emptyList(), CallOptions())
            val req = server.takeRequest()
            assertEquals("/api/v1/chat/completions", req.path)
            assertEquals("Bearer sk-or-test", req.getHeader("Authorization"))
            assertEquals("Coder", req.getHeader("X-Title"))
            assertEquals("qwen/qwen3-coder", JSONObject(req.body.readUtf8()).getString("model"))
            assertEquals("selam", r.message.text)
            assertEquals("düşündüm", r.message.reasoning)
            assertEquals(800L, r.usage.cached)
        } finally { server.shutdown() }
    }
}
