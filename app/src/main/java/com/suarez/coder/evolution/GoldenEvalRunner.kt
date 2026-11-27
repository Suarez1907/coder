package com.suarez.coder.evolution

import com.suarez.coder.ci.LogFilter
import com.suarez.coder.decision.Route
import com.suarez.coder.decision.RouterAccessPolicy
import com.suarez.coder.diff.SearchReplace
import com.suarez.coder.harness.PreFlightValidator
import org.json.JSONArray
import org.json.JSONObject

/** Deterministic, offline checks behind the golden fixtures. No network and no model calls. */
object GoldenEvalRunner {
    data class RunResult(val passed: Int, val total: Int, val failures: List<String>) {
        val ok get() = failures.isEmpty() && (total == 0 || passed == total)
    }

    fun run(component: String, jsonText: String): RunResult {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return RunResult(0, 0, listOf("json"))
        val cases = root.optJSONArray("cases") ?: JSONArray()
        val failures = ArrayList<String>()
        for (i in 0 until cases.length()) {
            val c = cases.optJSONObject(i) ?: continue
            val id = c.optString("id").ifBlank { "case-$i" }
            val why = runCatching { check(component, c) }.getOrElse { it.message ?: "exception" }
            if (why != null) failures += id
        }
        val total = cases.length()
        return RunResult(total - failures.size, total, failures)
    }

    /** Null when the case passes. */
    fun check(component: String, c: JSONObject): String? = when (component) {
        "search_replace" -> checkSearch(c)
        "preflight" -> checkPreflight(c)
        "log_filter" -> checkLog(c)
        "router_access" -> checkRouter(c)
        else -> "unknown component"
    }

    private fun checkSearch(c: JSONObject): String? {
        val content = c.optString("content")
        val search = c.optString("search")
        val replace = c.optString("replace")
        val result = if (c.optBoolean("applyFile")) {
            val res = SearchReplace.applyToFile(content, listOf(SearchReplace.Block(c.optString("path", "F.kt"), search, replace, 1)))
            if (res.failures.isEmpty()) SearchReplace.Match.Ok(res.content) else SearchReplace.Match.Err(res.failures.first().reason)
        } else SearchReplace.replaceOnce(content, search, replace)
        val expect = c.optString("expect", "ok")
        return when (expect) {
            "err" -> if (result is SearchReplace.Match.Err) null else "expected rejection"
            else -> {
                val ok = result as? SearchReplace.Match.Ok ?: return "expected a match"
                val text = ok.text
                val contains = c.optString("contains")
                val absent = c.optString("notContains")
                when {
                    contains.isNotBlank() && !text.contains(contains) -> "missing '$contains'"
                    absent.isNotBlank() && text.contains(absent) -> "still contains '$absent'"
                    else -> null
                }
            }
        }
    }

    private fun checkPreflight(c: JSONObject): String? {
        val findings = PreFlightValidator.check(c.optString("path", "A.kt"), c.optString("before").ifEmpty { null }, c.optString("after"))
        val rules = c.optJSONArray("expectRules")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val got = findings.map { it.rule }.toSet()
        return if (got == rules) null else "expected $rules but got $got"
    }

    private fun checkLog(c: JSONObject): String? {
        val extracted = LogFilter.extract(c.optString("log"))
        val must = c.optJSONArray("mustContain")
        val not = c.optJSONArray("mustNotContain")
        if (must != null) for (i in 0 until must.length()) {
            val needle = must.optString(i)
            if (needle.isNotBlank() && !extracted.contains(needle)) return "missing '$needle'"
        }
        if (not != null) for (i in 0 until not.length()) {
            val needle = not.optString(i)
            if (needle.isNotBlank() && extracted.contains(needle)) return "unexpected '$needle'"
        }
        return null
    }

    private fun checkRouter(c: JSONObject): String? {
        val forced = c.optString("forcedRoute").takeIf { it.isNotBlank() }?.let { name -> Route.entries.firstOrNull { it.name == name } }
        val architect = c.optString("architect")
        val implementer = c.optString("implementer")
        val out = RouterAccessPolicy.decide(c.optString("request"), architect, implementer, forced)
        val expectRoute = c.optString("expectRoute")
        val expectPhase = c.optString("expectPhase")
        val expectArchitect = c.optString("expectArchitect").ifBlank { architect }
        return when {
            expectRoute.isNotBlank() && out.route.name != expectRoute -> "route ${out.route.name}"
            expectPhase.isNotBlank() && out.phase != expectPhase -> "phase ${out.phase}"
            out.architectModel != expectArchitect -> "architect changed"
            out.architectModel == implementer && architect != implementer -> "architect replaced by implementer"
            else -> null
        }
    }
}
