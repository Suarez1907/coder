package com.suarez.coder

import com.suarez.coder.evolution.MetaHarnessCompressor
import com.suarez.coder.evolution.MetaHarnessCompressor.CompressionCandidate
import com.suarez.coder.evolution.MetaHarnessCompressor.EvalSnapshot
import com.suarez.coder.evolution.MetaHarnessCompressor.ReplaySnapshot
import com.suarez.coder.evolution.PrState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaHarnessCompressorTest {

    @Test
    fun `rejects candidate when token reduction is below 15 percent even with full eval pass`() {
        val compressor = MetaHarnessCompressor(nowMs = { 1700000000000L })
        val candidate = CompressionCandidate(
            oldTokens = 1000,
            newTokens = 860, // 14.0% reduction
            oldEval = EvalSnapshot(passed = 20, total = 20),
            newEval = EvalSnapshot(passed = 20, total = 20),
            oldReplay = ReplaySnapshot(score = 0.95),
            newReplay = ReplaySnapshot(score = 0.96),
            touchedFiles = listOf("app/src/main/java/com/suarez/coder/tools/ToolRegistry.kt"),
        )

        val result = compressor.evaluateCandidate(candidate)

        assertFalse(result.accepted)
        assertNull(result.pendingPr)
    }

    @Test
    fun `rejects candidate when 25 percent compressed but GoldenEval or ShadowReplay regresses`() {
        val compressor = MetaHarnessCompressor(nowMs = { 1700000000000L })

        val evalRegression = CompressionCandidate(
            oldTokens = 1000,
            newTokens = 750, // 25% reduction
            oldEval = EvalSnapshot(passed = 20, total = 20),
            newEval = EvalSnapshot(passed = 19, total = 20, failures = listOf("eval-7")),
            oldReplay = ReplaySnapshot(score = 0.95),
            newReplay = ReplaySnapshot(score = 0.95),
        )
        val evalResult = compressor.evaluateCandidate(evalRegression)
        assertFalse(evalResult.accepted)
        assertNull(evalResult.pendingPr)

        val replayRegression = CompressionCandidate(
            oldTokens = 1000,
            newTokens = 750, // 25% reduction
            oldEval = EvalSnapshot(passed = 20, total = 20),
            newEval = EvalSnapshot(passed = 20, total = 20),
            oldReplay = ReplaySnapshot(score = 0.95),
            newReplay = ReplaySnapshot(score = 0.92),
        )
        val replayResult = compressor.evaluateCandidate(replayRegression)
        assertFalse(replayResult.accepted)
        assertNull(replayResult.pendingPr)
    }

    @Test
    fun `accepts candidate and creates draft PendingSelfPr without autoMerge when reduction ge 15 percent and zero regressions`() {
        val fixedTs = 1700000001234L
        val compressor = MetaHarnessCompressor(nowMs = { fixedTs })
        val candidate = CompressionCandidate(
            oldTokens = 2000,
            newTokens = 1600, // 20% reduction
            oldEval = EvalSnapshot(passed = 24, total = 24),
            newEval = EvalSnapshot(passed = 24, total = 24),
            oldReplay = ReplaySnapshot(score = 0.94),
            newReplay = ReplaySnapshot(score = 0.95),
            touchedFiles = listOf("app/src/main/java/com/suarez/coder/tools/ToolRegistry.kt"),
            costUsd = 0.04,
        )

        val result = compressor.evaluateCandidate(candidate)

        assertTrue(result.accepted)
        val pr = result.pendingPr
        assertNotNull(pr)
        assertEquals("evo/meta-compress-$fixedTs", pr!!.branch)
        assertEquals(PrState.PENDING, pr.state)
        assertEquals(0, pr.evalRegressions)
        assertEquals("Taban Prompt: 2000 -> 1600 token (-%20) · Eval: 24/24 (0 gerileme)", pr.compressionReport)
    }
}
