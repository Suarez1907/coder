package com.suarez.coder

import com.suarez.coder.evolution.CostToGreenScorer
import com.suarez.coder.evolution.ImplementerExperimentEngine
import com.suarez.coder.evolution.TaskDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CostToGreenScorerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val domain = TaskDomain.KOTLIN_CORE
    private val architect = "gemini/gemini-2.5-pro"
    private val baseline = "gemini/reliable"
    private val cheap = "gemini/cheap-flaky"

    private fun scorer() = CostToGreenScorer(tmp.newFile("ctg.json"))

    private fun sample(ok: Boolean, firstPass: Boolean, first: Double, heal: Double, osc: Boolean = false, pre: Int = 0) =
        CostToGreenScorer.Sample(ok, firstPass, first, heal, pre, osc, at = 1)

    @Test fun cheapModelThatBreaksCiLosesToTheReliableOne() {
        val s = scorer()
        repeat(4) { s.record(cheap, domain, sample(ok = true, firstPass = false, first = 0.004, heal = 0.04), baseline) }
        s.record(cheap, domain, sample(ok = false, firstPass = false, first = 0.004, heal = 0.04), baseline)
        repeat(5) { s.record(baseline, domain, sample(ok = true, firstPass = true, first = 0.02, heal = 0.0), baseline) }
        val bad = s.score(cheap, domain)
        val good = s.score(baseline, domain)
        assertTrue(bad.costToGreenUsd > good.costToGreenUsd)
        assertTrue(good.efficiencyScore > bad.efficiencyScore)
        val picked = ImplementerExperimentEngine(s).select(
            domain, listOf(cheap, baseline, architect), architect, baseline, experiments = true, roll = 0.1,
        )
        assertEquals(baseline, picked)
        assertNotEquals(architect, picked)
    }

    @Test fun twoAndAHalfTimesCostCoolsDownForSevenDays() {
        val s = scorer()
        val now = 1_700_000_000_000L
        s.record(baseline, domain, sample(true, true, 0.02, 0.0), baseline, now)
        val expensive = "gemini/experiment"
        repeat(3) {
            s.record(expensive, domain, sample(true, false, 0.05, 0.20), baseline, now)
        }
        val score = s.score(expensive, domain, now)
        assertTrue(score.costToGreenUsd >= s.score(baseline, domain, now).costToGreenUsd * 2.5)
        assertEquals(now + CostToGreenScorer.COOLDOWN_MS, score.cooldownUntil)
        assertEquals(7L * 24 * 60 * 60 * 1000, CostToGreenScorer.COOLDOWN_MS)
        val picked = ImplementerExperimentEngine(s).select(
            domain, listOf(expensive, baseline), architect, baseline, experiments = true, roll = 0.1, now = now,
        )
        assertEquals(baseline, picked)
    }

    @Test fun lowSuccessRateAlsoCoolsDown() {
        val s = scorer()
        val now = 50_000L
        val weak = "gemini/weak"
        repeat(3) { s.record(weak, domain, sample(false, false, 0.01, 0.01), baseline, now) }
        assertTrue(s.score(weak, domain, now).overallSuccessRate < 0.40)
        assertEquals(now + CostToGreenScorer.COOLDOWN_MS, s.score(weak, domain, now).cooldownUntil)
    }

    @Test fun architectLockIsNotTouched() {
        val s = scorer()
        repeat(3) { s.record(architect, domain, sample(true, true, 0.001, 0.0), baseline) }
        val engine = ImplementerExperimentEngine(s)
        val chosen = engine.select(domain, listOf(architect, baseline), architect, baseline, experiments = true, roll = 0.99)
        assertNotEquals(architect, chosen)
        var architectSetting = architect
        val models = mutableMapOf("architect" to architectSetting, "editor" to "gemini/old")
        engine.applyEditorOnly(models, chosen, architectSetting)
        assertEquals(architect, models["architect"])
        assertEquals(architectSetting, architect)
        assertEquals(chosen, models["editor"])
        engine.applyEditorOnly(models, architect, architectSetting)
        assertEquals(architect, models["architect"])
        assertNotEquals(architect, models["editor"])
    }

    @Test fun newModelsGetASmallExplorationBonusAndTheSplitIsEightyTwenty() {
        val s = scorer()
        repeat(2) { s.record(baseline, domain, sample(true, true, 0.02, 0.0), baseline) }
        val young = s.score(baseline, domain)
        assertTrue(young.n < 3)
        assertTrue(s.selectionScore(young) > s.selectionScore(young.copy(n = 5)))
        val other = "gemini/other"
        repeat(5) { s.record(other, domain, sample(true, false, 0.03, 0.01), baseline) }
        val engine = ImplementerExperimentEngine(s)
        assertEquals(baseline, engine.select(domain, listOf(baseline, other), architect, baseline, true, roll = 0.1))
        assertEquals(other, engine.select(domain, listOf(baseline, other), architect, baseline, true, roll = 0.95))
    }
}
