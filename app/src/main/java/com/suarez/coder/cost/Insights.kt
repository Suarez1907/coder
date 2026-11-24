package com.suarez.coder.cost

import com.suarez.coder.evolution.SessionTrace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Model report card from the user's own tasks: success, cost per task, first-try CI passes, cache hits. Real data from
 * the user's repositories beats public benchmarks for choosing models.
 */
object ModelReport {
    data class Row(
        val model: String,
        val tasks: Int,
        val successRate: Double,
        val avgCost: Double,
        /** Of the tasks that ran CI, the share that passed without a red round. */
        val ciFirstTry: Double?,
        val avgTurns: Double,
        val rating: Int,
    ) {
        /** Success first; among similar success, the cheaper. */
        fun score(costWeight: Double, maxCost: Double) = successRate - costWeight * (if (maxCost > 0) avgCost / maxCost else 0.0) * 0.3
    }

    const val MIN_TASKS = 3

    /** A run with several models is counted for each of them (attribution is approximate). */
    fun rows(traces: List<SessionTrace>, sinceMs: Long = 0): List<Row> {
        val by = HashMap<String, MutableList<SessionTrace>>()
        for (t in traces) {
            if (t.at < sinceMs || t.route == "QUESTION_ONLY") continue
            for (m in t.modelsUsed.distinct()) {
                if (m.isBlank() || m.contains("decision") || m == "coder") continue
                by.getOrPut(m) { ArrayList() } += t
            }
        }
        return by.map { (m, ts) ->
            val ci = ts.filter { it.ciRuns > 0 }
            Row(m, ts.size, ts.count { it.success }.toDouble() / ts.size, ts.sumOf { it.totalCostUsd } / ts.size,
                if (ci.isEmpty()) null else ci.count { it.ciFailureCount == 0 }.toDouble() / ci.size,
                ts.sumOf { it.turnCount }.toDouble() / ts.size, ts.sumOf { it.rating })
        }.sortedWith(compareByDescending<Row> { it.tasks >= MIN_TASKS }.thenByDescending { it.successRate }.thenBy { it.avgCost })
    }

    /**
     * Suggested escalation order from real data: models at least as successful as the current editor, cheapest first
     * (each step up should cost more and succeed more often). Needs MIN_TASKS runs per model.
     */
    fun suggestedChain(rows: List<Row>, editor: String): List<String> {
        val known = rows.filter { it.tasks >= MIN_TASKS }
        val base = known.firstOrNull { it.model == editor }?.successRate ?: 0.0
        return known.filter { it.model != editor && it.successRate >= base }.sortedBy { it.avgCost }.map { it.model }.take(4)
    }

    data class CacheRow(val role: String, val model: String, val calls: Int, val hitRate: Double, val input: Long)

    /** Share of input tokens served from cache, per role and model, from the cost ledger. */
    fun cacheRows(entries: List<LedgerEntry>, sinceMs: Long): List<CacheRow> =
        entries.filter { it.at >= sinceMs && it.input > 0 && it.role !in setOf("cache", "decision") }
            .groupBy { it.role to it.model }
            .map { (k, es) -> CacheRow(k.first, k.second, es.size, es.sumOf { it.cached }.toDouble() / es.sumOf { it.input }, es.sumOf { it.input }) }
            .sortedByDescending { it.input }

    /** Why a role's cache hit rate may be low (heuristics shown next to the number). */
    fun cacheHint(r: CacheRow): String? {
        if (r.calls < 5 || r.hitRate >= 0.3) return null
        val m = r.model.lowercase()
        return when {
            r.role in setOf("review", "critic", "router", "evolution") -> "Tek seferlik kısa çağrılar; önbellek beklenmez."
            m.startsWith("gemini-4") -> "Gemini 4 yalnızca örtük önbellek kullanır; istem öneki her çağrıda aynı kalmalı."
            m.startsWith("deepseek") -> "DeepSeek otomatik önbellek: öneki değiştiren sık geçmiş sıkıştırması isabeti düşürür (Ayarlar > tutulan tur sayısı)."
            m.contains("claude") || m.contains("anthropic") -> "Anthropic önbelleği 5 dakikada düşer; adımlar arası uzun bekleme (CI) isabeti düşürür."
            else -> "Araç listesi veya proje bağlamı sık değişiyorsa önek bozulur; akıllı özellikleri sabit tutmak isabeti artırır."
        }
    }

    fun pct(v: Double) = "%" + String.format(Locale.US, "%.0f", v * 100)
}

/** Watches the prices of the models in use and warns when they change (or a published change is coming). */
object PriceWatch {
    data class Alert(val model: String, val text: String)

    fun snapshot(prices: Map<String, Price>): String = JSONObject().also { o ->
        prices.forEach { (m, p) -> o.put(m, JSONArray().put(p.input).put(p.output)) }
    }.toString()

    fun parse(json: String): Map<String, Pair<Double, Double>> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getDouble(0) to it.getDouble(1) } }
    }.getOrDefault(emptyMap())

    /** Changes of 10% or more since the last snapshot, and scheduled changes within [horizonDays]. */
    fun alerts(old: Map<String, Pair<Double, Double>>, now: Map<String, Price>, nowMs: Long = System.currentTimeMillis(), horizonDays: Int = 45): List<Alert> {
        val out = ArrayList<Alert>()
        for ((m, p) in now) {
            val (i, o) = old[m] ?: continue
            fun ch(a: Double, b: Double) = if (a <= 0) 0.0 else (b - a) / a
            val ci = ch(i, p.input); val co = ch(o, p.output)
            if (kotlin.math.abs(ci) >= 0.1 || kotlin.math.abs(co) >= 0.1) {
                out += Alert(m, "$m fiyatı değişti: ${fmt(i)}/${fmt(o)} → ${fmt(p.input)}/${fmt(p.output)} $ (1M girdi/çıktı)")
            }
        }
        for (m in now.keys) Pricing.upcoming(m, nowMs)?.let { s ->
            val days = (s.effectiveMs - nowMs) / 86_400_000L
            if (days in 0..horizonDays) out += Alert(m, "$m için ${days} gün sonra fiyat artışı bildirildi: ${fmt(s.price.input)}/${fmt(s.price.output)} $ (kaynak: ${s.source})")
        }
        return out.distinctBy { it.text }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}

/** Output sizes per role, so the output cap fits real answers (thinking included); a cut-off answer is retried larger. */
class OutputStats(private val file: File) {
    @Synchronized fun add(role: String, tokens: Long) {
        if (tokens <= 0) return
        val o = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val a = o.optJSONArray(role) ?: JSONArray()
        a.put(tokens)
        while (a.length() > WINDOW) a.remove(0)
        o.put(role, a)
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }

    @Synchronized fun samples(role: String): List<Long> = runCatching {
        val a = JSONObject(file.readText()).getJSONArray(role); (0 until a.length()).map { a.getLong(it) }
    }.getOrDefault(emptyList())

    /** The learned cap: 1.5 × the 95th percentile, never above [configured], never below [floor]; null until enough samples. */
    fun cap(role: String, configured: Int, floor: Int = 2_000): Int? = learned(samples(role), configured, floor)

    companion object {
        const val WINDOW = 60
        const val MIN_SAMPLES = 15

        fun learned(samples: List<Long>, configured: Int, floor: Int): Int? {
            if (samples.size < MIN_SAMPLES) return null
            val s = samples.sorted()
            val p95 = s[((s.size - 1) * 0.95).toInt()]
            return (p95 * 1.5).toInt().coerceIn(minOf(floor, configured), configured)
        }
    }
}
