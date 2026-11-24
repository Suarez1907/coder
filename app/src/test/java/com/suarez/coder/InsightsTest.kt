package com.suarez.coder

import com.suarez.coder.agent.Agent
import com.suarez.coder.cost.LedgerEntry
import com.suarez.coder.cost.ModelReport
import com.suarez.coder.cost.OutputStats
import com.suarez.coder.cost.Price
import com.suarez.coder.cost.PriceWatch
import com.suarez.coder.cost.Pricing
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.llm.LlmException
import com.suarez.coder.queue.QueueWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InsightsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun t(model: String, ok: Boolean, cost: Double, ci: Int = 1, ciFail: Int = 0) = SessionTrace("t${Math.random()}", "s", "me/app", 1000, "x",
        listOf(model), 4, null, cost, if (ok) 0 else 1, emptyList(), ciFail, emptyList(), emptyList(), null, false, false, ciRuns = ci)

    @Test fun reportAndSuggestedChain() {
        val traces = List(4) { t("flash", it < 2, 0.01) } + List(4) { t("v4-pro", it < 3, 0.03) } + List(4) { t("sonnet", true, 0.10) } +
            List(4) { t("cheap-bad", false, 0.001) } + listOf(t("rare", true, 0.5))
        val rows = ModelReport.rows(traces)
        assertEquals(0.5, rows.first { it.model == "flash" }.successRate, 1e-9)
        assertEquals("rare", rows.last().model) // under MIN_TASKS goes last
        // Stronger than the editor, cheapest first.
        assertEquals(listOf("v4-pro", "sonnet"), ModelReport.suggestedChain(rows, "flash"))
        assertEquals(1.0, rows.first { it.model == "sonnet" }.ciFirstTry!!, 1e-9)
    }

    @Test fun cacheHits() {
        val e = List(6) { LedgerEntry(1000L + it, "s", "r", "agent", "deepseek-chat", 1000, 100, 50, 0.001) } +
            List(6) { LedgerEntry(1000L + it, "s", "r", "architect", "claude-sonnet-5.5", 1000, 900, 50, 0.001) }
        val rows = ModelReport.cacheRows(e, 0)
        val ds = rows.first { it.model == "deepseek-chat" }
        assertEquals(0.1, ds.hitRate, 1e-9)
        assertNotNull(ModelReport.cacheHint(ds))
        assertNull(ModelReport.cacheHint(rows.first { it.model.startsWith("claude") }))
    }

    @Test fun priceWatch() {
        val dec = java.time.LocalDate.of(2026, 12, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val old = mapOf("deepseek-chat" to (0.28 to 0.42), "gemini-2.5-flash" to (0.30 to 2.50))
        val now = mapOf("deepseek-chat" to Price(0.14, 0.28, 0.01), "gemini-2.5-flash" to Price(0.30, 2.50, 0.03), "gemini-3.8-flash" to Price(0.75, 3.75, 0.075))
        val alerts = PriceWatch.alerts(old, now, nowMs = dec)
        assertTrue(alerts.any { it.model == "deepseek-chat" })
        assertFalse(alerts.any { it.model == "gemini-2.5-flash" })
        assertTrue(alerts.any { it.model == "gemini-3.8-flash" && it.text.contains("31 gün") }) // announced increase
        assertEquals(mapOf("deepseek-chat" to (0.14 to 0.28)), PriceWatch.parse(PriceWatch.snapshot(mapOf("deepseek-chat" to Price(0.14, 0.28, 0.01)))))
    }

    @Test fun scheduledPriceTakesEffect() {
        val before = java.time.LocalDate.of(2026, 12, 31).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertNotNull(Pricing.upcoming("gemini/gemini-3.8-flash", before))
        assertNull(Pricing.upcoming("gemini-3.8-flash", before + 2 * 86_400_000L))
        // The table switches by itself once the date has passed (current date is before it in these tests' time frame or after: both consistent).
        val p = Pricing.defaultFor("gemini-3.8-flash")
        assertTrue(p.input == 0.75 || p.input == 1.50)
    }

    @Test fun learnedOutputCap() {
        assertNull(OutputStats.learned(List(5) { 1000L }, 16_000, 2_000)) // too few samples
        assertEquals(3_000, OutputStats.learned(List(20) { 2000L }, 16_000, 2_000))
        assertEquals(16_000, OutputStats.learned(List(20) { 20_000L }, 16_000, 2_000)) // never above the setting
        assertEquals(2_000, OutputStats.learned(List(20) { 100L }, 16_000, 2_000)) // floor
        val s = OutputStats(tmp.newFile("o.json").also { it.delete() })
        repeat(70) { s.add("agent", 1000L + it) }
        assertEquals(OutputStats.WINDOW, s.samples("agent").size)
    }

    @Test fun queueWindow() {
        assertEquals(0, QueueWindow.minutesUntil(-1, -1, 10, 0))
        assertEquals(0, QueueWindow.minutesUntil(1, 7, 3, 30))
        assertEquals(5 * 60 + 30, QueueWindow.minutesUntil(1, 7, 19, 30)) // 19:30 → 01:00
        assertEquals(0, QueueWindow.minutesUntil(22, 6, 23, 0)) // wraps midnight
        assertEquals(0, QueueWindow.minutesUntil(22, 6, 2, 0))
        assertEquals(60, QueueWindow.minutesUntil(22, 6, 21, 0))
    }

    @Test fun transientErrors() {
        assertTrue(Agent.isTransient(LlmException("OpenRouter hatası (503): overloaded")))
        assertTrue(Agent.isTransient(LlmException("Gemini hatası (429): RESOURCE_EXHAUSTED")))
        assertTrue(Agent.isTransient(LlmException("DeepSeek bağlantı hatası (3 yeniden denemeden sonra): reset")))
        assertFalse(Agent.isTransient(LlmException("Gemini hatası (400): invalid argument")))
        assertFalse(Agent.isTransient(LlmException("Gemini API anahtarı girilmemiş.")))
    }
}
