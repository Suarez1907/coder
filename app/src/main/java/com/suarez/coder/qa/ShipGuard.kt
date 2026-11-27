package com.suarez.coder.qa

/**
 * Stops an automatic merge after a visual defect, a critical critic finding or a surviving mutant.
 * Empty verdicts do not block: the GitHub path keeps working when no check has run.
 */
class ShipGuard {
    @Volatile var visual: VisualQaGate.Result? = null
    @Volatile var visualRounds: Int = 0
    @Volatile var critic: AdversarialCritic.Verdict? = null
    @Volatile var criticRounds: Int = 0
    @Volatile var mutationReport: String? = null
    @Volatile var mutationSummary: String? = null
    @Volatile var mutationLogicDiff: Boolean = true
    @Volatile var userApproved: Boolean = false

    /** Records a [DiffMutationTester.MutationEvaluation] so Auto-Ship can block on surviving mutants in logic diffs. */
    fun recordMutationRun(run: DiffMutationTester.MutationEvaluation) {
        mutationLogicDiff = !run.skipped && (run.totalMutants > 0 || run.survivingMutants.isNotEmpty())
        if (!run.allowMerge && run.survivingMutants.isNotEmpty()) {
            mutationSummary = run.summaryLine
            mutationReport = run.recoveryPrompt.ifBlank { run.summaryLine }
        } else {
            mutationSummary = null
            mutationReport = null
        }
    }

    fun reset() {
        visual = null
        visualRounds = 0
        critic = null
        criticRounds = 0
        mutationReport = null
        mutationSummary = null
        mutationLogicDiff = true
        userApproved = false
    }

    fun hold(
        autoShip: Boolean,
        visualOn: Boolean,
        criticOn: Boolean,
        mutationOn: Boolean = false,
    ): String? {
        if (!autoShip || userApproved) return null
        if (visualOn && VisualQaGate.blocksMerge(visual)) {
            val issues = visual?.issues.orEmpty().joinToString("\n")
            val tail = if (visualRounds >= VisualQaGate.MAX_FIX_ROUNDS) "Tur sınırı doldu; kullanıcı onayı gerekir."
            else "Aynı dalda düzeltme turu açılabilir (${visualRounds}/${VisualQaGate.MAX_FIX_ROUNDS})."
            return "Görsel QA: VISUAL_DEFECT. Otomatik merge durdu. $tail\n$issues"
        }
        if (criticOn && AdversarialCritic.blocksMerge(critic)) {
            val sites = critic?.findings.orEmpty().joinToString("\n") { "${it.site} ${it.category}: ${it.detail}" }
            val tail = if (criticRounds >= AdversarialCritic.MAX_FIX_ROUNDS) "Tur sınırı doldu; kullanıcı onayı gerekir."
            else "Bulgular bir düzeltme turuna aktarılabilir (${criticRounds}/${AdversarialCritic.MAX_FIX_ROUNDS})."
            return "Karşıt eleştirmen: BLOCK_CRITICAL. Otomatik merge durdu. $tail\n$sites"
        }
        if (mutationOn && mutationLogicDiff && blocksMutation(mutationReport)) {
            val summary = (mutationSummary?.takeIf { it.isNotBlank() }
                ?: "Diff mutasyon testi: mantık değişikliğinde hayatta kalan mutant var.")
                .replace('\n', ' ').replace('\r', ' ').trim()
            val detail = mutationReport.orEmpty().trim()
            return "$summary Otomatik merge durdu.\n$detail"
        }
        return null
    }

    companion object {
        fun blocksMerge(visual: VisualQaGate.Result?, critic: AdversarialCritic.Verdict?): Boolean =
            VisualQaGate.blocksMerge(visual) || AdversarialCritic.blocksMerge(critic)

        fun allowAnotherVisualFix(roundsDone: Int) = roundsDone < VisualQaGate.MAX_FIX_ROUNDS
        fun allowAnotherCriticFix(roundsDone: Int) = roundsDone < AdversarialCritic.MAX_FIX_ROUNDS

        /** Diff mutation gate: any surviving mutant report blocks the automatic merge. */
        fun blocksMutation(report: String?): Boolean = !report.isNullOrBlank()

        /** One-line chat/PR summary for the mutation gate; empty when no mutant survived. */
        fun mutationSummaryLine(
            report: String?,
            fallback: String = "Diff mutasyon testi: mantık değişikliğinde hayatta kalan mutant var.",
        ): String = if (!blocksMutation(report)) "" else fallback

        /** Full survivor feedback (line + mutant detail) for the chat/PR body. */
        fun mutationDetail(report: String?): String =
            if (!blocksMutation(report)) "" else report.orEmpty().trim()

        fun blocksMutationRun(run: DiffMutationTester.MutationEvaluation?): Boolean =
            run != null && !run.allowMerge
    }
}
