package com.suarez.coder.llm

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class GeminiClient(private val apiKey: String, override val model: String) : LlmClient {
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    data class CacheInfo(val name: String, val expireAtMillis: Long, val tokens: Long)

    /** The cache would be below the model's minimum size. */
    class CacheTooSmallException(message: String) : Exception(message)

    companion object {
        /** Models that rejected our thinkingConfig; we stop sending it to them. */
        private val noThinkingConfig = HashSet<String>()

        fun isLegacyThinking(model: String) = Regex("gemini-(1|2)\\.").containsMatchIn(model.lowercase())
    }

    private fun checkKey() {
        if (apiKey.isBlank()) throw LlmException("Gemini API anahtarı girilmemiş. Ayarlar'dan ekleyin.")
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(500))

    private suspend fun send(method: String, path: String, body: JSONObject?): Http.Result {
        val rb = body?.toString()?.toRequestBody(Http.JSON)
        val req = Request.Builder().url("$base/$path").header("x-goog-api-key", apiKey).method(method, rb).build()
        return Http.execute(req)
    }

    /** Gemini 3+ takes thinkingLevel; 2.x models take a token budget. */
    fun thinkingConfig(level: String?): JSONObject? {
        if (level.isNullOrBlank() || model in noThinkingConfig) return null
        val m = model.lowercase()
        if (!isLegacyThinking(m)) return JSONObject().put("thinkingLevel", level)
        if (!m.contains("2.5")) return null // older models do not think
        val budget = when (level) {
            "low" -> if (m.contains("pro")) 1024 else 512
            "medium" -> 8192
            else -> -1 // dynamic
        }
        return JSONObject().put("thinkingBudget", budget)
    }

    fun systemJson(system: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))

    fun toolsJson(tools: List<ToolSpec>): JSONArray? {
        if (tools.isEmpty()) return null
        val decls = JSONArray()
        tools.forEach {
            val d = JSONObject().put("name", it.name).put("description", it.description)
            // Gemini rejects OBJECT schemas with empty properties, so omit parameters for no-arg tools.
            if ((it.parameters.optJSONObject("properties")?.length() ?: 0) > 0) d.put("parameters", it.parameters)
            decls.put(d)
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun requestBody(system: String, history: List<ChatMessage>, tools: List<ToolSpec>, options: CallOptions, withThinking: Boolean): JSONObject {
        val body = JSONObject().put("contents", buildContents(history))
        if (options.cachedContent != null) {
            body.put("cachedContent", options.cachedContent)
        } else {
            body.put("systemInstruction", systemJson(system))
            toolsJson(tools)?.let { body.put("tools", it) }
        }
        if (withThinking) thinkingConfig(options.thinkingLevel)?.let {
            body.put("generationConfig", JSONObject().put("thinkingConfig", it))
        }
        return body
    }

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>, options: CallOptions): LlmResponse {
        checkKey()
        var res = send("POST", "models/$model:generateContent", requestBody(system, history, tools, options, true))
        if (res.code == 400 && options.thinkingLevel != null && errorMessage(res.body).contains("think", ignoreCase = true)) {
            // This model does not accept our thinking setting: remember and retry without it.
            noThinkingConfig += model
            res = send("POST", "models/$model:generateContent", requestBody(system, history, tools, options, false))
        }
        if (options.cachedContent != null && res.code in setOf(400, 403, 404) &&
            errorMessage(res.body).contains("cach", ignoreCase = true)
        ) throw CacheRejectedException(errorMessage(res.body))
        if (res.code !in 200..299) throw LlmException("Gemini hatası (${res.code}): ${errorMessage(res.body)}")
        val json = JSONObject(res.body)
        return LlmResponse(parse(json), parseUsage(json.optJSONObject("usageMetadata")))
    }

    private fun parseUsage(u: JSONObject?): Usage {
        if (u == null) return Usage()
        return Usage(
            input = u.optLong("promptTokenCount") + u.optLong("toolUsePromptTokenCount"),
            output = u.optLong("candidatesTokenCount"),
            cached = u.optLong("cachedContentTokenCount"),
            thoughts = u.optLong("thoughtsTokenCount"),
        )
    }

    override suspend fun countTokens(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): Long? {
        checkKey()
        val inner = JSONObject().put("model", "models/$model").put("contents", buildContents(history))
            .put("systemInstruction", systemJson(system))
        toolsJson(tools)?.let { inner.put("tools", it) }
        val res = send("POST", "models/$model:countTokens", JSONObject().put("generateContentRequest", inner))
        if (res.code !in 200..299) throw LlmException("countTokens hatası (${res.code}): ${errorMessage(res.body)}")
        return JSONObject(res.body).optLong("totalTokens")
    }

    // ---- Explicit context caching ----

    suspend fun createCache(system: String, tools: List<ToolSpec>, ttlSeconds: Long): CacheInfo {
        checkKey()
        val body = JSONObject()
            .put("model", "models/$model")
            .put("displayName", "coder-${UUID.randomUUID().toString().take(6)}")
            .put("systemInstruction", systemJson(system))
            .put("ttl", "${ttlSeconds}s")
        toolsJson(tools)?.let { body.put("tools", it) }
        val res = send("POST", "cachedContents", body)
        if (res.code !in 200..299) {
            val msg = errorMessage(res.body)
            if (res.code == 400 && (msg.contains("too small", true) || msg.contains("min", true) && msg.contains("token", true))) {
                throw CacheTooSmallException(msg)
            }
            throw LlmException("Önbellek oluşturulamadı (${res.code}): $msg")
        }
        return parseCache(JSONObject(res.body))
    }

    suspend fun extendCache(name: String, ttlSeconds: Long): CacheInfo {
        val res = send("PATCH", "$name?updateMask=ttl", JSONObject().put("ttl", "${ttlSeconds}s"))
        if (res.code !in 200..299) throw LlmException("Önbellek uzatılamadı (${res.code}): ${errorMessage(res.body)}")
        return parseCache(JSONObject(res.body))
    }

    suspend fun deleteCache(name: String) {
        runCatching { send("DELETE", name, null) }
    }

    private fun parseCache(o: JSONObject): CacheInfo {
        val expire = runCatching { Instant.parse(o.getString("expireTime")).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 60_000)
        val tokens = o.optJSONObject("usageMetadata")?.optLong("totalTokenCount") ?: 0L
        return CacheInfo(o.getString("name"), expire, tokens)
    }

    // ---- Message conversion ----

    private fun buildContents(history: List<ChatMessage>): JSONArray {
        val contents = JSONArray()
        var pendingResponses: JSONArray? = null
        fun flush() {
            pendingResponses?.let { contents.put(JSONObject().put("role", "user").put("parts", it)) }
            pendingResponses = null
        }
        for (m in history) {
            when (m) {
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
                else -> Unit
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
            text.append("(Model boş yanıt döndü: ${cand.optString("finishReason")})")
        }
        return ChatMessage.Assistant(text.toString(), calls, thoughts.toString().ifBlank { null }, textSig, model)
    }

    override suspend fun listModels(): List<String> {
        checkKey()
        val res = send("GET", "models?pageSize=200", null)
        if (res.code !in 200..299) throw LlmException("Gemini hatası (${res.code}): ${errorMessage(res.body)}")
        val arr = JSONObject(res.body).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m ->
                val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") && !it.contains("tts") && !it.contains("image") && !it.contains("embedding") }
            .sortedDescending()
    }
}
