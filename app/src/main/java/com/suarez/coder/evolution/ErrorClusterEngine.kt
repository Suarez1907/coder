package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class ClusterStatus { COLLECTING, READY_FOR_PR, PR_OPEN, RESOLVED, DISMISSED }

data class ClusterThresholds(val minSessions: Int = 2, val minCostUsd: Double = 0.05, val minFailedRounds: Int = 3)

/**
 * One repeating failure. A self-PR is allowed only while [status] is [ClusterStatus.READY_FOR_PR].
 * Dismissed and already-open clusters are not re-armed by new traces.
 */
data class ErrorCluster(
    val clusterKey: String,
    val category: String,
    val distinctSessionIds: List<String>,
    val occurrenceCount: Int,
    val estimatedWastedCostUsd: Double,
    val sampleTraces: List<String>,
    val status: ClusterStatus,
    val failedFixRounds: Int = 0,
    val traceIds: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", clusterKey).put("cat", category).put("sessions", JSONArray(distinctSessionIds))
        .put("n", occurrenceCount).put("usd", estimatedWastedCostUsd).put("samples", JSONArray(sampleTraces))
        .put("status", status.name).put("rounds", failedFixRounds).put("traces", JSONArray(traceIds))

    companion object {
        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        fun fromJson(o: JSONObject) = ErrorCluster(
            o.getString("key"), o.optString("cat"), strings(o.optJSONArray("sessions")), o.optInt("n"),
            o.optDouble("usd", 0.0), strings(o.optJSONArray("samples")),
            runCatching { ClusterStatus.valueOf(o.optString("status")) }.getOrDefault(ClusterStatus.COLLECTING),
            o.optInt("rounds"), strings(o.optJSONArray("traces")),
        )
    }
}

class ErrorClusterEngine(private val file: File) {
    @Synchronized fun all(): List<ErrorCluster> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { runCatching { ErrorCluster.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    /** Folds one trace into its clusters. The same trace id is not counted twice. */
    @Synchronized fun ingest(trace: SessionTrace, thresholds: ClusterThresholds = ClusterThresholds()): List<ErrorCluster> {
        val hits = hitsOf(trace)
        if (hits.isEmpty()) return all()
        val share = trace.totalCostUsd / hits.size
        var clusters = all()
        for (hit in hits) {
            val prev = clusters.firstOrNull { it.clusterKey == hit.key }
            if (prev != null && trace.id in prev.traceIds) continue
            val sessions = ((prev?.distinctSessionIds ?: emptyList()) + trace.sessionId).filter { it.isNotBlank() }.distinct()
            val samples = ((prev?.sampleTraces ?: emptyList()) + hit.sample).filter { it.isNotBlank() }.distinct().takeLast(3)
            val next = ErrorCluster(
                clusterKey = hit.key,
                category = hit.category,
                distinctSessionIds = sessions,
                occurrenceCount = (prev?.occurrenceCount ?: 0) + hit.occurrences,
                estimatedWastedCostUsd = (prev?.estimatedWastedCostUsd ?: 0.0) + share,
                sampleTraces = samples,
                status = prev?.status ?: ClusterStatus.COLLECTING,
                failedFixRounds = (prev?.failedFixRounds ?: 0) + hit.rounds,
                traceIds = ((prev?.traceIds ?: emptyList()) + trace.id).distinct().takeLast(40),
            )
            val status = when (next.status) {
                ClusterStatus.DISMISSED, ClusterStatus.PR_OPEN, ClusterStatus.RESOLVED -> next.status
                else -> if (ready(next, thresholds) && hasRealSnippet(next)) ClusterStatus.READY_FOR_PR else ClusterStatus.COLLECTING
            }
            val saved = next.copy(status = status)
            clusters = clusters.filter { it.clusterKey != hit.key } + saved
        }
        save(clusters)
        return clusters
    }

    @Synchronized fun dismiss(key: String) = mark(key, ClusterStatus.DISMISSED)

    @Synchronized fun mark(key: String, status: ClusterStatus) {
        val next = all().map { if (it.clusterKey == key) it.copy(status = status) else it }
        save(next)
    }

    fun forSession(sessionId: String) = all().filter { sessionId in it.distinctSessionIds }

    fun ready() = all().filter { it.status == ClusterStatus.READY_FOR_PR }

    fun shouldOpen(cluster: ErrorCluster) = cluster.status == ClusterStatus.READY_FOR_PR

    private fun save(list: List<ErrorCluster>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString())
    }

    companion object {
        fun ready(c: ErrorCluster, t: ClusterThresholds) =
            c.distinctSessionIds.size >= t.minSessions || c.estimatedWastedCostUsd >= t.minCostUsd || c.failedFixRounds >= t.minFailedRounds

        data class Hit(val key: String, val category: String, val occurrences: Int, val rounds: Int, val sample: String)

        fun hitsOf(t: SessionTrace): List<Hit> {
            val out = ArrayList<Hit>()
            val lang = languageOf(t)
            if (t.diffErrorCount > 0 || t.searchReplaceTier2Count > 0 || t.searchReplaceTier3Count > 0) {
                val n = maxOf(t.diffErrorCount, 1)
                out += Hit("search_replace_miss:$lang", "search_replace", n, maxOf(t.diffErrorCount, t.searchReplaceTier2Count + t.searchReplaceTier3Count, 1), sample(t, "search_replace"))
            }
            if (t.preflightBlocksCount > 0) {
                val rule = Regex("ön kontrol \\(([^)]+)\\)").find(t.diffErrorSamples.joinToString(" "))?.groupValues?.get(1) ?: "unknown"
                out += Hit("preflight_false_positive:$rule", "preflight", t.preflightBlocksCount, t.preflightBlocksCount, sample(t, "preflight"))
            }
            if (t.ciFailureCount > 0) {
                val trunc = t.ciSignatures.any { s -> s.contains("kısaltıldı") || s.contains("truncat", true) }
                val key = if (trunc) "ci_log_truncation_miss" else "ci_failure:" + slug(t.ciSignatures.firstOrNull() ?: "unknown")
                out += Hit(key, "ci", t.ciFailureCount, t.ciFailureCount, sample(t, "log_filter"))
            }
            if (t.oscillationEscalationsCount > 0) {
                val cat = t.route?.substringBefore('_')?.lowercase() ?: "general"
                out += Hit("oscillation_loop:$cat", "oscillation", t.oscillationEscalationsCount, t.oscillationEscalationsCount, sample(t, "search_replace"))
            }
            if (t.prePushGateWarningsCount > 0) {
                out += Hit("prepush_gate:warning", "prepush", t.prePushGateWarningsCount, t.prePushGateWarningsCount, sample(t, "preflight"))
            }
            if (t.escalations.any { e -> e.contains("salt okunur", true) || e.contains("READ_ONLY") || e.contains("yetki", true) }) {
                out += Hit("readonly_mismatch", "access", 1, 1, sample(t, "router_access"))
            }
            if (t.upgradedToArchitect) {
                val cat = (t.route ?: "simple").lowercase()
                out += Hit("router_underestimation:$cat", "router", 1, 1, sample(t, "router_access"))
            }
            return out
        }

        fun languageOf(t: SessionTrace): String {
            val blob = (t.diffErrorSamples + t.steps + listOf(t.request)).joinToString(" ").lowercase()
            return when {
                ".gd" in blob || "gdscript" in blob -> "gdscript"
                "blender" in blob || blob.contains(".py") -> "python"
                ".yml" in blob || ".yaml" in blob || "gradle" in blob -> "gradle"
                else -> "kt"
            }
        }

        fun hasRealSnippet(c: ErrorCluster) = c.sampleTraces.any { EvalGate.caseFromSnippet(it) != null }

        fun sample(t: SessionTrace, kind: String): String {
            val window = t.failureWindows.firstOrNull { EvalGate.caseFromSnippet(it)?.optString("kind") == kind }
            if (window != null) return window.take(4_000)
            val raw = (t.diffErrorSamples + t.ciSignatures + t.escalations).firstOrNull { it.isNotBlank() } ?: t.request
            return anonymize(raw).take(180)
        }

        fun anonymize(text: String) = text
            .replace(Regex("ghp_[A-Za-z0-9]+"), "<token>")
            .replace(Regex("github_pat_[A-Za-z0-9_]+"), "<token>")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<email>")
            .replace(Regex("(/Users|/home)/[^\\s]+"), "<path>")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun slug(text: String) = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifBlank { "unknown" }

        /** Files a self-PR may try to change for this cluster. Never the evolution package or settings. */
        fun filesFor(key: String): List<String> = when {
            key.startsWith("search_replace") -> listOf("app/src/main/java/com/suarez/coder/diff/SearchReplace.kt")
            key.startsWith("preflight") -> listOf("app/src/main/java/com/suarez/coder/harness/PreFlightValidator.kt")
            key.startsWith("ci_") -> listOf("app/src/main/java/com/suarez/coder/ci/LogFilter.kt")
            key.startsWith("router_") -> listOf("app/src/main/java/com/suarez/coder/decision/SmartRouter.kt")
            key.startsWith("readonly") -> listOf("app/src/main/java/com/suarez/coder/agent/ReadOnlyMismatchRecovery.kt")
            key.startsWith("oscillation") -> listOf("app/src/main/java/com/suarez/coder/agent/Agent.kt")
            key.startsWith("prepush") -> listOf("app/src/main/java/com/suarez/coder/agent/CommitGuards.kt")
            else -> emptyList()
        }

        fun improvementFor(c: ErrorCluster) = SelfImprovement(
            title = c.clusterKey.take(80),
            problem = "Tekrarlayan hata kümesi ${c.clusterKey}: ${c.distinctSessionIds.size} seans, ${c.occurrenceCount} kez, " +
                "yaklaşık $${String.format(Locale.US, "%.4f", c.estimatedWastedCostUsd)} boşa harcama.\n" +
                c.sampleTraces.joinToString("\n"),
            files = filesFor(c.clusterKey),
            topic = c.clusterKey,
            repeatable = true,
        )
    }
}
