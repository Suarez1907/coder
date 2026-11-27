package com.suarez.coder.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String, override val model: String) : LlmClient {
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    data class CacheInfo(val name: String, val expireAtMillis: Long, val tokens: Long)

    /** The cache would be below the model's minimum size. */
    class CacheTooSmallException(message: String) : Exception(message)

    /** Flex capacity is full (503/429 after retries) or Flex is not offered for this model. */
    class FlexUnavailableException(message: String) : Exception(message)

    /** No data arrived for the stall timeout while streaming. */
    class StallException(message: String) : Exception(message)

    companion object {
        /** Models that rejected our thinkingConfig; we stop sending it to them. */
        private val noThinkingConfig = HashSet<String>()

        /** Flex requests can wait in a queue for minutes (Google recommends 10+ minute client timeouts). */
        private val flexClient: OkHttpClient = Http.client.newBuilder()
            .readTimeout(15, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).callTimeout(16, TimeUnit.MINUTES).build()

        private val FLEX_BACKOFF_MS = listOf(10_000L, 30_000L)

        private val stallClients = HashMap<Long, OkHttpClient>()

        /** A client whose read timeout is the stall timeout (time without any received bytes). */
        @Synchronized
        private fun clientFor(stallMs: Long): OkHttpClient = stallClients.getOrPut(stallMs) {
            Http.client.newBuilder().readTimeout(stallMs, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        }

        /** Per-request output cap of non-streaming REST calls on gemini-4-carbon/argon. */
        const val SINGLE_HOP_CAP_SMALL = 32_768
        const val MAX_HOPS = 40

        /**
         * Joins long-decode hops into one response: all parts in order (one model turn), usage summed,
         * finish reason and service tier of the last hop.
         */
        fun mergeHops(hops: List<JSONObject>): JSONObject {
            if (hops.size == 1) return hops[0]
            val parts = JSONArray()
            val usage = JSONObject()
            for (h in hops) {
                h.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.let { p ->
                    for (i in 0 until p.length()) parts.put(p.get(i))
                }
                h.optJSONObject("usageMetadata")?.let { u ->
                    u.keys().forEach { k -> if (u.opt(k) is Number) usage.put(k, usage.optLong(k) + u.optLong(k)) }
                }
            }
            val last = hops.last()
            val lastCand = last.optJSONArray("candidates")?.optJSONObject(0) ?: JSONObject()
            val cand = JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))
                .put("finishReason", lastCand.optString("finishReason"))
            val out = JSONObject().put("candidates", JSONArray().put(cand)).put("usageMetadata", usage)
            last.optString("serviceTier").takeIf { it.isNotBlank() }?.let { out.put("serviceTier", it) }
            return out
        }

        fun isLegacyThinking(model: String) = Regex("gemini-(1|2)\\.").containsMatchIn(model.lowercase())
    }

    private fun checkKey() {
        if (apiKey.isBlank()) throw LlmException("Gemini API anahtarı girilmemiş. Ayarlar'dan ekleyin.")
    }

    private fun errorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body.take(500))

    private suspend fun send(method: String, path: String, body: JSONObject?, client: OkHttpClient = Http.client): Http.Result {
        val rb = body?.toString()?.toRequestBody(Http.JSON)
        val req = Request.Builder().url("$base/$path").header("x-goog-api-key", apiKey).method(method, rb).build()
        return Http.execute(req, client)
    }

    /**
     * Sends the request with streamGenerateContent (SSE), so progress is visible and a stuck request is told apart
     * from a slow one. On the Flex tier retries 503/429 with backoff and never silently upgrades.
     */
    private suspend fun generate(
        body: JSONObject,
        flex: Boolean,
        stallMs: Long?,
        onProgress: ((StreamProgress) -> Unit)?,
        watchdog: MidStreamWatchdog?,
    ): Http.Result {
        val client = if (flex) flexClient else stallMs?.let { clientFor(it) } ?: Http.client
        if (!flex) return stream(body, client, onProgress, watchdog)
        var attempt = 0
        while (true) {
            val res = stream(body, client, onProgress, watchdog)
            if (res.code == 400 && Regex("service.?tier|flex", RegexOption.IGNORE_CASE).containsMatchIn(errorMessage(res.body))) {
                throw FlexUnavailableException("Bu model Flex'i desteklemiyor: ${errorMessage(res.body).take(150)}")
            }
            if (res.code != 503 && res.code != 429) return res
            if (attempt >= FLEX_BACKOFF_MS.size) throw FlexUnavailableException("Flex kapasitesi dolu (HTTP ${res.code}).")
            delay(FLEX_BACKOFF_MS[attempt++])
        }
    }

    /** Gemini 3+ takes thinkingLevel; 2.x models take a token budget. */
    fun thinkingConfig(level: String?, minimal: Boolean = false): JSONObject? {
        if (model in noThinkingConfig) return null
        val m = model.lowercase()
        if (minimal) {
            return when {
                m.startsWith("gemini-2.5-flash") -> JSONObject().put("thinkingBudget", 0)
                m.startsWith("gemini-2.5-pro") -> JSONObject().put("thinkingBudget", 128)
                com.suarez.coder.cost.Pricing.canNearlyDisableThinking(m) -> JSONObject().put("thinkingLevel", "minimal")
                else -> JSONObject().put("thinkingLevel", "low")
            }
        }
        if (level.isNullOrBlank()) return null
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
        val gen = JSONObject()
        if (withThinking) thinkingConfig(options.thinkingLevel, options.minimalThinking)?.let {
            // Thought summaries stream while the model thinks: visible progress instead of minutes of silence.
            if (options.onProgress != null && !options.minimalThinking) it.put("includeThoughts", true)
            gen.put("thinkingConfig", it)
        }
        options.maxOutputTokens?.let { gen.put("maxOutputTokens", it) }
        options.temperature?.let { gen.put("temperature", it) }
        if (gen.length() > 0) body.put("generationConfig", gen)
        options.serviceTier?.let { body.put("serviceTier", it) }
        return body
    }

    override suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>, options: CallOptions): LlmResponse {
        checkKey()
        val flex = options.serviceTier == "flex"
        var opts = options
        var withThinking = true
        val hops = ArrayList<JSONObject>()
        var token: String? = null
        // Long decode: while the model reports CONTINUATION, resend the same request with the latest token and join the hops.
        while (true) {
            val body = requestBody(system, history, tools, opts, withThinking)
            token?.let { body.put("continuationToken", it) }
            var res = hop(body, flex, opts)
            if (hops.isEmpty() && res.code == 400) {
                val msg = errorMessage(res.body)
                if ((opts.thinkingLevel != null || opts.minimalThinking) && msg.contains("think", ignoreCase = true)) {
                    // This model does not accept our thinking setting: remember and retry without it.
                    noThinkingConfig += model
                    withThinking = false
                    res = hop(requestBody(system, history, tools, opts, false), flex, opts)
                } else if ((opts.maxOutputTokens ?: 0) > SINGLE_HOP_CAP_SMALL && Regex("max.?output|output.?tokens|continuation", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                    // Some models reject a total above their single-request cap on the first hop: fall back to that cap.
                    opts = opts.copy(maxOutputTokens = SINGLE_HOP_CAP_SMALL)
                    res = hop(requestBody(system, history, tools, opts, withThinking), flex, opts)
                }
            }
            if (opts.cachedContent != null && res.code in setOf(400, 403, 404) &&
                errorMessage(res.body).contains("cach", ignoreCase = true)
            ) throw CacheRejectedException(errorMessage(res.body))
            if (res.code !in 200..299) {
                val partial = if (hops.isNotEmpty()) " (${hops.size} parça alındıktan sonra)" else ""
                throw LlmException("Gemini hatası (${res.code})$partial: ${errorMessage(res.body)}")
            }
            val json = JSONObject(res.body)
            hops += json
            val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            val next = cand?.optString("continuationToken")?.takeIf { it.isNotBlank() }
            if (cand?.optString("finishReason") != "CONTINUATION" || next == null || hops.size >= MAX_HOPS) break
            token = next
            opts.onHop?.invoke(hops.size + 1)
        }
        val merged = mergeHops(hops)
        val finish = merged.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason") ?: ""
        val tier = merged.optString("serviceTier").takeIf { it.isNotBlank() }?.lowercase()
        return LlmResponse(parse(merged), parseUsage(merged.optJSONObject("usageMetadata")),
            truncated = finish == "MAX_TOKENS" || finish == "CONTINUATION", serviceTier = tier)
    }

    private suspend fun hop(body: JSONObject, flex: Boolean, o: CallOptions): Http.Result {
        val run: suspend () -> Http.Result = { generate(body, flex, o.stallTimeoutMs, o.onProgress, o.watchdog) }
        return if (o.hopTimeoutMs == null) run() else kotlinx.coroutines.withTimeout(o.hopTimeoutMs) { run() }
    }

    /** One SSE request; returns the joined response as JSON text, or the error body for non-2xx answers. */
    private suspend fun stream(
        body: JSONObject,
        client: OkHttpClient,
        onProgress: ((StreamProgress) -> Unit)?,
        watchdog: MidStreamWatchdog?,
    ): Http.Result =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val req = Request.Builder().url("$base/models/$model:streamGenerateContent?alt=sse")
                    .header("x-goog-api-key", apiKey).post(body.toString().toRequestBody(Http.JSON)).build()
                val call = client.newCall(req)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(stallOr(e, client))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { r ->
                            try {
                                if (!r.isSuccessful) {
                                    val text = r.body?.string() ?: ""
                                    if (cont.isActive) cont.resume(Http.Result(r.code, text, r.header("Content-Type")))
                                    return
                                }
                                val acc = StreamAccumulator()
                                val src = r.body!!.source()
                                var lastReport = 0L
                                while (true) {
                                    val line = src.readUtf8Line() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val data = line.substring(5).trim()
                                    if (data.isEmpty() || data == "[DONE]") continue
                                    val delta = acc.add(JSONObject(data))
                                    val verdict = watchdog?.observe(delta, acc.progress().toolCalls > 0)
                                    if (verdict != null && verdict.cut) {
                                        call.cancel()
                                        val usage = parseUsage(acc.result().optJSONObject("usageMetadata"))
                                        throw WatchdogCut(verdict.reason!!, verdict.consumedChars, watchdog.captured(), usage)
                                    }
                                    val now = System.currentTimeMillis()
                                    if (onProgress != null && now - lastReport > 1_000) { lastReport = now; onProgress(acc.progress()) }
                                }
                                if (cont.isActive) cont.resume(Http.Result(200, acc.result().toString(), "application/json"))
                            } catch (e: WatchdogCut) {
                                if (cont.isActive) cont.resumeWithException(e)
                            } catch (e: IOException) {
                                if (cont.isActive) cont.resumeWithException(stallOr(e, client))
                            } catch (e: org.json.JSONException) {
                                if (cont.isActive) cont.resumeWithException(LlmException("Gemini akışı okunamadı: ${e.message}"))
                            }
                        }
                    }
                })
            }
        }

    private fun stallOr(e: IOException, client: OkHttpClient): Exception =
        if (e is SocketTimeoutException) StallException("${client.readTimeoutMillis / 1000} sn boyunca hiç veri gelmedi") else e

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

    /** [contents] are leading conversation messages (e.g. the stable project context) cached together with the system prompt. */
    suspend fun createCache(system: String, tools: List<ToolSpec>, ttlSeconds: Long, contents: List<ChatMessage> = emptyList()): CacheInfo {
        checkKey()
        val body = JSONObject()
            .put("model", "models/$model")
            .put("displayName", "coder-${UUID.randomUUID().toString().take(6)}")
            .put("systemInstruction", systemJson(system))
            .put("ttl", "${ttlSeconds}s")
        toolsJson(tools)?.let { body.put("tools", it) }
        if (contents.isNotEmpty()) body.put("contents", buildContents(contents))
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

    /** Reads a prepared image file as (mimeType, base64); null when it no longer exists. */
    private fun imagePart(path: String): Pair<String, String>? {
        val f = java.io.File(path)
        if (!f.isFile) return null
        val mime = if (path.endsWith(".png", true)) "image/png" else "image/jpeg"
        return mime to java.util.Base64.getEncoder().encodeToString(f.readBytes())
    }

    /** Visible for tests. */
    internal fun contentsForTest(history: List<ChatMessage>) = buildContents(history)

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
                    val parts = JSONArray().put(JSONObject().put("text", m.text))
                    m.images.forEach { path ->
                        imagePart(path)?.let { (mime, data) ->
                            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data)))
                        }
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", parts))
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
