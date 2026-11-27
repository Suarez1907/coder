package com.suarez.coder

import com.suarez.coder.cost.CacheTtlTracker
import com.suarez.coder.cost.FinOpsArbitrageEngine
import com.suarez.coder.cost.PriorityTier
import com.suarez.coder.evolution.EvolutionWorker
import com.suarez.coder.evolution.RepoSentinelWorker
import com.suarez.coder.evolution.SkillSynthesizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinOpsArbitrageTest {
    @Test fun backgroundJobsAreTaggedFlexAndInteractiveIsNot() {
        assertEquals(PriorityTier.BACKGROUND_FLEX, EvolutionWorker.priorityTier)
        assertEquals(PriorityTier.BACKGROUND_FLEX, SkillSynthesizer.priorityTier)
        assertEquals(PriorityTier.BACKGROUND_FLEX, RepoSentinelWorker.priorityTier)
        assertEquals(PriorityTier.BACKGROUND_FLEX, FinOpsArbitrageEngine.tierOf("EvolutionWorker"))
        assertEquals(PriorityTier.BACKGROUND_FLEX, FinOpsArbitrageEngine.tierOf("SkillSynthesizer"))
        assertEquals(PriorityTier.BACKGROUND_FLEX, FinOpsArbitrageEngine.tierOf("RepoSentinelWorker"))
        assertEquals(PriorityTier.INTERACTIVE, FinOpsArbitrageEngine.tierOf("Agent"))
    }

    @Test fun unsupportedProviderStaysOnTheStandardPath() {
        val standard = JSONObject().put("model", "deepseek-chat")
        val route = FinOpsArbitrageEngine.route("deepseek", "deepseek-chat", PriorityTier.BACKGROUND_FLEX, enabled = true)
        assertFalse(route.supported)
        assertNull(route.serviceTier)
        assertFalse(route.priceSort)
        FinOpsArbitrageEngine.apply(standard, route)
        assertFalse(standard.has("service_tier"))
        assertFalse(standard.has("provider"))

        val rejected = JSONObject().put("model", "openai/gpt-5").put("service_tier", "flex")
            .put("provider", JSONObject().put("sort", "price"))
        assertTrue(FinOpsArbitrageEngine.stripUnsupported(rejected, "service_tier is not supported for this model"))
        assertFalse(rejected.has("service_tier"))
        assertFalse(rejected.has("provider"))
        assertEquals("openai/gpt-5", rejected.getString("model"))
        assertFalse(FinOpsArbitrageEngine.stripUnsupported(JSONObject().put("model", "x"), "temperature is fixed"))
    }

    @Test fun openRouterBackgroundCanSortByPriceWithoutChangingTheModel() {
        val route = FinOpsArbitrageEngine.route("openrouter", "anthropic/claude-sonnet-4.5", PriorityTier.BACKGROUND_FLEX, true)
        assertTrue(route.supported)
        assertTrue(route.priceSort)
        assertNull(route.serviceTier)
        val body = JSONObject().put("model", "anthropic/claude-sonnet-4.5")
        FinOpsArbitrageEngine.apply(body, route)
        assertEquals("price", body.getJSONObject("provider").getString("sort"))
        assertEquals("anthropic/claude-sonnet-4.5", body.getString("model"))
        val off = FinOpsArbitrageEngine.route("gemini", "gemini-2.5-flash", PriorityTier.INTERACTIVE, true)
        assertFalse(off.supported)
    }

    @Test fun cacheTtlWarmAndColdTransitions() {
        val t = CacheTtlTracker()
        assertEquals(CacheTtlTracker.State.NEVER, t.view("openrouter", 0).state)
        assertEquals(300, FinOpsArbitrageEngine.defaultTtlSec("anthropic", explicit = false))
        assertEquals(300, FinOpsArbitrageEngine.defaultTtlSec("openrouter", explicit = false))
        assertEquals(600, FinOpsArbitrageEngine.defaultTtlSec("gemini", explicit = true, explicitMinutes = 10))
        t.note("openrouter", 300, now = 0)
        assertEquals(300, t.warmRemainingSec("openrouter", 0))
        assertEquals(CacheTtlTracker.State.WARM, t.view("openrouter", 279_000).state)
        assertTrue(t.warmRemainingSec("openrouter", 279_000) > 20)
        assertEquals(CacheTtlTracker.State.COLD, t.view("openrouter", 280_000).state)
        assertEquals(20, t.warmRemainingSec("openrouter", 280_000))
        val warm = FinOpsArbitrageEngine.pace(t, "openrouter", now = 100_000)
        assertTrue(warm.minimizeWaits)
        assertFalse(warm.preferCompactAndTurn0)
        assertEquals(0L, warm.interStepDelayMs)
        val cold = FinOpsArbitrageEngine.pace(t, "openrouter", now = 400_000)
        assertFalse(cold.minimizeWaits)
        assertTrue(cold.preferCompactAndTurn0)
        assertEquals("3:40", CacheTtlTracker.formatClock(220))
        assertEquals("Önbellek %88 · Sıcak 3:40", CacheTtlTracker.label(88, t.view("openrouter", 0).copy(
            state = CacheTtlTracker.State.WARM, remainingSec = 220,
        )))
        assertNull(CacheTtlTracker.label(88, CacheTtlTracker.View(CacheTtlTracker.State.COLD, 10, 300, 3)))
    }
}
