package com.suarez.coder

import com.suarez.coder.decision.ActiveInferencePlanner
import com.suarez.coder.evolution.KellyBudgetMarket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveInferenceAndKellyTest {
    @Test
    fun prioritizesZeroCostDiagnosticsUnderHighEntropyAndRespectsKellyBoundsAndArchitectLock() {
        val signals = ActiveInferencePlanner.UncertaintySignals(missingSymbolsOrFiles = 3, ambiguousErrorLog = true)
        val decision = ActiveInferencePlanner.rankAndChoose(
            signals,
            listOf(
                ActiveInferencePlanner.CandidateAction("edit", ActiveInferencePlanner.ActionKind.IMPLEMENTER_CODE_EDIT, 0.4, 0.02),
                ActiveInferencePlanner.CandidateAction("impact", ActiveInferencePlanner.ActionKind.GITHUB_FIND_IMPACT, 0.35, 0.0),
            ),
        )
        assertTrue(decision.diagnosticFirst)
        assertEquals("impact", decision.chosen!!.id)

        val history = (1..10).map { KellyBudgetMarket.PredictionRecord("good-impl", 0.9, true, 0.005) } +
            (1..10).map { KellyBudgetMarket.PredictionRecord("overconfident-impl", 0.95, false, 0.02) }
        val snap = KellyBudgetMarket.allocate("locked/claude-architect", listOf("good-impl", "overconfident-impl"), history)
        assertEquals("locked/claude-architect", snap.lockedArchitectModelId)
        val byId = snap.allocations.associateBy { it.implementerModelId }
        assertTrue(byId["good-impl"]!!.kellyWeight > byId["overconfident-impl"]!!.kellyWeight)
        assertTrue(snap.allocations.all { it.kellyWeight in KellyBudgetMarket.MIN_WEIGHT..KellyBudgetMarket.MAX_WEIGHT })
    }
}
