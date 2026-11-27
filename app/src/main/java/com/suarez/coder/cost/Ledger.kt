package com.suarez.coder.cost

import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** One billed call (or cache storage) kept permanently, independent of chat sessions. */
data class LedgerEntry(
    val at: Long,
    val session: String,
    val repo: String,
    val role: String,
    val model: String,
    val input: Long,
    val cached: Long,
    val output: Long,
    val usd: Double,
)

data class CostBreakdown(
    val totalUsd: Double,
    val monthUsd: Double,
    /** repo → (usd, cache hit rate or null) */
    val byRepo: List<Triple<String, Double, Double?>>,
    /** role group → usd */
    val byRole: List<Pair<String, Double>>,
    /** "2026-11" → usd, newest first */
    val byMonth: List<Pair<String, Double>>,
    val cacheHitRate: Double?,
)

/** Append-only cost ledger (one JSON object per line). */
class Ledger(private val file: File) {
    @Synchronized
    fun append(entries: List<LedgerEntry>) {
        if (entries.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(entries.joinToString("") { e ->
            JSONObject().put("at", e.at).put("s", e.session).put("repo", e.repo).put("role", e.role).put("model", e.model)
                .put("in", e.input).put("c", e.cached).put("out", e.output).put("usd", e.usd).toString() + "\n"
        })
    }

    @Synchronized
    fun readAll(): List<LedgerEntry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        runCatching {
            val o = JSONObject(line)
            LedgerEntry(o.getLong("at"), o.optString("s"), o.optString("repo"), o.optString("role"), o.optString("model"),
                o.optLong("in"), o.optLong("c"), o.optLong("out"), o.optDouble("usd", 0.0))
        }.getOrNull()
    }

    /** Keys already recorded, so a record is never counted twice. */
    fun keys(): Set<String> = readAll().mapTo(HashSet()) { key(it.session, it.at, it.role, it.model) }

    companion object {
        fun key(session: String, at: Long, role: String, model: String) = "$session|$at|$role|$model"

        fun roleGroup(role: String) = when (role) {
            "architect", "planner" -> "Mimar"
            "editor", "escalation" -> "Uygulayıcı"
            "reviewer" -> "İnceleyici"
            "evolution" -> "Öz-gelişim"
            "router", "decision" -> "Yönlendirici"
            "denetim", "audit", "pre_push_gate" -> "Denetim / Karar"
            "summary" -> "Özet"
            "cache" -> "Önbellek depolama"
            else -> "Ajan"
        }

        fun monthKey(at: Long): String {
            if (at <= 0L) return "Önceki kayıtlar"
            val c = Calendar.getInstance().apply { timeInMillis = at }
            return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }

        fun aggregate(entries: List<LedgerEntry>, now: Long = System.currentTimeMillis()): CostBreakdown {
            val month = monthKey(now)
            val validMonth = now > 0L
            fun hit(list: List<LedgerEntry>): Double? {
                val calls = list.filter { it.role != "cache" && it.role != "decision" }
                val input = calls.sumOf { it.input }
                return if (input <= 0) null else calls.sumOf { it.cached }.toDouble() / input
            }
            return CostBreakdown(
                totalUsd = entries.sumOf { it.usd },
                monthUsd = entries.filter { it.at > 0L && validMonth && monthKey(it.at) == month }.sumOf { it.usd },
                byRepo = entries.groupBy { it.repo.ifBlank { "Genel" } }
                    .map { (r, l) -> Triple(r, l.sumOf { it.usd }, hit(l)) }.sortedByDescending { it.second },
                byRole = entries.groupBy { roleGroup(it.role) }.map { (g, l) -> g to l.sumOf { it.usd } }.sortedByDescending { it.second },
                byMonth = entries.groupBy { monthKey(it.at) }.map { (m, l) -> m to l.sumOf { it.usd } }
                    .sortedWith(compareBy<Pair<String, Double>> { it.first == "Önceki kayıtlar" }.thenByDescending { it.first }),
                cacheHitRate = hit(entries),
            )
        }
    }
}
