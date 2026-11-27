package com.suarez.coder.evolution

/**
 * Offline replay of the last 50 traces. A routing, preflight or cost-to-green change is kept only when the replay
 * is cheaper or scores higher, and it shows no regression. The replay itself costs nothing.
 */
class ShadowReplayEngine(traces: List<SessionTrace>) {
    val pool: List<SessionTrace> = traces.takeLast(POOL).map { anonymize(it) }

    fun judge(proposal: ShadowProposal, baselineCost: Double, baselineScore: Double): ShadowVerdict {
        val cost = baselineCost * proposal.costFactor
        val score = baselineScore + proposal.scoreDelta
        val better = cost < baselineCost - 1e-9 || score > baselineScore + 1e-9
        val accepted = better && proposal.regressions == 0
        val reason = when {
            proposal.regressions > 0 -> "gerileme ${proposal.regressions}"
            !better -> "maliyet veya skor iyileşmedi"
            else -> "kabul"
        }
        return ShadowVerdict(accepted, reason, 0.0)
    }

    /**
     * Raising the architect threshold on traces that already needed the architect (and succeeded because of it)
     * is a regression. Lowering it costs more but can raise the score when nothing regresses.
     */
    fun projectThreshold(oldT: Double, newT: Double): ShadowProposal {
        if (newT == oldT) return ShadowProposal("threshold", 1.0, 0.0, 0)
        val risky = if (newT > oldT) pool.count { it.upgradedToArchitect && it.success } else 0
        val costFactor = if (newT > oldT) 0.85 else 1.2
        val scoreDelta = if (risky == 0 && newT < oldT) 0.02 else 0.0
        return ShadowProposal("threshold", costFactor, scoreDelta, risky)
    }

    private fun anonymize(t: SessionTrace): SessionTrace {
        val masked = PrivacyFilter.apply(t.request, listOf(t.repo)).text ?: ""
        return t.copy(repo = "repo", request = masked, branch = null, baseSha = null)
    }

    companion object {
        const val POOL = 50
    }
}

data class ShadowProposal(val kind: String, val costFactor: Double, val scoreDelta: Double, val regressions: Int)

data class ShadowVerdict(val accepted: Boolean, val reason: String, val costUsd: Double)
