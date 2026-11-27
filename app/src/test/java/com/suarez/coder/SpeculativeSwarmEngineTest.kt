package com.suarez.coder

import com.suarez.coder.evolution.TaskDomain
import com.suarez.coder.orchestrator.SpeculativeSwarmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpeculativeSwarmEngineTest {

    @Test
    fun earlyStopCancelsSlowStreamsWhenFirstCandidatePasses() = runBlocking {
        val engine = SpeculativeSwarmEngine()
        val cancelledSlow = AtomicInteger(0)
        val arms = listOf(
            SpeculativeSwarmEngine.SwarmArm(model = "gemini/gemini-2.5-flash", estimatedCostUsd = 0.005),
            SpeculativeSwarmEngine.SwarmArm(model = "deepseek/deepseek-chat", estimatedCostUsd = 0.005),
        )

        val result = engine.runTournament(
            arms = arms,
            lockedArchitectModel = "gemini/gemini-2.5-pro",
            budgetCeilingUsd = 0.02,
        ) { arm ->
            if (arm.model == "gemini/gemini-2.5-flash") {
                delay(20)
                SpeculativeSwarmEngine.CandidateOutput(
                    arm = arm,
                    diffLinesCount = 12,
                    preFlightWarnings = 0,
                    watchdogWarnings = 0,
                    securityIssues = 0,
                    crossFileSignatureErrors = 0,
                    testsPassed = true,
                    actualCostUsd = 0.004,
                    durationMs = 20,
                )
            } else {
                try {
                    delay(5_000)
                } catch (ce: CancellationException) {
                    cancelledSlow.incrementAndGet()
                    throw ce
                }
                error("Should have been cancelled")
            }
        }

        assertNotNull(result.winner)
        assertEquals("gemini/gemini-2.5-flash", result.winner?.arm?.model)
        assertEquals(1, cancelledSlow.get())
        assertEquals(1, result.cancelledCandidates)
        assertTrue(result.summaryLine.contains("erken durdurma"))
    }

    @Test
    fun budgetCeilingPreventsLaunchingExcessCandidates() = runBlocking {
        val engine = SpeculativeSwarmEngine()
        val arms = listOf(
            SpeculativeSwarmEngine.SwarmArm(model = "m1", estimatedCostUsd = 0.012),
            SpeculativeSwarmEngine.SwarmArm(model = "m2", estimatedCostUsd = 0.012),
            SpeculativeSwarmEngine.SwarmArm(model = "m3", estimatedCostUsd = 0.012),
        )

        val result = engine.runTournament(
            arms = arms,
            lockedArchitectModel = "gemini/gemini-2.5-pro",
            budgetCeilingUsd = 0.02,
        ) { arm ->
            SpeculativeSwarmEngine.CandidateOutput(
                arm = arm,
                diffLinesCount = 10,
                preFlightWarnings = 0,
                watchdogWarnings = 0,
                securityIssues = 0,
                crossFileSignatureErrors = 0,
                testsPassed = true,
                actualCostUsd = 0.010,
                durationMs = 10,
            )
        }

        assertEquals(1, result.launchedCandidates)
        assertTrue(result.totalCostUsd <= 0.02)
    }

    @Test(expected = IllegalArgumentException::class)
    fun lockedArchitectModelIsNeverAllowedInTournament() {
        val engine = SpeculativeSwarmEngine()
        val arms = listOf(
            SpeculativeSwarmEngine.SwarmArm(model = "gemini/gemini-2.5-pro"),
            SpeculativeSwarmEngine.SwarmArm(model = "deepseek/deepseek-chat"),
        )
        engine.validateCandidates(arms, lockedArchitectModel = "gemini/gemini-2.5-pro")
    }

    @Test
    fun selectArmsExcludesLockedArchitectModel() {
        val engine = SpeculativeSwarmEngine()
        val selected = engine.selectArms(
            scorer = null,
            domain = TaskDomain.GENERAL,
            pool = listOf("gemini/gemini-2.5-pro", "gemini/gemini-2.5-flash", "deepseek/deepseek-chat"),
            lockedArchitectModel = "gemini/gemini-2.5-pro",
            maxCandidates = 3,
        )
        assertFalse(selected.any { it.model == "gemini/gemini-2.5-pro" })
        assertEquals(2, selected.size)
    }

    @Test
    fun tieBreakerSelectsSmallestCleanDiffThenLowestCost() {
        val engine = SpeculativeSwarmEngine()
        val a1 = SpeculativeSwarmEngine.CandidateOutput(
            arm = SpeculativeSwarmEngine.SwarmArm("m1"),
            diffLinesCount = 20,
            preFlightWarnings = 0,
            watchdogWarnings = 0,
            securityIssues = 0,
            crossFileSignatureErrors = 0,
            testsPassed = true,
            actualCostUsd = 0.002,
            durationMs = 50,
        )
        val a2 = SpeculativeSwarmEngine.CandidateOutput(
            arm = SpeculativeSwarmEngine.SwarmArm("m2"),
            diffLinesCount = 8,
            preFlightWarnings = 0,
            watchdogWarnings = 0,
            securityIssues = 0,
            crossFileSignatureErrors = 0,
            testsPassed = true,
            actualCostUsd = 0.005,
            durationMs = 60,
        )
        val winner = engine.pickWinner(listOf(a1, a2))
        assertEquals("m2", winner?.arm?.model)
    }
}
