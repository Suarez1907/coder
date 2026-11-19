package com.suarez.coder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Client for DeepSeek (and any other OpenAI-compatible chat/completions API). */
class OpenAiCompatClient(
    private val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
) : LlmClient {

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>, options: CallOptions): LlmResponse {
        if (apiKey.isBlank()) throw LlmException("$name API anahtarı girilmemiş. Ayarlar'dan ekleyin.")
        val body = JSONObject()
            .put("model", model)
            .put("messages", buildMessages(system, history))
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
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val res = Http.execute(req)
        if (res.code !in 200..299) throw LlmException("$name hatası (${res.code}): ${errorMessage(res.body)}")
        val json = JSONObject(res.body)
        val u = json.optJSONObject("usage")
        // prompt_tokens includes cache hits; completion_tokens already includes reasoning tokens.
        val usage = if (u == null) Usage() else Usage(
            input = u.optLong("prompt_tokens"),
            output = u.optLong("completion_tokens"),
            cached = u.optLong("prompt_cache_hit_tokens", u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L),
        )
        return LlmResponse(parse(json), usage)
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(500))

    private fun buildMessages(system: String, history: List<ChatMessage>): JSONArray {
        val out = JSONArray()
        out.put(JSONObject().put("role", "system").put("content", system))
        val lastUser = history.indexOfLast { it is ChatMessage.User }
        history.forEachIndexed { index, m ->
            when (m) {
                is ChatMessage.User -> out.put(JSONObject().put("role", "user").put("content", m.text))
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
        val reasoning = if (msg.isNull("reasoning_content")) null else msg.optString("reasoning_content").ifBlank { null }
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

    override suspend fun listModels(): List<String> {
        if (apiKey.isBlank()) throw LlmException("Önce $name API anahtarını girin.")
        val req = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").get().build()
        val res = Http.execute(req)
        if (res.code !in 200..299) throw LlmException("$name hatası (${res.code}): ${errorMessage(res.body)}")
        val arr = JSONObject(res.body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
    }
}
