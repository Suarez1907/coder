package com.suarez.coder.agent

import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One timed step of a task: a model call, a tool, a CI wait, a cloud run, or the whole run. */
data class Span(val kind: String, val name: String, val start: Long, val end: Long, val costUsd: Double = 0.0, val ok: Boolean = true) {
    val ms get() = (end - start).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().put("k", kind).put("n", name).put("s", start).put("e", end).put("c", costUsd).put("ok", ok)

    companion object {
        const val MODEL = "model"; const val TOOL = "tool"; const val CI = "ci"; const val CLOUD = "cloud"; const val RUN = "run"
        fun fromJson(o: JSONObject) = Span(o.optString("k"), o.optString("n"), o.optLong("s"), o.optLong("e"), o.optDouble("c", 0.0), o.optBoolean("ok", true))
        fun kindOfTool(name: String) = when (name) { "ci_wait" -> CI; "run_cloud_tool", "codemod", "mutation_test" -> CLOUD; else -> TOOL }
        fun label(kind: String) = when (kind) { MODEL -> "Model"; TOOL -> "Araç"; CI -> "CI bekleme"; CLOUD -> "Bulut"; else -> kind }
    }
}

/** Where a task's time and money went. */
object TimelineReport {
    data class Total(val kind: String, val ms: Long, val cost: Double, val count: Int)

    /** Spans of the last run (inside its "run" span; everything when there is none). */
    fun lastRun(spans: List<Span>): Pair<Span?, List<Span>> {
        val run = spans.lastOrNull { it.kind == Span.RUN } ?: return null to spans.filter { it.kind != Span.RUN }
        return run to spans.filter { it.kind != Span.RUN && it.start >= run.start && it.end <= run.end + 1_000 }
    }

    fun totals(spans: List<Span>): List<Total> = spans.groupBy { it.kind }
        .map { (k, s) -> Total(k, s.sumOf { it.ms }, s.sumOf { it.costUsd }, s.size) }.sortedByDescending { it.ms }

    fun slowest(spans: List<Span>, n: Int = 5) = spans.sortedByDescending { it.ms }.take(n)

    fun secs(ms: Long) = if (ms >= 60_000) String.format(Locale.US, "%d dk %d sn", ms / 60_000, (ms % 60_000) / 1000)
        else String.format(Locale.US, "%.1f sn", ms / 1000.0)
}

/** Spans per chat, append-only (one JSON object per line). */
class SpanStore(private val dir: File) {
    private fun f(sid: String) = File(dir, "$sid.spans.jsonl")

    @Synchronized fun add(sid: String, s: Span) {
        dir.mkdirs()
        f(sid).appendText(s.toJson().toString() + "\n")
    }

    @Synchronized fun load(sid: String): List<Span> = runCatching {
        f(sid).readLines().mapNotNull { l -> if (l.isBlank()) null else runCatching { Span.fromJson(JSONObject(l)) }.getOrNull() }
    }.getOrDefault(emptyList()).takeLast(MAX)

    fun delete(sid: String) { f(sid).delete() }

    companion object { const val MAX = 2_000 }
}
