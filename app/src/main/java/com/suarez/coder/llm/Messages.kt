package com.suarez.coder.llm

import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
    /** Gemini "thoughtSignature" that must be sent back unchanged. */
    val signature: String? = null,
)

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

    /** Shown in the chat only; never sent to the model. */
    data class Notice(val text: String) : ChatMessage()
}

data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

interface LlmClient {
    suspend fun complete(system: String, history: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage.Assistant
    suspend fun listModels(): List<String>
}

class LlmException(message: String) : Exception(message)

object MessageJson {
    fun toJson(list: List<ChatMessage>): String {
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            when (m) {
                is ChatMessage.User -> o.put("t", "user").put("text", m.text)
                is ChatMessage.Notice -> o.put("t", "notice").put("text", m.text)
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
                "notice" -> ChatMessage.Notice(o.getString("text"))
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
