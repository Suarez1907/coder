package com.suarez.coder

import com.suarez.coder.evolution.EvolutionModel
import com.suarez.coder.evolution.EvolutionProvider
import com.suarez.coder.evolution.SessionTrace
import com.suarez.coder.evolution.ShadowProposal
import com.suarez.coder.evolution.ShadowReplayEngine
import com.suarez.coder.evolution.StrategyAbOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShadowReplayAndAbTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun trace(i: Int, repo: String = "Secret/app") = SessionTrace(
        id = "t$i", sessionId = "s$i", repo = repo, at = i.toLong(), request = "bak $repo ghp_abcdefghijklmnopqrstuvwxyz",
        modelsUsed = emptyList(), turnCount = 1, cacheHitPercent = null, totalCostUsd = 0.02,
        diffErrorCount = 0, diffErrorSamples = emptyList(), ciFailureCount = 0, ciSignatures = emptyList(),
        escalations = emptyList(), routing = null, upgradedToArchitect = i >= 40, stopped = false,
    )

    @Test fun regressingProposalIsRejectedAtZeroCost() {
        val engine = ShadowReplayEngine((1..60).map { trace(it) })
        assertEquals(50, engine.pool.size)
        assertTrue(engine.pool.all { it.repo == "repo" })
        assertFalse(engine.pool.any { it.request.contains("ghp_") || it.request.contains("Secret/app") })
        val verdict = engine.judge(ShadowProposal("threshold", 0.5, 0.2, regressions = 1), baselineCost = 0.02, baselineScore = 0.8)
        assertFalse(verdict.accepted)
        assertEquals(0.0, verdict.costUsd, 0.0)
        assertTrue(verdict.reason.contains("gerileme"))
        val ok = engine.judge(ShadowProposal("ctg", 0.8, 0.0, regressions = 0), 0.02, 0.8)
        assertTrue(ok.accepted)
        assertEquals(0.0, ok.costUsd, 0.0)
        val raised = engine.projectThreshold(1.0, 2.0)
        assertTrue(raised.regressions > 0)
        assertFalse(engine.judge(raised, 0.02, 1.0).accepted)
        val flat = engine.judge(ShadowProposal("preflight", 1.0, 0.0, 0), 0.02, 0.8)
        assertFalse(flat.accepted)
        assertEquals(0.0, flat.costUsd, 0.0)
    }

    @Test fun providerSwitchDoesNotTouchArchitect() {
        val architect = "gemini/gemini-2.5-pro"
        val openRouter = EvolutionModel.fromFields(architect, "OPENROUTER", "nvidia/nemotron-3-ultra", "gemini-2.5-flash")
        val gemini = EvolutionModel.select(architect, EvolutionProvider.GEMINI, "gemini-2.5-pro")
        val switched = EvolutionModel.select(openRouter.architectModelId, EvolutionProvider.GEMINI, "gemini-2.5-flash")
        assertEquals(architect, openRouter.architectModelId)
        assertEquals(architect, gemini.architectModelId)
        assertEquals(architect, switched.architectModelId)
        assertEquals(EvolutionProvider.OPENROUTER, openRouter.provider)
        assertEquals("nvidia/nemotron-3-ultra", openRouter.modelId)
        assertEquals(EvolutionProvider.GEMINI, switched.provider)
        assertEquals("gemini-2.5-flash", switched.modelId)
        assertEquals(EvolutionProvider.GEMINI, gemini.provider)
        assertFalse(EvolutionModel.canSpend(0.10, 0.10))
        assertTrue(EvolutionModel.canSpend(0.04, 0.10))
    }

    @Test fun failingVariantRollsBackAndArchitectStays() {
        val opt = StrategyAbOptimizer(tmp.newFile("ab.json"), "gemini/architect-lock")
        assertTrue(opt.start("list_symbols"))
        assertTrue(opt.start("compact_diff"))
        assertFalse(opt.start("test_first"))
        val first = opt.pin("same", 0.95)
        assertEquals(first.arm, opt.pin("same", 0.0).arm)
        assertEquals("gemini/architect-lock", opt.architectModelId)
        repeat(6) { i ->
            assertEquals("control", opt.pin("c$i", 0.0).arm)
            opt.record("c$i", 0.01, true)
        }
        repeat(4) { i ->
            assertEquals("variant", opt.pin("v$i", 0.99).arm)
            opt.record("v$i", 0.08, false)
        }
        val report = opt.evaluate()
        assertTrue(report.rolledBack)
        assertTrue(report.active.isEmpty())
        assertEquals("gemini/architect-lock", opt.architectModelId)
    }
}
