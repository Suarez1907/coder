package com.suarez.coder

import com.suarez.coder.evolution.AdversarialCoEvolutionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialCoEvolutionTest {
    @Test
    fun catchesInjectedGapIsolatesBlueVaccineAndNeverAutoMerges() = runBlocking {
        val report = AdversarialCoEvolutionEngine.runCycle(
            enabled = true,
            lockedArchitectModelId = "locked/arch",
            dailyBudgetUsd = 0.05,
            shieldEvaluator = { probe -> probe.id != "rt_yaml_tab" },
        )
        assertTrue(report.executed)
        assertFalse(report.autoMerged)
        assertEquals(1, report.findings.size)
        assertEquals(1, report.vaccineDrafts.size)
        val pr = report.vaccineDrafts.single()
        assertTrue(pr.isEvolutionSession)
        assertFalse(pr.autoMerge)
        assertEquals("locked/arch", pr.lockedArchitectModelId)
        assertTrue(report.spentUsd <= 0.05 + 1e-9)
    }
}
