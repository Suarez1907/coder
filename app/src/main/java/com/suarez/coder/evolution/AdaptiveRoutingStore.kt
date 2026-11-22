package com.suarez.coder.evolution

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Tier 2: routing parameters tuned from experience, kept as data (no rebuild needed). Every change is clamped by
 * [EvolutionGuard] and logged with the previous value so the user can undo it in one tap.
 */
class AdaptiveRoutingStore(private val file: File, private val statsFile: File) {
    @Synchronized fun current(): AdaptiveRouting = if (file.isFile) AdaptiveRouting.fromJson(file.readText()) else AdaptiveRouting()

    @Synchronized fun save(r: AdaptiveRouting) = file.writeText(r.toJson().toString())

    /**
     * Applies a reflector report within the hard limits. Returns (before, after) when something changed, else null.
     * Hints must be short plain phrases with a known route; duplicates are skipped; the list is capped.
     */
    @Synchronized fun apply(report: EvolutionReport): Pair<AdaptiveRouting, AdaptiveRouting>? {
        val before = current()
        val routes = setOf("SIMPLE", "QUESTION", "COMPLEX")
        val newHints = report.hints
            .map { LocalHint(it.pattern.trim().lowercase(), normalizeRoute(it.route)) }
            .filter { it.route in routes && it.pattern.length in 3..40 && Regex("^[\\p{L}\\p{N} '.-]+$").matches(it.pattern) }
            .filter { h -> before.localHints.none { it.pattern == h.pattern } }
            .distinctBy { it.pattern }
        val notes = report.escalationNotes.filter { it.model.isNotBlank() && it.note.isNotBlank() }
            .filter { n -> before.escalationNotes.none { it.model == n.model && it.taskType == n.taskType } }
        val after = before.copy(
            complexityThreshold = report.threshold?.takeIf { !it.isNaN() }?.let { EvolutionGuard.clampThreshold(it) } ?: before.complexityThreshold,
            localHints = (before.localHints + newHints).takeLast(EvolutionGuard.MAX_HINTS),
            escalationNotes = (before.escalationNotes + notes).takeLast(10),
        )
        if (after == before) return null
        save(after)
        return before to after
    }

    private fun normalizeRoute(r: String) = r.trim().uppercase().let {
        when {
            it.startsWith("SIMPLE") || it == "EXECUTOR" -> "SIMPLE"
            it.startsWith("QUESTION") -> "QUESTION"
            it.startsWith("COMPLEX") || it == "ARCHITECT" -> "COMPLEX"
            else -> it
        }
    }

    /** Restores the routing saved in a log entry. */
    @Synchronized fun undo(previousJson: String) = save(AdaptiveRouting.fromJson(previousJson))

    // ---- Statistics per (route, model) ----

    @Synchronized fun stats(): Map<String, RouteStat> = runCatching {
        val o = JSONObject(statsFile.readText())
        o.keys().asSequence().associateWith { k ->
            val s = o.getJSONObject(k)
            RouteStat(s.optInt("n"), s.optInt("ok"), s.optDouble("usd", 0.0), s.optInt("ci"), s.optInt("ci1"), s.optInt("esc"))
        }
    }.getOrDefault(emptyMap())

    @Synchronized fun record(t: SessionTrace) {
        val o = runCatching { JSONObject(statsFile.readText()) }.getOrDefault(JSONObject())
        val route = t.routing?.let { r -> listOf("SIMPLE", "QUESTION", "COMPLEX", "MANUAL").firstOrNull { r.uppercase().contains(it) } } ?: "UNKNOWN"
        val models = t.modelsUsed.ifEmpty { listOf("?") }
        for (m in models) {
            val key = "$route|$m"
            val s = o.optJSONObject(key) ?: JSONObject()
            s.put("n", s.optInt("n") + 1).put("ok", s.optInt("ok") + if (t.success) 1 else 0)
                .put("usd", s.optDouble("usd", 0.0) + t.totalCostUsd / models.size)
                .put("esc", s.optInt("esc") + if (t.escalations.isNotEmpty() || t.upgradedToArchitect) 1 else 0)
            if (t.ciSignatures.isNotEmpty() || t.ciFailureCount > 0 || t.ciRuns > 0) {
                s.put("ci", s.optInt("ci") + 1).put("ci1", s.optInt("ci1") + if (t.ciFailureCount == 0) 1 else 0)
            }
            o.put(key, s)
        }
        statsFile.writeText(o.toString())
    }

    fun summary(): String = stats().entries.sortedByDescending { it.value.runs }.take(12).joinToString("\n") { (k, s) ->
        "$k: ${s.runs} çalışma, başarı %${(s.successRate * 100).toInt()}, ort. $" + String.format(Locale.US, "%.4f", s.avgCost) +
            ", CI ilk seferde ${s.ciFirstPass}/${s.ciRuns}, yükseltme ${s.escalated}"
    }
}
