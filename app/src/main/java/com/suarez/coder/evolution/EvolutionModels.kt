package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject

/** Structured summary of one finished coding run (never of an evolution run). */
data class SessionTrace(
    val id: String,
    val sessionId: String,
    val repo: String,
    val at: Long,
    val request: String,
    val modelsUsed: List<String>,
    val turnCount: Int,
    val cacheHitPercent: Int?,
    val totalCostUsd: Double,
    val diffErrorCount: Int,
    val diffErrorSamples: List<String>,
    val ciFailureCount: Int,
    val ciSignatures: List<String>,
    val escalations: List<String>,
    val routing: String?,
    val upgradedToArchitect: Boolean,
    val stopped: Boolean,
    val userCorrection: Boolean = false,
    val processed: Boolean = false,
    /** CI waits during the run (passed or failed). */
    val ciRuns: Int = 0,
) {
    /** Problems that are worth learning from (a clean, cheap run teaches nothing). */
    val troubleScore get() = diffErrorCount + ciFailureCount * 2 + escalations.size + (if (stopped) 1 else 0) + (if (userCorrection) 2 else 0)
    val success get() = !stopped && !userCorrection && ciFailureCount == 0 && diffErrorCount == 0

    fun toJson(): JSONObject = JSONObject().put("id", id).put("session", sessionId).put("repo", repo).put("at", at).put("req", request)
        .put("models", JSONArray(modelsUsed)).put("turns", turnCount).put("cache", cacheHitPercent ?: JSONObject.NULL).put("cost", totalCostUsd)
        .put("diffErr", diffErrorCount).put("diffSamples", JSONArray(diffErrorSamples)).put("ciFail", ciFailureCount)
        .put("ciSig", JSONArray(ciSignatures)).put("esc", JSONArray(escalations)).put("routing", routing ?: JSONObject.NULL)
        .put("upgraded", upgradedToArchitect).put("stopped", stopped).put("correction", userCorrection).put("processed", processed).put("ciRuns", ciRuns)

    companion object {
        private fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        fun fromJson(o: JSONObject) = SessionTrace(
            o.getString("id"), o.optString("session"), o.optString("repo"), o.optLong("at"), o.optString("req"),
            list(o.optJSONArray("models")), o.optInt("turns"), if (o.isNull("cache") || !o.has("cache")) null else o.optInt("cache"),
            o.optDouble("cost", 0.0), o.optInt("diffErr"), list(o.optJSONArray("diffSamples")), o.optInt("ciFail"),
            list(o.optJSONArray("ciSig")), list(o.optJSONArray("esc")), if (o.isNull("routing")) null else o.optString("routing"),
            o.optBoolean("upgraded"), o.optBoolean("stopped"), o.optBoolean("correction"), o.optBoolean("processed"), o.optInt("ciRuns"),
        )
    }
}

enum class EvoTier(val label: String) { MEMORY("Hafıza"), ROUTING("Yönlendirme"), SELF_PR("Self-PR"), INFO("Bilgi") }

data class EvolutionLogEntry(
    val id: String,
    val at: Long,
    val tier: EvoTier,
    val summary: String,
    val costUsd: Double = 0.0,
    /** Routing entries: the AdaptiveRouting before the change, as JSON (for "Geri Al"). */
    val undo: String? = null,
    val undone: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("tier", tier.name).put("summary", summary)
        .put("cost", costUsd).put("undo", undo ?: JSONObject.NULL).put("undone", undone)

    companion object {
        fun fromJson(o: JSONObject) = EvolutionLogEntry(o.getString("id"), o.optLong("at"),
            runCatching { EvoTier.valueOf(o.optString("tier")) }.getOrDefault(EvoTier.INFO), o.optString("summary"),
            o.optDouble("cost", 0.0), if (o.isNull("undo") || !o.has("undo")) null else o.optString("undo"), o.optBoolean("undone"))
    }
}

data class LocalHint(val pattern: String, val route: String)
data class EscalationNote(val taskType: String, val model: String, val note: String)

/** Routing parameters the reflector may tune; everything is clamped by [EvolutionGuard]. */
data class AdaptiveRouting(
    val complexityThreshold: Double = EvolutionGuard.DEFAULT_THRESHOLD,
    val localHints: List<LocalHint> = emptyList(),
    val escalationNotes: List<EscalationNote> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("threshold", complexityThreshold)
        .put("hints", JSONArray().also { a -> localHints.forEach { a.put(JSONObject().put("p", it.pattern).put("r", it.route)) } })
        .put("esc", JSONArray().also { a -> escalationNotes.forEach { a.put(JSONObject().put("t", it.taskType).put("m", it.model).put("n", it.note)) } })

    /** Editor models that repeatedly fail: their ladder switches to the escalation model earlier. */
    fun earlyEscalation(model: String) = escalationNotes.any { it.model.equals(model, true) || model.endsWith("/" + it.model) }

    companion object {
        fun fromJson(s: String?): AdaptiveRouting = runCatching {
            val o = JSONObject(s!!)
            val h = o.optJSONArray("hints") ?: JSONArray()
            val e = o.optJSONArray("esc") ?: JSONArray()
            AdaptiveRouting(
                EvolutionGuard.clampThreshold(o.optDouble("threshold", EvolutionGuard.DEFAULT_THRESHOLD)),
                (0 until h.length()).map { h.getJSONObject(it) }.map { LocalHint(it.optString("p"), it.optString("r")) },
                (0 until e.length()).map { e.getJSONObject(it) }.map { EscalationNote(it.optString("t"), it.optString("m"), it.optString("n")) },
            )
        }.getOrDefault(AdaptiveRouting())
    }
}

/** Per (route, model) running statistics. */
data class RouteStat(val runs: Int = 0, val successes: Int = 0, val costUsd: Double = 0.0, val ciRuns: Int = 0, val ciFirstPass: Int = 0, val escalated: Int = 0) {
    val successRate get() = if (runs == 0) 0.0 else successes.toDouble() / runs
    val avgCost get() = if (runs == 0) 0.0 else costUsd / runs
}

/** A reusable workflow the reflector wants to keep as a skill (added disabled; the user turns it on). */
data class SkillProposal(val name: String, val description: String, val body: String)

data class SelfImprovement(val title: String, val problem: String, val files: List<String>, val topic: String, val repeatable: Boolean)

/** What the reflector returns (strict JSON, parsed leniently). */
data class EvolutionReport(
    val lessons: List<String>,
    val threshold: Double?,
    val hints: List<LocalHint>,
    val escalationNotes: List<EscalationNote>,
    val selfImprovement: SelfImprovement?,
    val skillProposal: SkillProposal? = null,
)

enum class PrState { PENDING, MERGED, REJECTED, CONFLICT }

data class PendingSelfPr(
    val number: Int,
    val title: String,
    val url: String,
    val branch: String,
    val rootCause: String,
    val files: List<String>,
    val ciPassed: Boolean,
    val costUsd: Double,
    val topic: String,
    val createdAt: Long,
    val state: PrState = PrState.PENDING,
    val apkUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("n", number).put("title", title).put("url", url).put("branch", branch).put("cause", rootCause)
        .put("files", JSONArray(files)).put("ci", ciPassed).put("cost", costUsd).put("topic", topic).put("at", createdAt)
        .put("state", state.name).put("apk", apkUrl ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = PendingSelfPr(o.getInt("n"), o.optString("title"), o.optString("url"), o.optString("branch"),
            o.optString("cause"), o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            o.optBoolean("ci"), o.optDouble("cost", 0.0), o.optString("topic"), o.optLong("at"),
            runCatching { PrState.valueOf(o.optString("state")) }.getOrDefault(PrState.PENDING), if (o.isNull("apk")) null else o.optString("apk"))
    }
}
