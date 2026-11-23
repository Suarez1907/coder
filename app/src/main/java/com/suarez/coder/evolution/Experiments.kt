package com.suarez.coder.evolution

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Result of exam questions under one configuration. */
data class ExamScore(val passed: Int, val total: Int, val costUsd: Double) {
    operator fun plus(o: ExamScore) = ExamScore(passed + o.passed, total + o.total, costUsd + o.costUsd)
    fun toJson(): JSONObject = JSONObject().put("p", passed).put("t", total).put("c", costUsd)
    override fun toString() = "$passed/$total geçti, $" + String.format(Locale.US, "%.3f", costUsd)

    companion object {
        val EMPTY = ExamScore(0, 0, 0.0)
        fun fromJson(o: JSONObject) = ExamScore(o.optInt("p"), o.optInt("t"), o.optDouble("c", 0.0))
    }
}

/**
 * One proposed change to how Coder works, tested on exam questions before it is kept:
 * a different model for a role, an extra working rule, or enabling a skill.
 */
data class Experiment(
    val id: String = UUID.randomUUID().toString().take(8),
    val kind: Kind,
    val title: String,
    /** MODEL: role + model; RULE: text; SKILL: name. */
    val payload: JSONObject,
    val state: State = State.QUEUED,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val decidedAt: Long = 0,
    val baseline: ExamScore? = null,
    val candidate: ExamScore? = null,
    val note: String? = null,
) {
    enum class Kind(val label: String) { MODEL("Model"), RULE("Kural"), SKILL("Yetenek") }
    enum class State(val label: String) { QUEUED("Sırada"), RUNNING("Sınavda"), PROPOSED("Onay bekliyor"), ADOPTED("Kabul edildi"), REJECTED("Reddedildi"), FAILED("Ölçülemedi") }

    /** Identity of the change itself (the same change is never tested twice). */
    val key get() = "${kind.name}:" + when (kind) {
        Kind.MODEL -> payload.optString("role") + "=" + payload.optString("model")
        Kind.RULE -> payload.optString("text").lowercase().replace(Regex("\\s+"), " ").trim()
        Kind.SKILL -> payload.optString("name")
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("title", title).put("payload", payload)
        .put("state", state.name).put("source", source).put("created", createdAt).put("decided", decidedAt)
        .put("base", baseline?.toJson() ?: JSONObject.NULL).put("cand", candidate?.toJson() ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Experiment(
            o.getString("id"), Kind.valueOf(o.getString("kind")), o.optString("title"), o.optJSONObject("payload") ?: JSONObject(),
            runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.QUEUED), o.optString("source"),
            o.optLong("created"), o.optLong("decided"),
            o.optJSONObject("base")?.let { ExamScore.fromJson(it) }, o.optJSONObject("cand")?.let { ExamScore.fromJson(it) },
            o.optNullableString("note"),
        )

        fun model(role: String, model: String, source: String) =
            Experiment(kind = Kind.MODEL, title = "${if (role == "architect") "Mimar" else "Uygulayıcı"} → ${model.substringAfter('/')}",
                payload = JSONObject().put("role", role).put("model", model), source = source)

        fun rule(text: String, source: String) =
            Experiment(kind = Kind.RULE, title = "Kural: " + text.take(80), payload = JSONObject().put("text", text.trim()), source = source)

        fun skill(name: String, source: String) =
            Experiment(kind = Kind.SKILL, title = "Yetenek: /$name", payload = JSONObject().put("name", name), source = source)

        /**
         * Keep the candidate only when it is not worse: more questions passed, or the same number passed for at least
         * 10% less money. A candidate that passes nothing is never kept. (Few questions → noisy; erring on "keep the
         * old way" is the safe side.)
         */
        fun wins(base: ExamScore, cand: ExamScore): Boolean = when {
            cand.total == 0 || cand.passed == 0 -> false
            cand.passed > base.passed -> true
            cand.passed == base.passed && cand.costUsd <= base.costUsd * 0.9 -> true
            else -> false
        }
    }
}

/** A past task that ended well, replayable as an exam question. */
data class ExamCase(val id: String, val repo: String, val baseSha: String, val request: String, val route: String)

object ExamCases {
    /** Successful code tasks with a known starting commit and CI, newest first, at most one per request text. */
    fun from(traces: List<SessionTrace>, excluded: Set<String> = emptySet()): List<ExamCase> = traces
        .filter { t -> Outcome.of(t) >= 0.8 && t.rating >= 0 && t.repo.isNotBlank() && t.baseSha != null && t.ciRuns > 0 }
        .filter { (it.route ?: "").let { r -> r.startsWith("SIMPLE") || r.startsWith("COMPLEX") } && it.request.length >= 10 }
        .filter { it.id !in excluded }
        .sortedByDescending { it.at }
        .distinctBy { it.request.lowercase().take(120) }
        .map { ExamCase(it.id, it.repo, it.baseSha!!, it.request, it.route ?: "SIMPLE_EDIT") }

    /** Up to [k] questions, spread over repositories. */
    fun pick(cases: List<ExamCase>, k: Int): List<ExamCase> {
        val byRepo = cases.groupBy { it.repo }.values.map { it.toMutableList() }
        val out = ArrayList<ExamCase>()
        while (out.size < k && byRepo.any { it.isNotEmpty() }) for (l in byRepo) if (l.isNotEmpty() && out.size < k) out += l.removeAt(0)
        return out
    }
}
