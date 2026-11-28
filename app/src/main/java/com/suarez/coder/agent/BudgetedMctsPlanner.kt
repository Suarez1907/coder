package com.suarez.coder.agent

import com.suarez.coder.harness.ForensicDiffEngine
import com.suarez.coder.validate.DifferentialVerifier
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Faz 6A — Bütçeli MCTS Planlayıcı (`BudgetedMctsPlanner`).
 *
 * Kurallar:
 * - Standart tek-geçişli düzeltmelerde devreye GİRMEZ.
 * - Yalnızca `enabled == true` ve (`consecutiveFailures >= 2` veya `isComplexArchitectural == true`)
 *   durumunda etkinleşir.
 * - Kesin üst sınırlar zorunludur:
 *   - `maxNodes` en fazla [HARD_MAX_NODES] (4)
 *   - `maxDepth` en fazla [HARD_MAX_DEPTH] (2)
 *   - Sert `$` tavanı: `minOf(maxBudgetUsd, remainingSessionBudgetUsd)` — bütçeyi aşacak düğüm genişletilmez.
 * - Her dal `DifferentialVerifier` + `ForensicDiffEngine` deterministik skoruyla puanlanır.
 */
object BudgetedMctsPlanner {

    const val HARD_MAX_NODES = 4
    const val HARD_MAX_DEPTH = 2
    private const val UCB_C = 1.41421356

    data class Config(
        val enabled: Boolean = false,
        val maxNodes: Int = HARD_MAX_NODES,
        val maxDepth: Int = HARD_MAX_DEPTH,
        val maxBudgetUsd: Double = 0.10,
        val remainingSessionBudgetUsd: Double = 1.0,
    ) {
        val cappedNodes: Int get() = maxNodes.coerceIn(1, HARD_MAX_NODES)
        val cappedDepth: Int get() = maxDepth.coerceIn(1, HARD_MAX_DEPTH)
        val effectiveBudgetUsd: Double get() = minOf(maxBudgetUsd, remainingSessionBudgetUsd).coerceAtLeast(0.0)
    }

    data class CandidatePatch(
        val id: String,
        val description: String,
        val changes: List<Triple<String, String?, String?>>,
        val simulatedCiLog: String = "",
        val estimatedCostUsd: Double = 0.0,
    )

    data class ScoredNode(
        val id: String,
        val depth: Int,
        val patch: CandidatePatch,
        val verifyScore: Double,
        val forensicScore: Double,
        val totalScore: Double,
        val costUsd: Double,
        var visits: Int = 1,
    )

    data class PlanResult(
        val activated: Boolean,
        val bestNode: ScoredNode?,
        val nodesExpanded: Int,
        val maxDepthReached: Int,
        val spentUsd: Double,
        val budgetCapUsd: Double,
        val stopReason: String,
    )

    /**
     * Never activates for normal single-pass edits; requires opt-in flag plus either
     * 2+ consecutive failures on the same error or a complex architectural task.
     */
    fun shouldActivate(
        enabled: Boolean,
        consecutiveFailures: Int,
        isComplexArchitectural: Boolean,
    ): Boolean = enabled && (consecutiveFailures >= 2 || isComplexArchitectural)

    /**
     * Runs a strictly bounded MCTS search using [expand] to generate child candidate patches
     * from a parent node (null parent = root). Enforces node, depth, and USD hard caps.
     */
    fun plan(
        config: Config,
        consecutiveFailures: Int,
        isComplexArchitectural: Boolean,
        expand: (parent: ScoredNode?, depth: Int) -> List<CandidatePatch>,
    ): PlanResult {
        val budgetCap = config.effectiveBudgetUsd
        if (!shouldActivate(config.enabled, consecutiveFailures, isComplexArchitectural)) {
            return PlanResult(
                activated = false,
                bestNode = null,
                nodesExpanded = 0,
                maxDepthReached = 0,
                spentUsd = 0.0,
                budgetCapUsd = budgetCap,
                stopReason = "Standart tek-geçişli mod (MCTS devre dışı).",
            )
        }
        val maxNodes = config.cappedNodes
        val maxDepth = config.cappedDepth
        val explored = ArrayList<ScoredNode>(maxNodes)
        var spent = 0.0
        var deepest = 0
        var stopReason = "Arama tamamlandı."

        fun evaluate(patch: CandidatePatch, depth: Int): ScoredNode {
            val diffReport = DifferentialVerifier.verifyAll(patch.changes)
            val forensicReport = ForensicDiffEngine.diagnose(patch.simulatedCiLog)
            val vScore = diffReport.score
            val fScore = forensicReport.healthScore
            val combined = if (diffReport.hasBlocking) {
                (0.2 * vScore + 0.2 * fScore).coerceIn(0.0, 0.35)
            } else {
                (0.55 * vScore + 0.45 * fScore).coerceIn(0.0, 1.0)
            }
            return ScoredNode(
                id = patch.id,
                depth = depth,
                patch = patch,
                verifyScore = vScore,
                forensicScore = fScore,
                totalScore = combined,
                costUsd = patch.estimatedCostUsd.coerceAtLeast(0.0),
            )
        }

        // Depth 1 expansion from root
        val rootCandidates = expand(null, 1)
        for (cand in rootCandidates) {
            if (explored.size >= maxNodes) { stopReason = "Düğüm tavanına ulaşıldı ($maxNodes)."; break }
            val cCost = cand.estimatedCostUsd.coerceAtLeast(0.0)
            if (spent + cCost > budgetCap + 1e-9) { stopReason = "Bütçe tavanına ulaşıldı ($budgetCap USD)."; break }
            spent += cCost
            deepest = maxOf(deepest, 1)
            explored += evaluate(cand, 1)
        }

        // Depth 2+ UCB1-guided refinement while budget & node caps allow
        var totalVisits = explored.size.coerceAtLeast(1)
        while (explored.size < maxNodes && explored.isNotEmpty()) {
            val expandable = explored.filter { it.depth < maxDepth }
            if (expandable.isEmpty()) { stopReason = "Derinlik tavanına ulaşıldı ($maxDepth)."; break }
            val parent = expandable.maxByOrNull { node ->
                node.totalScore + UCB_C * sqrt(ln(totalVisits.toDouble() + 1.0) / node.visits.toDouble())
            } ?: break
            val nextDepth = parent.depth + 1
            if (nextDepth > maxDepth) { stopReason = "Derinlik tavanına ulaşıldı ($maxDepth)."; break }

            val children = expand(parent, nextDepth)
            if (children.isEmpty()) break
            var addedAny = false
            for (cand in children) {
                if (explored.size >= maxNodes) { stopReason = "Düğüm tavanına ulaşıldı ($maxNodes)."; break }
                val cCost = cand.estimatedCostUsd.coerceAtLeast(0.0)
                if (spent + cCost > budgetCap + 1e-9) { stopReason = "Bütçe tavanına ulaşıldı ($budgetCap USD)."; break }
                spent += cCost
                deepest = maxOf(deepest, nextDepth)
                explored += evaluate(cand, nextDepth)
                parent.visits++
                totalVisits++
                addedAny = true
            }
            if (!addedAny) break
        }

        val best = explored.maxWithOrNull(compareBy<ScoredNode> { it.totalScore }.thenBy { -it.costUsd })
        return PlanResult(
            activated = true,
            bestNode = best,
            nodesExpanded = explored.size,
            maxDepthReached = deepest,
            spentUsd = spent,
            budgetCapUsd = budgetCap,
            stopReason = stopReason,
        )
    }
}
