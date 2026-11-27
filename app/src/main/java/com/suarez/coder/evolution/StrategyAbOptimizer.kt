package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small implementer-only tactics. At most two experiments, pinned for a session, 70% control / 30% variant.
 * The architect model id is stored and never written by a promotion or a rollback.
 */
class StrategyAbOptimizer(
    private val file: File,
    val architectModelId: String,
) {
    data class Tactic(val id: String, val hint: String)

    data class Assignment(val sessionId: String, val arm: String, val tacticId: String?, val hint: String?)

    data class Report(
        val controlN: Int,
        val variantN: Int,
        val controlCtg: Double,
        val variantCtg: Double,
        val controlSuccess: Double,
        val variantSuccess: Double,
        val promoted: Boolean,
        val rolledBack: Boolean,
        val active: List<String>,
        val note: String,
    )

    fun tactics(): List<Tactic> = TACTICS

    @Synchronized fun active(): List<String> = load().active

    /** Starts an experiment. The third one is refused. */
    @Synchronized fun start(tacticId: String): Boolean {
        if (TACTICS.none { it.id == tacticId }) return false
        val s = load()
        if (tacticId in s.active) return true
        if (s.active.size >= MAX_ACTIVE) return false
        save(s.copy(active = s.active + tacticId, rolledBack = false, promoted = false))
        return true
    }

    @Synchronized fun ensureActive() {
        if (load().active.isEmpty() && !load().rolledBack && load().promotedTactic == null) start(TACTICS.first().id)
    }

    /** Same session id always returns the same arm. */
    @Synchronized fun pin(sessionId: String, roll: Double = Math.random()): Assignment {
        val s = load()
        val existing = s.pins[sessionId]
        if (existing != null) return assignment(sessionId, existing, s)
        val variant = s.active.firstOrNull()
        val arm = if (variant == null || roll < CONTROL_SHARE) "control" else "variant"
        val pins = s.pins + (sessionId to arm)
        save(s.copy(pins = pins))
        return assignment(sessionId, arm, s)
    }

    @Synchronized fun record(sessionId: String, costToGreen: Double, success: Boolean) {
        val s = load()
        val arm = s.pins[sessionId] ?: return
        val sample = Sample(sessionId, arm, costToGreen.coerceAtLeast(0.0), success)
        val samples = (s.samples.filter { it.sessionId != sessionId } + sample).takeLast(80)
        save(s.copy(samples = samples))
    }

    @Synchronized fun snapshot(): Report = reportOf(load(), promoted = false, rolledBack = false, note = load().note)

    /** Compares arms after at least 10 tasks. A worse variant rolls back immediately at that point. */
    @Synchronized fun evaluate(): Report {
        val s = load()
        if (s.active.isEmpty()) return reportOf(s, false, false, s.note)
        val control = s.samples.filter { it.arm == "control" }
        val variant = s.samples.filter { it.arm == "variant" }
        val total = control.size + variant.size
        if (total < MIN_TASKS || variant.isEmpty() || control.isEmpty()) return reportOf(s, false, false, "örnek az")
        val cCtg = control.map { it.ctg }.average()
        val vCtg = variant.map { it.ctg }.average()
        val cOk = control.count { it.success }.toDouble() / control.size
        val vOk = variant.count { it.success }.toDouble() / variant.size
        val worse = vCtg > cCtg * 1.02 || vOk + 0.05 < cOk
        if (worse) {
            val note = "varyant geri alındı (CTG ${fmt(vCtg)} > kontrol ${fmt(cCtg)})"
            save(s.copy(active = emptyList(), rolledBack = true, note = note))
            return reportOf(load(), promoted = false, rolledBack = true, note = note)
        }
        val better = vCtg <= cCtg * 0.90 && vOk >= cOk
        if (better) {
            val id = s.active.first()
            val note = "varyant terfi etti ($id, CTG ${fmt(vCtg)} vs ${fmt(cCtg)})"
            save(s.copy(active = emptyList(), promoted = true, promotedTactic = id, note = note))
            return reportOf(load(), promoted = true, rolledBack = false, note = note)
        }
        return reportOf(s, false, false, "fark eşiğin altında")
    }

    @Synchronized fun reset() {
        if (file.isFile) file.delete()
    }

    private fun assignment(sessionId: String, arm: String, s: State): Assignment {
        val tactic = if (arm == "variant") s.active.firstOrNull()?.let { id -> TACTICS.firstOrNull { it.id == id } } else null
        val promoted = if (arm == "control" && tactic == null) s.promotedTactic?.let { id -> TACTICS.firstOrNull { it.id == id } } else null
        val hint = tactic?.hint ?: promoted?.hint
        return Assignment(sessionId, arm, tactic?.id ?: promoted?.id, hint)
    }

    private fun reportOf(s: State, promoted: Boolean, rolledBack: Boolean, note: String): Report {
        val control = s.samples.filter { it.arm == "control" }
        val variant = s.samples.filter { it.arm == "variant" }
        fun ctg(list: List<Sample>) = if (list.isEmpty()) 0.0 else list.map { it.ctg }.average()
        fun ok(list: List<Sample>) = if (list.isEmpty()) 0.0 else list.count { it.success }.toDouble() / list.size
        return Report(control.size, variant.size, ctg(control), ctg(variant), ok(control), ok(variant), promoted, rolledBack, s.active, note)
    }

    private data class Sample(val sessionId: String, val arm: String, val ctg: Double, val success: Boolean)
    private data class State(
        val active: List<String> = emptyList(),
        val pins: Map<String, String> = emptyMap(),
        val samples: List<Sample> = emptyList(),
        val promotedTactic: String? = null,
        val promoted: Boolean = false,
        val rolledBack: Boolean = false,
        val note: String = "",
    )

    private fun load(): State = runCatching {
        val o = JSONObject(file.readText())
        val pins = o.optJSONObject("pins")?.let { p -> p.keys().asSequence().associateWith { p.getString(it) } }.orEmpty()
        val samples = o.optJSONArray("samples")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Sample(it.getString("s"), it.getString("a"), it.optDouble("c"), it.optBoolean("ok")) }
        }.orEmpty()
        val active = o.optJSONArray("active")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        State(active, pins, samples, o.optString("promotedTactic").ifBlank { null }, o.optBoolean("promoted"), o.optBoolean("rolled"), o.optString("note"))
    }.getOrDefault(State())

    private fun save(s: State) {
        file.parentFile?.mkdirs()
        val o = JSONObject()
            .put("active", JSONArray(s.active))
            .put("pins", JSONObject().also { p -> s.pins.forEach { (k, v) -> p.put(k, v) } })
            .put("samples", JSONArray().also { a -> s.samples.forEach { a.put(JSONObject().put("s", it.sessionId).put("a", it.arm).put("c", it.ctg).put("ok", it.success)) } })
            .put("promoted", s.promoted)
            .put("rolled", s.rolledBack)
            .put("note", s.note)
        s.promotedTactic?.let { o.put("promotedTactic", it) }
        file.writeText(o.toString())
    }

    companion object {
        const val MAX_ACTIVE = 2
        const val CONTROL_SHARE = 0.70
        const val MIN_TASKS = 10
        val TACTICS = listOf(
            Tactic("list_symbols", "Uygulayıcı taktiği: önce github_list_symbols zorunluluğu. İmza veya ada dokunmadan önce github_list_symbols çağır, sonra find_references ve github_find_impact."),
            Tactic("compact_diff", "Uygulayıcı taktiği: SEARCH/REPLACE bloklarını kısa tut, her blok en fazla 30 satır."),
            Tactic("test_first", "Uygulayıcı taktiği: önce başarısız testi yaz veya çalıştır, sonra düzelt."),
        )
        private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
    }
}
