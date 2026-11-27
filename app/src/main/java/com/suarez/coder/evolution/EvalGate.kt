package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Score shown on the self-PR and on the pending card. A single old failure blocks the pull request. */
data class EvalScorecard(val passed: Int, val total: Int, val added: Int, val regressions: Int) {
    val ok get() = regressions == 0 && total > 0 && passed == total

    fun line(): String = "Golden Eval: $passed/$total (+$added yeni vaka, $regressions gerileme)"

    fun chip(): String = "Eval: $passed/$total · +$added yeni test · $regressions gerileme"

    companion object {
        val EMPTY = EvalScorecard(0, 0, 0, 0)
    }
}

/**
 * Before a self-PR is opened, the failing cluster becomes one more golden case. Existing case ids must still pass
 * on the current runner; CI then runs the whole suite, including the new case, on the branch.
 */
object EvalGate {
    data class Prepared(val path: String, val json: String, val component: String, val baselineIds: Set<String>, val card: EvalScorecard)

    fun fileFor(clusterKey: String): Pair<String, String> {
        val name = when {
            clusterKey.startsWith("search_replace") -> "search_replace_evals.json"
            clusterKey.startsWith("preflight") -> "preflight_evals.json"
            clusterKey.startsWith("ci_") -> "log_filter_evals.json"
            else -> "router_access_evals.json"
        }
        val component = name.removeSuffix("_evals.json")
        return "app/src/test/resources/evals/$name" to component
    }

    fun caseFrom(cluster: ErrorCluster): JSONObject {
        val id = "reg-" + ErrorClusterEngine.slug(cluster.clusterKey)
        val sample = ErrorClusterEngine.anonymize(cluster.sampleTraces.firstOrNull().orEmpty()).ifBlank { cluster.clusterKey }
        val (path, component) = fileFor(cluster.clusterKey)
        val o = when (component) {
            "search_replace" -> JSONObject()
                .put("sample", sample)
                .put("content", "fun reg() {\n    val value = 1 // $sample\n}\n")
                .put("search", "val value = 1")
                .put("replace", "val value = 2")
                .put("expect", "ok")
                .put("contains", "val value = 2")
            "preflight" -> JSONObject()
                .put("sample", sample)
                .put("path", "Reg.kt")
                .put("before", "fun ok() {\n    return 1\n}\n")
                .put("after", "fun ok(s: String) {\n    // $sample { }\n    val t = \"{ }\"\n    return s\n}\n")
                .put("expectRules", JSONArray())
            "log_filter" -> {
                val line = if (sample.startsWith("e:") || sample.contains("Error") || sample.contains("ERROR")) sample
                else "e: regression: $sample"
                JSONObject()
                    .put("sample", sample)
                    .put("log", "Downloading dependencies\n".repeat(8) + line + "\nBUILD FAILED\n")
                    .put("mustContain", JSONArray().put(line))
                    .put("mustNotContain", JSONArray().put("(Belirgin hata satırı bulunamadı"))
            }
            else -> JSONObject()
                .put("sample", sample)
                .put("request", "bu fonksiyon ne işe yarıyor?")
                .put("architect", "gemini/gemini-2.5-pro")
                .put("implementer", "gemini/gemini-2.5-flash")
                .put("expectRoute", "QUESTION_ONLY")
                .put("expectPhase", "READ_ONLY")
                .put("expectArchitect", "gemini/gemini-2.5-pro")
        }
        o.put("id", id)
        return o
    }

    fun merge(jsonText: String, component: String, caseObj: JSONObject): String {
        val root = if (jsonText.isBlank()) JSONObject().put("component", component).put("cases", JSONArray())
        else JSONObject(jsonText)
        if (!root.has("component")) root.put("component", component)
        val cases = root.optJSONArray("cases") ?: JSONArray()
        val id = caseObj.optString("id")
        val next = JSONArray()
        for (i in 0 until cases.length()) {
            val c = cases.optJSONObject(i) ?: continue
            if (c.optString("id") != id) next.put(c)
        }
        next.put(caseObj)
        root.put("cases", next)
        return root.toString(2)
    }

    fun ids(jsonText: String): Set<String> {
        val cases = runCatching { JSONObject(jsonText).optJSONArray("cases") }.getOrNull() ?: return emptySet()
        return (0 until cases.length()).mapNotNull { cases.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }.toSet()
    }

    /**
     * Runs the current fixture, refuses the PR when any existing id fails, then appends the cluster's case.
     * Returns null when an old case regressed.
     */
    fun prepare(cluster: ErrorCluster, existingJson: String?): Prepared? {
        val (path, component) = fileFor(cluster.clusterKey)
        val existing = existingJson?.takeIf { it.isNotBlank() } ?: JSONObject().put("component", component).put("cases", JSONArray()).toString()
        val before = GoldenEvalRunner.run(component, existing)
        val beforeIds = ids(existing)
        val oldFailures = before.failures.filter { it in beforeIds }
        if (oldFailures.isNotEmpty()) return null
        val caseObj = caseFrom(cluster)
        val updated = merge(existing, component, caseObj)
        val after = GoldenEvalRunner.run(component, updated)
        val regressions = after.failures.filter { it in beforeIds }
        if (regressions.isNotEmpty()) return null
        val card = EvalScorecard(
            passed = after.passed,
            total = after.total,
            added = 1,
            regressions = 0,
        )
        if (!card.ok) return null
        return Prepared(path, updated, component, beforeIds, card)
    }

    fun formatUsd(v: Double) = String.format(Locale.US, "%.4f", v)
}
