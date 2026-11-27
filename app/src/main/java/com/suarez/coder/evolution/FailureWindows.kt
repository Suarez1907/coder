package com.suarez.coder.evolution

import com.suarez.coder.ci.LogFilter
import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.llm.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded, anonymized slices of a real failure: about [RADIUS] lines on each side of the error.
 * [EvalGate] builds a regression case only from these slices. A trace without one cannot open a self-PR.
 */
object FailureWindows {
    const val RADIUS = 40

    fun from(run: List<ChatMessage>, request: String, upgradedToArchitect: Boolean): List<String> {
        val out = ArrayList<String>()
        val files = HashMap<String, String>()
        for (m in run) {
            if (m is ChatMessage.ToolResult && (m.name == "github_read_file" || m.name == "github_read_files")) {
                readBodies(m.content, files)
            }
        }
        for (m in run) {
            if (m is ChatMessage.Assistant && m.text.contains("<<<<<<< SEARCH")) {
                val parsed = SearchReplace.parse(m.text)
                for (b in parsed.blocks) {
                    val body = files[b.path] ?: files.entries.firstOrNull { it.key.endsWith("/" + b.path.substringAfterLast('/')) }?.value
                    val window = body?.let { around(it, b.search) } ?: continue
                    val ambiguous = run.any { it is ChatMessage.ToolResult && it.isError && it.content.contains("kez geçiyor") }
                    val o = JSONObject()
                        .put("kind", "search_replace")
                        .put("content", ErrorClusterEngine.anonymize(window))
                        .put("search", ErrorClusterEngine.anonymize(b.search).take(1_500))
                        .put("replace", ErrorClusterEngine.anonymize(b.replace).take(1_500))
                        .put("expect", if (ambiguous) "err" else "ok")
                    if (!ambiguous) {
                        val needle = b.replace.lineSequence().map { it.trim() }.firstOrNull { it.length in 4..120 }
                        if (needle != null) o.put("contains", ErrorClusterEngine.anonymize(needle))
                    }
                    out += o.toString()
                }
            }
            if (m is ChatMessage.ToolResult && m.name == "ci_wait" && m.content.contains("\nHatalar (")) {
                logWindow(m.content)?.let { out += it }
            }
        }
        if (upgradedToArchitect && request.trim().length >= 8) {
            out += JSONObject()
                .put("kind", "router_access")
                .put("request", ErrorClusterEngine.anonymize(request).take(500))
                .put("architect", "gemini/gemini-2.5-pro")
                .put("implementer", "gemini/gemini-2.5-flash")
                .put("expectRoute", "COMPLEX_ARCHITECT")
                .put("expectPhase", "READ_WRITE")
                .put("expectArchitect", "gemini/gemini-2.5-pro")
                .toString()
        }
        return out.distinct().take(4)
    }

    fun around(text: String, needle: String, radius: Int = RADIUS): String? {
        val lines = text.replace("\r\n", "\n").lines()
        val first = needle.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val idx = lines.indexOfFirst { it.trim() == first || (first.length >= 8 && it.contains(first)) }
        if (idx < 0) return null
        val from = (idx - radius).coerceAtLeast(0)
        val to = (idx + radius + 1).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    private fun readBodies(content: String, into: MutableMap<String, String>) {
        val head = Regex("(\\S+) \\(\\d+ satır\\)\\n")
        val m = head.find(content) ?: return
        val body = content.substring(m.range.last + 1).substringBefore("\n... (kesildi")
        if (body.isNotBlank()) into[m.groupValues[1]] = body
    }

    private fun logWindow(report: String): String? {
        val lines = report.lines()
        val idx = lines.indexOfFirst { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("##") && !t.startsWith("…") &&
                !LogFilter.extract(t).contains("(Belirgin hata satırı bulunamadı")
        }
        if (idx < 0) return null
        val from = (idx - RADIUS).coerceAtLeast(0)
        val to = (idx + RADIUS + 1).coerceAtMost(lines.size)
        val slice = ErrorClusterEngine.anonymize(lines.subList(from, to).joinToString("\n")).take(6_000)
        val needle = ErrorClusterEngine.anonymize(lines[idx].trim()).take(180)
        if (needle.isBlank() || !slice.contains(needle)) return null
        return JSONObject()
            .put("kind", "log_filter")
            .put("log", slice)
            .put("mustContain", JSONArray().put(needle))
            .put("mustNotContain", JSONArray().put("(Belirgin hata satırı bulunamadı"))
            .toString()
    }
}
