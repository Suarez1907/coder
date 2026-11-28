package com.suarez.coder.decision

/**
 * Faz 7B — Aktif Çıkarım Eylem Puanlayıcısı (`ActiveInferencePlanner`).
 *
 * Belirsizlik altında körlemesine pahalı kod yazmak yerine, dolar başına en yüksek bilgi kazancını
 * (`infoGainPerDollar = expectedEntropyReduction / (estimatedCostUsd + 0.0005)`) üreten eylemi seçer.
 * Belirsizlik entropisi `H > 0.55` olduğunda `$0` maliyetli yüksek bilgi kazançlı teşhis adımları
 * (`ReflexExecutor`, `github_find_impact`, `github_list_symbols`, `git bisect`, `fetch_live_docs`)
 * ana kod üretme adımından önce otomatik önceliklendirilir.
 */
object ActiveInferencePlanner {

    const val HIGH_ENTROPY_THRESHOLD = 0.55
    const val COST_EPSILON_USD = 0.0005

    enum class ActionKind(val isDiagnosticZeroCost: Boolean) {
        REFLEX_MATCH(true),
        GITHUB_FIND_IMPACT(true),
        GITHUB_LIST_SYMBOLS(true),
        GIT_BISECT_REPRO(true),
        FETCH_LIVE_DOCS(true),
        IMPLEMENTER_CODE_EDIT(false),
        ARCHITECT_PLAN(false),
    }

    data class UncertaintySignals(
        val missingSymbolsOrFiles: Int = 0,
        val ambiguousErrorLog: Boolean = false,
        val priorFailuresOnTask: Int = 0,
        val hasOscillation: Boolean = false,
    )

    data class CandidateAction(
        val id: String,
        val kind: ActionKind,
        val expectedEntropyReduction: Double,
        val estimatedCostUsd: Double,
    ) {
        fun infoGainPerDollar(): Double =
            expectedEntropyReduction.coerceIn(0.0, 1.0) / (estimatedCostUsd.coerceAtLeast(0.0) + COST_EPSILON_USD)
    }

    data class PlanDecision(
        val entropy: Double,
        val diagnosticFirst: Boolean,
        val orderedActions: List<CandidateAction>,
        val chosen: CandidateAction?,
    )

    /** Estimates epistemic uncertainty H in [0.0, 1.0]. */
    fun estimateEntropy(signals: UncertaintySignals): Double {
        var h = 0.15
        h += (signals.missingSymbolsOrFiles.coerceIn(0, 4)) * 0.14
        if (signals.ambiguousErrorLog) h += 0.25
        h += (signals.priorFailuresOnTask.coerceIn(0, 3)) * 0.12
        if (signals.hasOscillation) h += 0.20
        return h.coerceIn(0.0, 1.0)
    }

    fun rankAndChoose(signals: UncertaintySignals, actions: List<CandidateAction>): PlanDecision {
        val h = estimateEntropy(signals)
        val diagFirst = h > HIGH_ENTROPY_THRESHOLD
        val ordered = actions.sortedWith(
            compareByDescending<CandidateAction> { if (diagFirst && it.kind.isDiagnosticZeroCost && it.expectedEntropyReduction > 0.05) 1 else 0 }
                .thenByDescending { it.infoGainPerDollar() }
        )
        return PlanDecision(h, diagFirst, ordered, ordered.firstOrNull())
    }
}
