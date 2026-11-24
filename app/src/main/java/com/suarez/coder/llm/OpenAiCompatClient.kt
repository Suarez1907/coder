package com.suarez.coder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Client for DeepSeek, OpenRouter and any other OpenAI-compatible chat/completions API. */
class OpenAiCompatClient(
    private val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    /** Send screenshots as image_url parts (only for models that accept images). */
    private val vision: Boolean = false,
    /** Mark the stable prefix with cache_control breakpoints (Anthropic models via OpenRouter). */
    private val cacheControl: Boolean = false,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** OpenRouter: ask for usage.cost (real USD) in every response. */
    private val openRouter: Boolean = false,
    /** The model accepts reasoning.effort (known from OpenRouter's model list). */
    private val reasoning: Boolean = false,
    /** The model accepts response_format (JSON mode). */
    private val json: Boolean = false,
    /** Waits between retries of temporary errors; visible for tests. */
    private val backoffMs: List<Long> = BACKOFF_MS,
) : LlmClient {

    companion object {
        /** Temporary upstream/proxy failures: retried, never treated as a model mistake. */
        val TRANSIENT_CODES = setOf(408, 429, 500, 502, 503, 504, 520, 522, 524, 529)
        /** Retry waits; tests shorten them. */
        @Volatile var BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)
        private const val MAX_RETRY_AFTER_MS = 30_000L

        fun isTransient(code: Int) = code in TRANSIENT_CODES

        /** Retry-After in seconds (dates are ignored); capped so a run never stalls for long. */
        fun retryAfterMs(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).coerceAtMost(MAX_RETRY_AFTER_MS) }
    }

    /**
     * Sends [req]; 408/429/5xx/529 and dropped connections are retried after 2s → 5s → 10s (or Retry-After).
     * These are network/provider problems, so they never reach the edit ladder as a failed attempt.
     */
    private suspend fun send(req: Request): Http.Result {
        var attempt = 0
        while (true) {
            val res = try {
                Http.execute(req)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                if (attempt >= backoffMs.size) throw LlmException("$name bağlantı hatası (${backoffMs.size} yeniden denemeden sonra): ${e.message}")
                kotlinx.coroutines.delay(backoffMs[attempt++])
                continue
            }
            if (!isTransient(res.code) || attempt >= backoffMs.size) return res
            kotlinx.coroutines.delay(retryAfterMs(res.retryAfter) ?: backoffMs[attempt])
            attempt++
        }
    }

    private fun Request.Builder.withHeaders(): Request.Builder = apply {
        header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> header(k, v) }
    }

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>, options: CallOptions): LlmResponse {
        if (apiKey.isBlank()) throw LlmException("$name API anahtarı girilmemiş. Ayarlar'dan ekleyin.")
        val body = JSONObject()
            .put("model", model)
            .put("messages", buildMessages(system, history))
        options.maxOutputTokens?.let { body.put("max_tokens", it) }
        if (openRouter) body.put("usage", JSONObject().put("include", true))
        if (json && options.jsonMode && tools.isEmpty()) body.put("response_format", JSONObject().put("type", "json_object"))
        if (reasoning) OpenRouterCatalog.effortFor(options.thinkingLevel, options.minimalThinking)?.let {
            body.put("reasoning", JSONObject().put("effort", it))
        }
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            tools.forEach {
                arr.put(JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters)))
            }
            body.put("tools", arr)
        }
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .withHeaders()
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        var res = send(req)
        if (res.code == 400 && options.maxOutputTokens != null && errorMessage(res.body).contains("max_tokens", ignoreCase = true)) {
            // The model's own output limit is lower than the configured one: let the API use its maximum.
            body.remove("max_tokens")
            res = send(req.newBuilder().post(body.toString().toRequestBody(Http.JSON)).build())
        }
        if (res.code == 400 && body.has("reasoning") && errorMessage(res.body).contains("reasoning", ignoreCase = true)) {
            // Listed as supporting reasoning but this provider rejects it: answer without it.
            body.remove("reasoning")
            res = send(req.newBuilder().post(body.toString().toRequestBody(Http.JSON)).build())
        }
        if (res.code == 400 && body.has("response_format") && errorMessage(res.body).contains("response_format", ignoreCase = true)) {
            body.remove("response_format")
            res = send(req.newBuilder().post(body.toString().toRequestBody(Http.JSON)).build())
        }
        if (res.code !in 200..299) {
            val retried = if (isTransient(res.code)) " (${backoffMs.size} yeniden denemeden sonra; geçici sağlayıcı hatası)" else ""
            throw LlmException("$name hatası (${res.code})$retried: ${errorMessage(res.body)}")
        }
        val json = JSONObject(res.body)
        val u = json.optJSONObject("usage")
        // prompt_tokens includes cache hits; completion_tokens already includes reasoning tokens.
        val usage = if (u == null) Usage() else Usage(
            input = u.optLong("prompt_tokens"),
            output = u.optLong("completion_tokens"),
            cached = u.optLong("prompt_cache_hit_tokens", u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L),
        )
        val finish = json.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason") ?: ""
        val cost = u?.takeIf { it.has("cost") && !it.isNull("cost") }?.optDouble("cost")?.takeIf { !it.isNaN() && it >= 0 }
        return LlmResponse(parse(json), usage, truncated = finish == "length", costUsd = cost)
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(500))

    private fun cachedText(text: String) = JSONArray().put(
        JSONObject().put("type", "text").put("text", text).put("cache_control", JSONObject().put("type", "ephemeral")))

    private fun userContent(m: ChatMessage.User, breakpoint: Boolean): Any {
        if (m.images.isNotEmpty() && vision) {
            val parts = JSONArray()
            val text = JSONObject().put("type", "text").put("text", m.text.ifBlank { "(ekran görüntüsü)" })
            if (breakpoint) text.put("cache_control", JSONObject().put("type", "ephemeral"))
            parts.put(text)
            m.images.forEach { path ->
                val f = java.io.File(path)
                if (!f.isFile) return@forEach
                val mime = if (path.endsWith(".png", true)) "image/png" else "image/jpeg"
                val data = java.util.Base64.getEncoder().encodeToString(f.readBytes())
                parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$data")))
            }
            return parts
        }
        val text = if (m.images.isEmpty()) m.text
            else m.text + "\n[Not: kullanıcı ${m.images.size} ekran görüntüsü ekledi; bu model görselleri göremiyor.]"
        return if (breakpoint) cachedText(text) else text
    }

    /** Visible for tests. */
    internal fun messagesForTest(system: String, history: List<ChatMessage>) = buildMessages(system, history)

    private fun buildMessages(system: String, history: List<ChatMessage>): JSONArray {
        val out = JSONArray()
        out.put(JSONObject().put("role", "system").put("content", if (cacheControl) cachedText(system) else system))
        val lastUser = history.indexOfLast { it is ChatMessage.User }
        val firstUser = history.indexOfFirst { it is ChatMessage.User }
        history.forEachIndexed { index, m ->
            when (m) {
                // Breakpoints (max 4): system, the stable context (first user turn) and the latest user turn.
                is ChatMessage.User -> out.put(JSONObject().put("role", "user")
                    .put("content", userContent(m, cacheControl && (index == firstUser || index == lastUser))))
                is ChatMessage.Assistant -> {
                    val o = JSONObject().put("role", "assistant").put("content", m.text)
                    // Thinking models need their reasoning back while the current tool-calling turn is in progress.
                    if (index > lastUser && m.reasoning != null && m.toolCalls.isNotEmpty()) o.put("reasoning_content", m.reasoning)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        m.toolCalls.forEach { c ->
                            calls.put(JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString())))
                        }
                        o.put("tool_calls", calls)
                    }
                    out.put(o)
                }
                is ChatMessage.ToolResult -> out.put(
                    JSONObject().put("role", "tool").put("tool_call_id", m.callId)
                        .put("content", if (m.isError) "ERROR: ${m.content}" else m.content)
                )
                else -> Unit
            }
        }
        return out
    }

    private fun parse(json: JSONObject): ChatMessage.Assistant {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw LlmException("$name boş yanıt döndü.")
        val msg = choices.getJSONObject(0).getJSONObject("message")
        val text = if (msg.isNull("content")) "" else msg.optString("content")
        val reasoning = listOf("reasoning_content", "reasoning")
            .firstNotNullOfOrNull { k -> if (msg.isNull(k)) null else msg.optString(k).ifBlank { null } }
        val calls = ArrayList<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val rawArgs = fn.optString("arguments").ifBlank { "{}" }
                val args = runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject().put("_raw", rawArgs) }
                calls += ToolCall(c.optString("id").ifBlank { "call_" + UUID.randomUUID().toString().take(8) }, fn.getString("name"), args)
            }
        }
        return ChatMessage.Assistant(
            text = if (text.isEmpty() && calls.isEmpty()) "(Model boş yanıt döndü)" else text,
            toolCalls = calls,
            reasoning = reasoning,
            model = model,
        )
    }

    /** Raw body of GET /models (OpenRouter puts prices and capabilities in it). */
    suspend fun modelsBody(): String {
        if (apiKey.isBlank()) throw LlmException("Önce $name API anahtarını girin.")
        val req = Request.Builder().url("$baseUrl/models").withHeaders().get().build()
        val res = send(req)
        if (res.code !in 200..299) throw LlmException("$name hatası (${res.code}): ${errorMessage(res.body)}")
        return res.body
    }

    override suspend fun listModels(): List<String> {
        val arr = JSONObject(modelsBody()).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
    }
}
