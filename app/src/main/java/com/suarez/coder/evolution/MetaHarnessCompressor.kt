package com.suarez.coder.evolution

import com.suarez.coder.cost.PriorityTier
import kotlin.math.roundToInt

/**
 * Self-compressing Meta-Harness: measures base prompt and tool-schema token weight,
 * evaluates compression candidates against both GoldenEvalRunner and ShadowReplayEngine,
 * and prepares a draft PendingSelfPr only when >=15% token reduction AND zero regressions
 * are proven. Never auto-merges.
 */
class MetaHarnessCompressor(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class EvalSnapshot(
        val passed: Int,
        val total: Int,
        val failures: List<String> = emptyList(),
    )

    data class ReplaySnapshot(
        val score: Double,
        val sampleSize: Int = SHADOW_REPLAY_WINDOW,
    )

    data class CompressionCandidate(
        val oldTokens: Int,
        val newTokens: Int,
        val oldEval: EvalSnapshot,
        val newEval: EvalSnapshot,
        val oldReplay: ReplaySnapshot,
        val newReplay: ReplaySnapshot,
        val touchedFiles: List<String> = emptyList(),
        val costUsd: Double = 0.0,
    )

    data class CompressionGateResult(
        val accepted: Boolean,
        val reductionRatio: Double,
        val report: String,
        val reason: String,
        val pendingPr: PendingSelfPr? = null,
    )

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length + 3) / 4
    }

    fun measureBaseWeight(toolDescriptions: List<String>, systemBlocks: List<String>): Int {
        val toolTokens = toolDescriptions.sumOf { estimateTokens(it) }
        val systemTokens = systemBlocks.sumOf { estimateTokens(it) }
        return toolTokens + systemTokens
    }

    fun isWithinBudget(
        metaHarnessBudgetUsd: Double,
        dailyEvoBudgetUsd: Double,
        spentTodayUsd: Double,
        estimatedRunCostUsd: Double,
    ): Boolean {
        if (metaHarnessBudgetUsd <= 0.0 || dailyEvoBudgetUsd <= 0.0) return false
        if (estimatedRunCostUsd > metaHarnessBudgetUsd) return false
        return spentTodayUsd + estimatedRunCostUsd <= dailyEvoBudgetUsd
    }

    fun evaluateCandidate(candidate: CompressionCandidate): CompressionGateResult {
        val oldTok = candidate.oldTokens
        val newTok = candidate.newTokens
        val ratio = if (oldTok <= 0) 0.0 else (oldTok - newTok).toDouble() / oldTok.toDouble()
        val pct = (ratio * 100.0).roundToInt()
        val report = "Taban Prompt: $oldTok -> $newTok token (-%$pct) · Eval: ${candidate.newEval.passed}/${candidate.newEval.total} (0 gerileme)"

        if (ratio < MIN_REDUCTION_RATIO) {
            return CompressionGateResult(
                accepted = false,
                reductionRatio = ratio,
                report = report,
                reason = "Token azalması %15 sınırının altında (-%$pct).",
                pendingPr = null,
            )
        }

        val evalRegression = candidate.newEval.passed < candidate.oldEval.passed ||
            candidate.newEval.failures.isNotEmpty()
        if (evalRegression) {
            return CompressionGateResult(
                accepted = false,
                reductionRatio = ratio,
                report = report,
                reason = "GoldenEval gerilemesi tespit edildi (${candidate.newEval.passed}/${candidate.newEval.total}).",
                pendingPr = null,
            )
        }

        val replayRegression = candidate.newReplay.score < candidate.oldReplay.score
        if (replayRegression) {
            return CompressionGateResult(
                accepted = false,
                reductionRatio = ratio,
                report = report,
                reason = "ShadowReplay gerilemesi tespit edildi (${candidate.newReplay.score} < ${candidate.oldReplay.score}).",
                pendingPr = null,
            )
        }

        val ts = nowMs()
        val branch = "evo/meta-compress-$ts"
        val pr = PendingSelfPr(
            number = 0,
            title = "Meta-Harness Sıkıştırma (-%$pct)",
            url = "",
            branch = branch,
            rootCause = report,
            files = candidate.touchedFiles,
            ciPassed = true,
            costUsd = candidate.costUsd,
            topic = "meta-harness",
            createdAt = ts,
            state = PrState.PENDING,
            evalPassed = candidate.newEval.passed,
            evalTotal = candidate.newEval.total,
            evalRegressions = 0,
            compressionReport = report,
        )

        return CompressionGateResult(
            accepted = true,
            reductionRatio = ratio,
            report = report,
            reason = "Çift kanıt kapısı geçildi; taslak PR hazırlandı (autoMerge=false).",
            pendingPr = pr,
        )
    }

    companion object {
        const val MIN_REDUCTION_RATIO = 0.15
        const val SHADOW_REPLAY_WINDOW = 50
        const val TARGET_REPO = "Suarez1907/coder"
        val PRIORITY_TIER: PriorityTier = PriorityTier.BACKGROUND_FLEX
    }
}
