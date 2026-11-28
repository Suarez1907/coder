package com.suarez.coder.context

import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the model-visible history in a cache-friendly way.
 *
 * Implicit prompt caching only pays off when each request starts with exactly the same content as the previous one.
 * So instead of re-shortening old messages on every step (which rewrites the middle of the prompt and forces everything
 * after it to be billed at full price again), history is append-only between rare checkpoints:
 * - everything before the last [ChatMessage.Summary] is replaced by that summary,
 * - messages before the last [ChatMessage.CompactMark] are compacted (deterministically, so the result is stable),
 * - messages after it are sent unchanged (only a fixed per-result cap), so consecutive requests share their prefix.
 * A new mark is inserted by [checkpointIndex] only when the uncompacted tail grows past a threshold.
 */
object HistoryPruner {
    const val OLD_TOOL_RESULT_CHARS = 200
    const val TOOL_RESULT_HARD_CAP = 20_000
    const val LONG_ARG_CHARS = 300
    const val KEEP_RESULTS_IN_HUGE_TURN = 6
    const val MIN_FULL_RESULTS = 2

    data class Prepared(
        /** Messages to send; the summary (if any) is merged into the first user message. */
        val messages: List<ChatMessage>,
        /** Compacted messages (between the last summary and the last mark) that a new summary could replace. */
        val summarizable: List<ChatMessage>,
        /** Index in the full list where a new Summary would be inserted (the last mark). */
        val summaryInsertIndex: Int,
        val summary: String?,
    )

    private fun modelVisible(m: ChatMessage) =
        m is ChatMessage.User || m is ChatMessage.Assistant || m is ChatMessage.ToolResult || m is ChatMessage.ContextBlock

    private fun lastSummaryIndex(all: List<ChatMessage>) = all.indexOfLast { it is ChatMessage.Summary }

    private fun lastMarkIndex(all: List<ChatMessage>, after: Int): Int =
        (all.size - 1 downTo after + 1).firstOrNull { all[it] is ChatMessage.CompactMark } ?: -1

    @Suppress("UNUSED_PARAMETER")
    fun prepare(all: List<ChatMessage>, keepTurns: Int = 4): Prepared {
        val s = lastSummaryIndex(all)
        val summary = (all.getOrNull(s) as? ChatMessage.Summary)?.text
        val mark = lastMarkIndex(all, s)
        val out = ArrayList<ChatMessage>()
        val old = ArrayList<ChatMessage>()
        for (i in s + 1 until all.size) {
            val m = all[i]
            if (!modelVisible(m)) continue
            if (i < mark) {
                old += m
                out += compactOld(m)
            } else {
                out += if (m is ChatMessage.ToolResult) m.copy(content = truncate(m.content, TOOL_RESULT_HARD_CAP, "Gerekirse daha dar bir aralık oku.")) else m
            }
        }
        mergeContextBlocks(out)
        repairToolPairs(out)
        normalizeTurns(out, summary != null)
        if (summary != null) {
            val prefix = "[Önceki konuşmanın özeti]\n$summary\n[Özet sonu]\n\n"
            val first = out[0] as ChatMessage.User
            out[0] = first.copy(text = prefix + first.text)
        }
        return Prepared(out, old, if (mark >= 0) mark else s + 1, summary)
    }

    /**
     * Returns where to insert a new [ChatMessage.CompactMark] when the uncompacted tail exceeds [maxTailChars], or null.
     * The last [keepTurns] user turns stay full; when the current turn alone is too large, only its last
     * [KEEP_RESULTS_IN_HUGE_TURN] tool results stay full. A checkpoint is only placed when it shrinks a meaningful part,
     * so the prefix is not rewritten on every step.
     */
    fun checkpointIndex(all: List<ChatMessage>, keepTurns: Int, maxTailChars: Int): Int? {
        val s = lastSummaryIndex(all)
        val mark = lastMarkIndex(all, s)
        val tailStart = maxOf(s, mark) + 1
        val tailIdx = (tailStart until all.size).filter { modelVisible(all[it]) }
        val tailSize = charSize(tailIdx.map { all[it] })
        if (tailSize <= maxTailChars) return null
        val users = tailIdx.filter { all[it] is ChatMessage.User }
        var pos = if (users.size > keepTurns) users[users.size - keepTurns] else -1
        if (pos <= tailStart) {
            val results = tailIdx.filter { all[it] is ChatMessage.ToolResult }
            if (results.size > KEEP_RESULTS_IN_HUGE_TURN) {
                val keepFrom = results[results.size - KEEP_RESULTS_IN_HUGE_TURN]
                pos = (keepFrom downTo tailStart).firstOrNull { all[it] is ChatMessage.Assistant } ?: return null
            } else if (tailSize > maxTailChars * 2 && results.size > MIN_FULL_RESULTS) {
                // Cost & compaction guard: even with few tool results, prevent runaway prompt bloat.
                val keepFrom = results[results.size - MIN_FULL_RESULTS]
                pos = (keepFrom downTo tailStart).firstOrNull { all[it] is ChatMessage.Assistant } ?: return null
            } else {
                return null
            }
        }
        // Everything kept full is billed fresh once right after the checkpoint, so keep at most a quarter of the threshold
        // (but always the last MIN_FULL_RESULTS tool results), and compact the rest in one go.
        val keepBudget = maxTailChars / 4
        var kept = 0
        var budgetPos = all.size
        for (i in tailIdx.reversed()) {
            kept += charSize(listOf(all[i]))
            if (kept > keepBudget) break
            budgetPos = i
        }
        val results = tailIdx.filter { all[it] is ChatMessage.ToolResult }
        val mustKeep = results.getOrNull(results.size - MIN_FULL_RESULTS)
            ?.let { r -> (r downTo tailStart).firstOrNull { all[it] is ChatMessage.Assistant } } ?: all.size
        val snapped = (budgetPos until all.size).firstOrNull { all[it] is ChatMessage.User || all[it] is ChatMessage.Assistant } ?: all.size
        pos = maxOf(pos, minOf(snapped, mustKeep))
        if (pos <= tailStart || pos >= all.size) return null
        // Only worth breaking the cached prefix when a substantial part gets compacted.
        val compacted = charSize(tailIdx.filter { it < pos }.map { all[it] })
        return if (compacted >= tailSize / 4) pos else null
    }

    /**
     * Gemini requires the conversation to start with a user turn and every function-call turn to follow a user turn or
     * function responses. After a summary or a checkpoint the kept part can start with (or contain back-to-back) model
     * turns, so short user turns are inserted where needed. Inserted text is deterministic, keeping the prefix stable.
     */
    fun normalizeTurns(list: MutableList<ChatMessage>, hasSummary: Boolean = false) {
        if (list.isEmpty() || list[0] !is ChatMessage.User) {
            list.add(0, ChatMessage.User(if (hasSummary) "(Konuşma özetten sonra devam ediyor.)" else "(Önceki konuşmanın devamı.)"))
        }
        var i = 1
        while (i < list.size) {
            val m = list[i]
            val prev = list[i - 1]
            if (m is ChatMessage.Assistant && m.toolCalls.isNotEmpty() && prev is ChatMessage.Assistant) {
                list.add(i, ChatMessage.User("(devam)"))
                i++
            }
            i++
        }
    }

    /** Context blocks are sent as part of the user message they belong to. */
    private fun mergeContextBlocks(list: MutableList<ChatMessage>) {
        var i = 0
        while (i < list.size) {
            val m = list[i]
            if (m is ChatMessage.ContextBlock) {
                val prev = list.getOrNull(i - 1)
                if (prev is ChatMessage.User) {
                    list[i - 1] = prev.copy(text = prev.text + "\n\n" + m.text)
                    list.removeAt(i)
                    continue
                }
                list[i] = ChatMessage.User(m.text)
            }
            i++
        }
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
        is ChatMessage.ContextBlock -> m.copy(text = "[Bu istek için eklenen bağlam dosyaları: ${m.label}]")
        // Old screenshots are not re-sent after a checkpoint; a note keeps the conversation readable.
        is ChatMessage.User -> if (m.images.isEmpty()) m else m.copy(text = m.text + "\n[${m.images.size} ekran görüntüsü eklenmişti]", images = emptyList())
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

    /** Rough size of messages, used for thresholds. */
    fun charSize(list: List<ChatMessage>): Int = list.sumOf { m ->
        when (m) {
            is ChatMessage.User -> m.text.length
            is ChatMessage.Assistant -> m.text.length + m.toolCalls.sumOf { it.args.toString().length }
            is ChatMessage.ToolResult -> m.content.length
            is ChatMessage.ContextBlock -> m.text.length
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
                is ChatMessage.ContextBlock -> append("  ${m.text}\n")
                else -> Unit
            }
        }
    }
}
