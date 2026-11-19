package com.suarez.coder.context

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the model-visible history from the full chat:
 * - everything before the last [ChatMessage.Summary] is replaced by that summary,
 * - the last [keepTurns] user turns keep their text in full; tool outputs of earlier turns in that window are capped,
 * - older turns are compacted: tool outputs, code blocks and long tool arguments become short placeholders,
 * - inside the current turn only the most recent tool outputs stay full.
 */
object HistoryPruner {
    const val OLD_TOOL_RESULT_CHARS = 200
    const val RECENT_TOOL_RESULT_CHARS = 2_000
    const val CURRENT_TURN_FULL_RESULTS = 6
    const val CURRENT_TURN_OLD_RESULT_CHARS = 1_500
    const val LONG_ARG_CHARS = 300

    data class Prepared(
        /** Messages to send; the summary (if any) is already merged into the first user message. */
        val messages: List<ChatMessage>,
        /** Older messages (after the last summary) that could be folded into a new summary. */
        val summarizable: List<ChatMessage>,
        /** Index in the full list where a new Summary would be inserted. */
        val summaryInsertIndex: Int,
        val summary: String?,
    )

    private fun modelVisible(m: ChatMessage) =
        m is ChatMessage.User || m is ChatMessage.Assistant || m is ChatMessage.ToolResult

    fun prepare(all: List<ChatMessage>, keepTurns: Int): Prepared {
        val lastSummary = all.indexOfLast { it is ChatMessage.Summary }
        val summary = (all.getOrNull(lastSummary) as? ChatMessage.Summary)?.text
        val startIdx = lastSummary + 1
        // Indices (in `all`) of model-visible messages after the summary.
        val idx = (startIdx until all.size).filter { modelVisible(all[it]) }
        val userPositions = idx.filter { all[it] is ChatMessage.User }
        val keep = keepTurns.coerceAtLeast(1)
        val splitAt = if (userPositions.size > keep) userPositions[userPositions.size - keep] else idx.firstOrNull() ?: all.size
        val old = idx.filter { it < splitAt }.map { all[it] }
        val recent = idx.filter { it >= splitAt }.map { all[it] }

        val out = ArrayList<ChatMessage>()
        old.forEach { out += compactOld(it) }

        val currentTurnStart = recent.indexOfLast { it is ChatMessage.User }.coerceAtLeast(0)
        val currentResults = recent.withIndex().filter { it.index > currentTurnStart && it.value is ChatMessage.ToolResult }.map { it.index }
        val fullResults = currentResults.takeLast(CURRENT_TURN_FULL_RESULTS).toSet()
        recent.forEachIndexed { i, m ->
            out += when {
                m is ChatMessage.ToolResult && i > currentTurnStart && i in fullResults -> m
                m is ChatMessage.ToolResult && i > currentTurnStart ->
                    m.copy(content = truncate(m.content, CURRENT_TURN_OLD_RESULT_CHARS, "Gerekirse aracı tekrar çağır."))
                m is ChatMessage.ToolResult -> m.copy(content = truncate(m.content, RECENT_TOOL_RESULT_CHARS, "Gerekirse aracı tekrar çağır."))
                else -> m
            }
        }

        repairToolPairs(out)
        if (summary != null) {
            val firstUser = out.indexOfFirst { it is ChatMessage.User }
            val prefix = "[Önceki konuşmanın özeti]\n$summary\n[Özet sonu]\n\n"
            if (firstUser >= 0) {
                out[firstUser] = ChatMessage.User(prefix + (out[firstUser] as ChatMessage.User).text)
            } else {
                out.add(0, ChatMessage.User(prefix.trim()))
            }
        }
        return Prepared(out, old, splitAt, summary)
    }

    /**
     * Every tool call needs a result right after it, and every result needs its call; otherwise the API rejects
     * the request. This happens when a run is stopped midway, so missing results are filled in.
     */
    fun repairToolPairs(list: MutableList<ChatMessage>) {
        var i = 0
        while (i < list.size) {
            val m = list[i]
            if (m is ChatMessage.ToolResult) { list.removeAt(i); continue } // orphan
            i++
            if (m is ChatMessage.Assistant && m.toolCalls.isNotEmpty()) {
                val ids = m.toolCalls.map { it.id }.toMutableSet()
                while (i < list.size && list[i] is ChatMessage.ToolResult) {
                    val r = list[i] as ChatMessage.ToolResult
                    if (ids.remove(r.callId)) i++ else list.removeAt(i)
                }
                for (c in m.toolCalls.filter { it.id in ids }) {
                    list.add(i, ChatMessage.ToolResult(c.id, c.name, "Yanıt yok: işlem durduruldu.", true)); i++
                }
            }
        }
    }

    fun compactOld(m: ChatMessage): ChatMessage = when (m) {
        is ChatMessage.ToolResult -> m.copy(content = truncate(m.content, OLD_TOOL_RESULT_CHARS, "Eski araç çıktısı kısaltıldı."))
        is ChatMessage.Assistant -> m.copy(
            text = stripCodeBlocks(m.text),
            toolCalls = m.toolCalls.map { compactCall(it) },
            reasoning = null,
        )
        else -> m
    }

    private fun compactCall(c: ToolCall): ToolCall = c.copy(args = compactJson(c.args) as JSONObject)

    private fun compactJson(v: Any?): Any? = when (v) {
        is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, compactJson(v.opt(k))) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(compactJson(v.opt(i))) }
        is String -> if (v.length > LONG_ARG_CHARS) v.take(120) + " …[${v.length} karakter kısaltıldı]" else v
        else -> v
    }

    fun truncate(s: String, max: Int, note: String): String =
        if (s.length <= max) s else s.take(max) + "\n…[${s.length - max} karakter kısaltıldı. $note]"

    fun stripCodeBlocks(text: String): String {
        val re = Regex("```[^\\n]*\\n([\\s\\S]*?)```")
        return re.replace(text) { m ->
            val lines = m.groupValues[1].count { it == '\n' }
            if (lines <= 6) m.value else "[kod bloğu çıkarıldı, $lines satır]"
        }
    }

    /** Rough size of messages, used to decide when summarizing pays off. */
    fun charSize(list: List<ChatMessage>): Int = list.sumOf { m ->
        when (m) {
            is ChatMessage.User -> m.text.length
            is ChatMessage.Assistant -> m.text.length + m.toolCalls.sumOf { it.args.toString().length }
            is ChatMessage.ToolResult -> m.content.length
            else -> 0
        }
    }

    /** Plain-text transcript for the summarizer. */
    fun transcript(list: List<ChatMessage>): String = buildString {
        for (m in list.map { compactOld(it) }) {
            when (m) {
                is ChatMessage.User -> append("KULLANICI: ").append(m.text).append("\n\n")
                is ChatMessage.Assistant -> {
                    if (m.text.isNotBlank()) append("ASİSTAN: ").append(m.text).append("\n")
                    m.toolCalls.forEach { append("  [araç ${it.name} ${it.args.toString().take(300)}]\n") }
                    append("\n")
                }
                is ChatMessage.ToolResult -> append("  [sonuç ${m.name}: ${m.content.take(200)}]\n")
                else -> Unit
            }
        }
    }
}
