package com.suarez.coder.llm

import org.json.JSONArray
import org.json.JSONObject

/** Live progress of a streamed answer. */
data class StreamProgress(val thoughtChars: Int, val textChars: Int, val toolCalls: Int, val lastThought: String)

/**
 * Collects the chunks of a Gemini `streamGenerateContent` (SSE) response into the same JSON shape as a
 * non-streaming response: consecutive text pieces are joined, function calls and thought signatures are kept,
 * and finish reason, continuation token, usage and service tier come from the latest chunk that has them.
 */
class StreamAccumulator {
    private val parts = JSONArray()
    private var finish = ""
    private var token: String? = null
    private var usage: JSONObject? = null
    private var tier: String? = null
    private var feedback: JSONObject? = null
    private var thoughtChars = 0
    private var textChars = 0
    private var calls = 0
    private var lastThought = ""

    /** @return answer text added by this chunk (thoughts are not included). */
    fun add(chunk: JSONObject): String {
        val delta = StringBuilder()
        chunk.optJSONObject("usageMetadata")?.let { usage = it }
        chunk.optString("serviceTier").takeIf { it.isNotBlank() }?.let { tier = it }
        chunk.optJSONObject("promptFeedback")?.let { feedback = it }
        val cand = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        cand.optString("finishReason").takeIf { it.isNotBlank() }?.let { finish = it }
        cand.optString("continuationToken").takeIf { it.isNotBlank() }?.let { token = it }
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            when {
                p.has("functionCall") -> { parts.put(JSONObject(p.toString())); calls++ }
                p.optBoolean("thought") -> {
                    val t = p.optString("text")
                    thoughtChars += t.length
                    t.lines().firstOrNull { it.isNotBlank() }?.let { lastThought = it.trim().trim('*').trim() }
                    appendText(p, thought = true)
                }
                p.has("text") || p.has("thoughtSignature") -> {
                    val t = p.optString("text")
                    textChars += t.length
                    delta.append(t)
                    appendText(p, thought = false)
                }
                else -> parts.put(JSONObject(p.toString()))
            }
        }
        return delta.toString()
    }

    /** Joins a text piece onto the previous part when both are plain text of the same kind (thought or answer). */
    private fun appendText(p: JSONObject, thought: Boolean) {
        val last = if (parts.length() > 0) parts.getJSONObject(parts.length() - 1) else null
        val sameKind = last != null && !last.has("functionCall") && last.has("text") && last.optBoolean("thought") == thought
        if (sameKind) {
            last!!.put("text", last.getString("text") + p.optString("text"))
            if (p.has("thoughtSignature")) last.put("thoughtSignature", p.getString("thoughtSignature"))
        } else {
            val copy = JSONObject(p.toString())
            if (!copy.has("text")) copy.put("text", "")
            parts.put(copy)
        }
    }

    fun progress() = StreamProgress(thoughtChars, textChars, calls, lastThought)

    fun result(): JSONObject {
        val cand = JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))
        if (finish.isNotBlank()) cand.put("finishReason", finish)
        token?.let { cand.put("continuationToken", it) }
        val out = JSONObject().put("candidates", JSONArray().put(cand))
        usage?.let { out.put("usageMetadata", it) }
        tier?.let { out.put("serviceTier", it) }
        feedback?.let { out.put("promptFeedback", it) }
        return out
    }
}
