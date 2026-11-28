package com.suarez.coder

import com.suarez.coder.agent.BudgetedMctsPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetedMctsPlannerTest {

    @Test
    fun staysInactiveOnStandardSinglePassFixOrWhenFlagDisabled() {
        assertFalse(BudgetedMctsPlanner.shouldActivate(enabled = true, consecutiveFailures = 0, isComplexArchitectural = false))
        assertFalse(BudgetedMctsPlanner.shouldActivate(enabled = true, consecutiveFailures = 1, isComplexArchitectural = false))
        assertFalse(BudgetedMctsPlanner.shouldActivate(enabled = false, consecutiveFailures = 3, isComplexArchitectural = true))
        assertTrue(BudgetedMctsPlanner.shouldActivate(enabled = true, consecutiveFailures = 2, isComplexArchitectural = false))
        assertTrue(BudgetedMctsPlanner.shouldActivate(enabled = true, consecutiveFailures = 0, isComplexArchitectural = true))

        val skipped = BudgetedMctsPlanner.plan(
            config = BudgetedMctsPlanner.Config(enabled = true, maxBudgetUsd = 0.10),
            consecutiveFailures = 1,
            isComplexArchitectural = false,
        ) { _, _ -> error("Should not expand on single-pass fix") }
        assertFalse(skipped.activated)
        assertEquals(0, skipped.nodesExpanded)
    }

    @Test
    fun strictlyEnforcesNodeDepthAndBudgetCapsAndPicksBestDeterministicScore() {
        val cleanBefore = "package a\nclass S { fun ok(): Int = 1 }"
        val cleanAfter = "package a\nclass S { fun ok(): Int = 2 }"
        val brokenAfter = "package a\nclass S { fun ok(): Int { return 2 }"

        val res = BudgetedMctsPlanner.plan(
            config = BudgetedMctsPlanner.Config(
                enabled = true,
                maxNodes = 10, // must still be hard-capped to 4
                maxDepth = 5,  // must still be hard-capped to 2
                maxBudgetUsd = 0.10,
                remainingSessionBudgetUsd = 0.08, // tighter session cap
            ),
            consecutiveFailures = 2,
            isComplexArchitectural = false,
        ) { parent, depth ->
            if (parent == null) {
                listOf(
                    BudgetedMctsPlanner.CandidatePatch(
                        id = "bad_root",
                        description = "Unbalanced syntax",
                        changes = listOf(Triple("S.kt", cleanBefore, brokenAfter)),
                        simulatedCiLog = "e: S.kt:2: Expecting '}'",
                        estimatedCostUsd = 0.025,
                    ),
                    BudgetedMctsPlanner.CandidatePatch(
                        id = "good_root",
                        description = "Clean fix",
                        changes = listOf(Triple("S.kt", cleanBefore, cleanAfter)),
                        simulatedCiLog = "",
                        estimatedCostUsd = 0.025,
                    ),
                )
            } else {
                listOf(
                    BudgetedMctsPlanner.CandidatePatch(
                        id = "child_a_d$depth",
                        description = "Refine A",
                        changes = listOf(Triple("S.kt", cleanBefore, cleanAfter)),
                        simulatedCiLog = "",
                        estimatedCostUsd = 0.025,
                    ),
                    BudgetedMctsPlanner.CandidatePatch(
                        id = "child_b_d$depth",
                        description = "Would exceed budget cap",
                        changes = listOf(Triple("S.kt", cleanBefore, cleanAfter)),
                        simulatedCiLog = "",
                        estimatedCostUsd = 0.050,
                    ),
                )
            }
        }

        assertTrue(res.activated)
        assertTrue("Nodes expanded (${res.nodesExpanded}) must be <= 4", res.nodesExpanded <= BudgetedMctsPlanner.HARD_MAX_NODES)
        assertTrue("Max depth (${res.maxDepthReached}) must be <= 2", res.maxDepthReached <= BudgetedMctsPlanner.HARD_MAX_DEPTH)
        assertTrue("Spent USD (${res.spentUsd}) must be <= 0.08", res.spentUsd <= 0.08 + 1e-9)
        assertNotNull(res.bestNode)
        assertTrue(res.bestNode!!.id != "bad_root")
        assertTrue(res.bestNode!!.totalScore >= 0.9)
    }
}
