package com.suarez.coder

import com.suarez.coder.evolution.EvolutionCompleter
import com.suarez.coder.evolution.EvolutionCompletion
import com.suarez.coder.evolution.EvolutionProvider
import com.suarez.coder.evolution.RemReplayEngine
import com.suarez.coder.evolution.SessionTrace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemReplayEngineTest {

    private fun trace(id: String, ciFails: Int, cost: Double) = SessionTrace(
        id = id,
        sessionId = "s_$id",
        repo = "Suarez1907/coder",
        at = 1_000_000L,
        request = "Kotlin derleme hatasını düzelt",
        modelsUsed = listOf("deepseek-chat"),
        turnCount = 7,
        cacheHitPercent = 60,
        totalCostUsd = cost,
        diffErrorCount = 1,
        diffErrorSamples = listOf("SEARCH bloğu eşleşmedi"),
        ciFailureCount = ciFails,
        ciSignatures = listOf("e: SequentialOrchestrator.kt:70: Unresolved reference 'AutoHealer'"),
        escalations = emptyList(),
        routing = null,
        upgradedToArchitect = false,
        stopped = false,
    )

    @Test
    fun enforcesReadOnlyDailyBudgetAndMaxTracesPerNight() = runBlocking {
        val traces = (1..6).map { trace("t$it", ciFails = it, cost = 0.20) }
        var calls = 0
        val fakeCompleter = EvolutionCompleter { _, _, _ ->
            calls++
            EvolutionCompletion(
                text = "Commit öncesi AutoHealer importunu doğrula.",
                costUsd = 0.025,
                modelId = "gemini-2.5-flash",
                provider = EvolutionProvider.GEMINI,
            )
        }

        val report = RemReplayEngine.replay(
            traces = traces,
            config = RemReplayEngine.Config(
                enabled = true,
                dailyBudgetUsd = 0.05,
                maxTracesPerNight = 3,
            ),
            completer = fakeCompleter,
        )

        assertTrue("REM must always remain READ_ONLY", report.readOnly)
        assertTrue("Analyzed traces (${report.tracesAnalyzed}) must be <= 3", report.tracesAnalyzed <= 3)
        assertTrue("Spent USD (${report.spentUsd}) must be <= 0.05", report.spentUsd <= 0.05 + 1e-9)
        assertEquals(2, calls)
        assertTrue(report.immunityCandidates.isNotEmpty())
        assertTrue(report.lessons.isNotEmpty())
    }

    @Test
    fun returnsEmptyWhenDisabled() = runBlocking {
        val report = RemReplayEngine.replay(
            traces = listOf(trace("t1", 2, 0.30)),
            config = RemReplayEngine.Config(enabled = false),
        )
        assertTrue(report.readOnly)
        assertEquals(0, report.tracesAnalyzed)
        assertEquals(0.0, report.spentUsd, 1e-9)
    }
}
