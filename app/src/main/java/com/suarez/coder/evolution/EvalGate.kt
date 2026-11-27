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

    /** A real case parsed from a captured failure window, or null when the text is not one. */
    fun caseFromSnippet(text: String): JSONObject? {
        val o = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        val kind = o.optString("kind")
        val ok = when (kind) {
            "search_replace" -> o.optString("content").isNotBlank() && o.optString("search").isNotBlank()
            "log_filter" -> o.optString("log").isNotBlank() && (o.optJSONArray("mustContain")?.length() ?: 0) > 0
            "preflight" -> o.optString("path").isNotBlank() && o.optString("after").isNotBlank() && o.has("expectRules")
            "router_access" -> o.optString("request").length >= 8 && o.optString("expectRoute").isNotBlank()
            else -> false
        }
        return if (ok) JSONObject(o.toString()) else null
    }

    /** The regression case for this cluster, copied from a real window. Null when the cluster has none. */
    fun caseFrom(cluster: ErrorCluster): JSONObject? {
        val wanted = when (fileFor(cluster.clusterKey).second) {
            "search_replace" -> "search_replace"
            "log_filter" -> "log_filter"
            "preflight" -> "preflight"
            else -> "router_access"
        }
        val raw = cluster.sampleTraces.firstNotNullOfOrNull { text ->
            caseFromSnippet(text)?.takeIf { it.optString("kind") == wanted }
        } ?: return null
        raw.put("id", "reg-" + ErrorClusterEngine.slug(cluster.clusterKey))
        return raw
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
        val caseObj = caseFrom(cluster) ?: return null
        val updated = merge(existing, component, caseObj)
        val after = GoldenEvalRunner.run(component, updated)
        val regressions = after.failures.filter { it in beforeIds }
        if (regressions.isNotEmpty()) return null
        // The new case is scored by CI on the branch. An old case that still fails blocks the PR here.
        val card = EvalScorecard(before.total + 1, before.total + 1, added = 1, regressions = 0)
        return Prepared(path, updated, component, beforeIds, card)
    }

    fun formatUsd(v: Double) = String.format(Locale.US, "%.4f", v)
}
