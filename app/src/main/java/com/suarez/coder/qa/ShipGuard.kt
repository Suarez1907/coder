package com.suarez.coder.qa

/**
 * Stops an automatic merge after a visual defect or a critical critic finding.
 * Empty verdicts do not block: the GitHub path keeps working when neither check has run.
 */
class ShipGuard {
    @Volatile var visual: VisualQaGate.Result? = null
    @Volatile var visualRounds: Int = 0
    @Volatile var critic: AdversarialCritic.Verdict? = null
    @Volatile var criticRounds: Int = 0
    @Volatile var mutationReport: String? = null
    @Volatile var mutationSummary: String? = null
    @Volatile var userApproved: Boolean = false

    fun reset() {
        visual = null
        visualRounds = 0
        critic = null
        criticRounds = 0
        mutationReport = null
        mutationSummary = null
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
        if (mutationOn && !mutationReport.isNullOrBlank()) {
            val summary = mutationSummary?.takeIf { it.isNotBlank() } ?: "Diff mutasyon testi: hayatta kalan mutant var."
            return "$summary Otomatik merge durdu.\n$mutationReport"
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
    }
}
