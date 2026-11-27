package com.suarez.coder.evals

import com.suarez.coder.evolution.GoldenEvalRunner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline golden suite: four fixtures, no API calls. */
class GoldenEvalSuiteTest {
    @Test fun everyFixturePassesAndTheSuiteHasAtLeastTwentyCases() {
        var total = 0
        for (name in FILES) {
            val text = resource(name)
            val root = JSONObject(text)
            val component = root.getString("component")
            val result = GoldenEvalRunner.run(component, text)
            assertTrue("$name failures: ${result.failures}", result.failures.isEmpty())
            assertEquals(result.total, result.passed)
            total += result.total
        }
        assertTrue("expected at least 20 golden cases, was $total", total >= 20)
    }

    private fun resource(name: String): String {
        val stream = requireNotNull(javaClass.classLoader).getResourceAsStream("evals/$name")
            ?: error("missing evals/$name")
        return stream.bufferedReader().readText()
    }

    companion object {
        val FILES = listOf(
            "search_replace_evals.json",
            "preflight_evals.json",
            "log_filter_evals.json",
            "router_access_evals.json",
        )
    }
}
