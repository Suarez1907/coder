package com.suarez.coder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class GeminiClient(private val apiKey: String, private val model: String) : LlmClient {
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage.Assistant {
        if (apiKey.isBlank()) throw LlmException("Gemini API anahtarı girilmemiş. Ayarlar'dan ekleyin.")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", buildContents(history))
        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            tools.forEach {
                val d = JSONObject().put("name", it.name).put("description", it.description)
                // Gemini rejects OBJECT schemas with empty properties, so omit parameters for no-arg tools.
                if ((it.parameters.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", it.parameters)
                decls.put(d)
            }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        val req = Request.Builder()
            .url("$base/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val res = Http.execute(req)
        if (res.code !in 200..299) throw LlmException("Gemini hatası (${res.code}): ${errorMessage(res.body)}")
        return parse(JSONObject(res.body))
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(500))

    private fun buildContents(history: List<ChatMessage>): JSONArray {
        val contents = JSONArray()
        var pendingResponses: JSONArray? = null
        fun flush() {
            pendingResponses?.let { contents.put(JSONObject().put("role", "user").put("parts", it)) }
            pendingResponses = null
        }
        for (m in history) {
            when (m) {
                is ChatMessage.Notice -> Unit
                is ChatMessage.User -> {
                    flush()
                    contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", m.text))))
                }
                is ChatMessage.Assistant -> {
                    flush()
                    val parts = JSONArray()
                    if (m.text.isNotEmpty()) {
                        val p = JSONObject().put("text", m.text)
                        m.textSignature?.let { p.put("thoughtSignature", it) }
                        parts.put(p)
                    }
                    m.toolCalls.forEach { c ->
                        val fc = JSONObject().put("name", c.name).put("args", c.args)
                        if (!c.id.startsWith("gen_")) fc.put("id", c.id)
                        val p = JSONObject().put("functionCall", fc)
                        c.signature?.let { p.put("thoughtSignature", it) }
                        parts.put(p)
                    }
                    if (parts.length() == 0) parts.put(JSONObject().put("text", " "))
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                is ChatMessage.ToolResult -> {
                    val fr = JSONObject().put("name", m.name)
                        .put("response", JSONObject().put(if (m.isError) "error" else "result", m.content))
                    if (!m.callId.startsWith("gen_")) fr.put("id", m.callId)
                    val arr = pendingResponses ?: JSONArray().also { pendingResponses = it }
                    arr.put(JSONObject().put("functionResponse", fr))
                }
            }
        }
        flush()
        return contents
    }

    private fun parse(json: JSONObject): ChatMessage.Assistant {
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason")
            throw LlmException("Gemini yanıt vermedi${if (!reason.isNullOrBlank()) " (engellendi: $reason)" else ""}.")
        }
        val cand = candidates.getJSONObject(0)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val thoughts = StringBuilder()
        var textSig: String? = null
        val calls = ArrayList<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val sig = if (p.has("thoughtSignature")) p.getString("thoughtSignature") else null
            when {
                p.has("functionCall") -> {
                    val fc = p.getJSONObject("functionCall")
                    val id = fc.optString("id").ifBlank { "gen_" + UUID.randomUUID().toString().take(8) }
                    calls += ToolCall(id, fc.getString("name"), fc.optJSONObject("args") ?: JSONObject(), sig)
                }
                p.optBoolean("thought") -> thoughts.append(p.optString("text"))
                p.has("text") -> {
                    text.append(p.getString("text"))
                    if (sig != null) textSig = sig
                }
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            val finish = cand.optString("finishReason")
            text.append("(Model boş yanıt döndü: $finish)")
        }
        return ChatMessage.Assistant(
            text = text.toString(),
            toolCalls = calls,
            reasoning = thoughts.toString().ifBlank { null },
            textSignature = textSig,
            model = model,
        )
    }

    override suspend fun listModels(): List<String> {
        if (apiKey.isBlank()) throw LlmException("Önce Gemini API anahtarını girin.")
        val req = Request.Builder().url("$base/models?pageSize=200").header("x-goog-api-key", apiKey).get().build()
        val res = Http.execute(req)
        if (res.code !in 200..299) throw LlmException("Gemini hatası (${res.code}): ${errorMessage(res.body)}")
        val arr = JSONObject(res.body).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") }
            .sortedDescending()
    }
}
