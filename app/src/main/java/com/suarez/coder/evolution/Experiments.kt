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
    enum class Kind(val label: String) { MODEL("Model"), RULE("Kural"), SKILL("Yetenek"), PROMPT("İstem") }
    enum class State(val label: String) { QUEUED("Sırada"), RUNNING("Sınavda"), PROPOSED("Onay bekliyor"), ADOPTED("Kabul edildi"), REJECTED("Reddedildi"), FAILED("Ölçülemedi") }

    /** True when this experiment would change the architect (planner) model, which is locked. */
    fun isArchitectExperiment(): Boolean = kind == Kind.MODEL && payload.optString("role") == "architect"

    /** Identity of the change itself (the same change is never tested twice). */
    val key get() = "${kind.name}:" + when (kind) {
        Kind.MODEL -> payload.optString("role") + "=" + payload.optString("model")
        Kind.RULE -> payload.optString("text").lowercase().replace(Regex("\\s+"), " ").trim()
        Kind.SKILL -> payload.optString("name")
        Kind.PROMPT -> payload.optString("section") + "#" + payload.optString("text").replace(Regex("\\s+"), " ").trim().hashCode().toString(16)
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
            o.optString("note").takeIf { it.isNotEmpty() && it != "null" },
        )

        fun model(role: String, model: String, source: String): Experiment {
            require(role != "architect") { "Mimar modeli kilitli: architect deneyi üretilemez" }
            return Experiment(kind = Kind.MODEL, title = "Uygulayıcı → ${model.substringAfter('/')}",
                payload = JSONObject().put("role", role).put("model", model), source = source)
        }

        fun rule(text: String, source: String) =
            Experiment(kind = Kind.RULE, title = "Kural: " + text.take(80), payload = JSONObject().put("text", text.trim()), source = source)

        fun prompt(section: String, text: String, source: String) =
            Experiment(kind = Kind.PROMPT, title = "İstem: $section yeniden yazımı",
                payload = JSONObject().put("section", section).put("text", text), source = source)

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
