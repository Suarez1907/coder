package com.suarez.coder.llm

import com.suarez.coder.data.Provider
import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    /** Gemini "thoughtSignature" that must be sent back unchanged. */
    val signature: String? = null,
)

/** Token usage of one API call. [input] includes [cached]; [output] excludes [thoughts]. */
data class Usage(val input: Long = 0, val output: Long = 0, val cached: Long = 0, val thoughts: Long = 0) {
    operator fun plus(o: Usage) = Usage(input + o.input, output + o.output, cached + o.cached, thoughts + o.thoughts)
}

sealed class ChatMessage {
    data class User(val text: String) : ChatMessage()

    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String? = null,
        val textSignature: String? = null,
        val model: String = "",
    ) : ChatMessage()

    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
    ) : ChatMessage()

    /** Shown in the chat only; never sent to the model. [detail] is expandable. */
    data class Notice(val text: String, val detail: String? = null, val isError: Boolean = false) : ChatMessage()

    /** Cost bookkeeping for one API call (or cache storage); never sent to the model. */
    data class UsageRecord(
        val model: String,
        val role: String,
        val usage: Usage,
        val extraCostUsd: Double = 0.0,
        val label: String = "",
        /** Price multiplier for the token cost, e.g. 0.5 for the Flex tier. */
        val discount: Double = 1.0,
    ) : ChatMessage()

    /** Everything model-visible before this marker is sent compacted; everything after it in full (append-only). */
    object CompactMark : ChatMessage()

    /** Extra context for one request (e.g. files picked by Decision 1); sent as part of the preceding user message. */
    data class ContextBlock(val text: String, val label: String) : ChatMessage()

    /** Smart-routing decision shown as an info bar; never sent to the model. */
    data class RouteInfo(val text: String, val detail: String?, val route: String, val canOverride: Boolean) : ChatMessage()

    /** Summary of every model-visible message before it. */
    data class Summary(val text: String) : ChatMessage()
}

data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class CallOptions(
    val thinkingLevel: String? = null,
    /** Hard cap on generated tokens (thinking included on Gemini). */
    val maxOutputTokens: Int? = null,
    /** Ask for the lowest thinking the model supports (classification calls). */
    val minimalThinking: Boolean = false,
    /** Gemini service tier, e.g. "flex" (50% cheaper, slower, best-effort capacity). */
    val serviceTier: String? = null,
    /** Gemini cachedContents/... name; when set, system prompt and tools come from the cache. */
    val cachedContent: String? = null,
)

data class LlmResponse(
    val message: ChatMessage.Assistant,
    val usage: Usage,
    val truncated: Boolean = false,
    /** Tier that actually served the request, as reported by the API. */
    val serviceTier: String? = null,
)

interface LlmClient {
    val model: String
    suspend fun complete(
        system: String,
        history: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: CallOptions = CallOptions(),
    ): LlmResponse

    /** Exact input token count when the provider offers it, otherwise null. */
    suspend fun countTokens(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): Long? = null

    suspend fun listModels(): List<String>
}

class LlmException(message: String) : Exception(message)

/** A cached-content reference was rejected (expired, deleted, wrong model). */
class CacheRejectedException(message: String) : Exception(message)

/** "provider/model", e.g. gemini/gemini-2.5-pro. */
data class ModelRef(val provider: Provider, val model: String) {
    val key: String get() = "${provider.name.lowercase()}/$model"
    val label: String get() = "${provider.label} · $model"

    companion object {
        fun parse(s: String): ModelRef? {
            val p = s.substringBefore('/', "").uppercase()
            val m = s.substringAfter('/', "").trim()
            val provider = Provider.entries.firstOrNull { it.name == p } ?: return null
            return if (m.isBlank()) null else ModelRef(provider, m)
        }
    }
}

object MessageJson {
    private fun usageJson(u: Usage) = JSONObject().put("i", u.input).put("o", u.output).put("c", u.cached).put("th", u.thoughts)
    private fun usageFrom(o: JSONObject?) = if (o == null) Usage() else
        Usage(o.optLong("i"), o.optLong("o"), o.optLong("c"), o.optLong("th"))

    fun toJson(list: List<ChatMessage>): String {
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            when (m) {
                is ChatMessage.User -> o.put("t", "user").put("text", m.text)
                is ChatMessage.Notice -> {
                    o.put("t", "notice").put("text", m.text).put("err", m.isError)
                    m.detail?.let { o.put("detail", it) }
                }
                is ChatMessage.Summary -> o.put("t", "summary").put("text", m.text)
                is ChatMessage.RouteInfo -> {
                    o.put("t", "route").put("text", m.text).put("route", m.route).put("ov", m.canOverride)
                    m.detail?.let { o.put("detail", it) }
                }
                is ChatMessage.UsageRecord -> o.put("t", "usage").put("model", m.model).put("role", m.role)
                    .put("u", usageJson(m.usage)).put("extra", m.extraCostUsd).put("label", m.label).put("disc", m.discount)
                is ChatMessage.CompactMark -> o.put("t", "mark")
                is ChatMessage.ContextBlock -> o.put("t", "ctx").put("text", m.text).put("label", m.label)
                is ChatMessage.ToolResult -> o.put("t", "tool").put("id", m.callId).put("name", m.name)
                    .put("content", m.content).put("err", m.isError)
                is ChatMessage.Assistant -> {
                    o.put("t", "assistant").put("text", m.text).put("model", m.model)
                    m.reasoning?.let { o.put("reasoning", it) }
                    m.textSignature?.let { o.put("tsig", it) }
                    val calls = JSONArray()
                    m.toolCalls.forEach { c ->
                        val co = JSONObject().put("id", c.id).put("name", c.name).put("args", c.args)
                        c.signature?.let { co.put("sig", it) }
                        calls.put(co)
                    }
                    o.put("calls", calls)
                }
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun fromJson(s: String): List<ChatMessage> {
        val arr = JSONArray(s)
        val out = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += when (o.getString("t")) {
                "user" -> ChatMessage.User(o.getString("text"))
                "notice" -> ChatMessage.Notice(o.getString("text"), if (o.has("detail")) o.getString("detail") else null, o.optBoolean("err"))
                "summary" -> ChatMessage.Summary(o.getString("text"))
                "route" -> ChatMessage.RouteInfo(o.getString("text"), if (o.has("detail")) o.getString("detail") else null,
                    o.optString("route"), o.optBoolean("ov"))
                "usage" -> ChatMessage.UsageRecord(o.optString("model"), o.optString("role"), usageFrom(o.optJSONObject("u")),
                    o.optDouble("extra", 0.0), o.optString("label"), o.optDouble("disc", 1.0))
                "mark" -> ChatMessage.CompactMark
                "ctx" -> ChatMessage.ContextBlock(o.getString("text"), o.optString("label"))
                "tool" -> ChatMessage.ToolResult(o.getString("id"), o.getString("name"), o.getString("content"), o.optBoolean("err"))
                else -> {
                    val calls = o.optJSONArray("calls") ?: JSONArray()
                    ChatMessage.Assistant(
                        text = o.optString("text"),
                        toolCalls = (0 until calls.length()).map {
                            val c = calls.getJSONObject(it)
                            ToolCall(c.getString("id"), c.getString("name"), c.optJSONObject("args") ?: JSONObject(),
                                if (c.has("sig")) c.getString("sig") else null)
                        },
                        reasoning = if (o.has("reasoning")) o.getString("reasoning") else null,
                        textSignature = if (o.has("tsig")) o.getString("tsig") else null,
                        model = o.optString("model"),
                    )
                }
            }
        }
        return out
    }
}
