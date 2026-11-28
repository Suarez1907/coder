package com.suarez.coder.repair

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import com.suarez.coder.proof.InvariantMiner
import com.suarez.coder.proof.ProofCarryingPatchGate

/**
 * Faz 9 — 4 Kademeli Otonom Onarım Merdiveni (`SelfHealingLadder`).
 *
 * Maliyet-sıralı deterministik onarım döngüsü:
 * - Kademe 0 (`$0.0000` · `<150 ms`): [CompilerQuickFixer] + [ErrorFixCache]
 * - Kademe 1 (`$0.0000`): Ücretsiz kota / yerel Tier-0 + `TypedHoleSynthesizer` + `ddmin`
 * - Kademe 2 (`~$0.001–$0.005`): MAP-Elites / Cost-to-Green ücretli Uygulayıcı + `OutputDiet`
 * - Kademe 3 (Kesin Çözüm — Kilitli Mimar): Kullanıcının seçtiği kilitli **Mimar** modeli (asla değiştirilmez)
 *
 * Kapalı Öğrenme Döngüsü: Başarılı küçük düzeltmeler otomatik [ErrorFixCache]'e kaydedilir.
 */
class SelfHealingLadder(
    val fixCache: ErrorFixCache = ErrorFixCache(),
    private val tier1FreeRepair: (suspend (errorLog: String, files: Map<String, String>) -> Map<String, String>?)? = null,
    private val tier2PaidImplementer: (suspend (errorLog: String, files: Map<String, String>) -> Pair<Map<String, String>?, Double>)? = null,
    private val tier3LockedArchitect: (suspend (lockedArchitectId: String, errorLog: String, files: Map<String, String>) -> Pair<Map<String, String>?, Double>)? = null,
) {

    enum class Tier(val index: Int, val label: String, val zeroCost: Boolean) {
        TIER_0_DETERMINISTIC(0, "Kademe 0 ($0.00)", true),
        TIER_1_FREE_QUOTA(1, "Kademe 1 ($0.00)", true),
        TIER_2_PAID_IMPLEMENTER(2, "Kademe 2 (Uygulayıcı)", false),
        TIER_3_LOCKED_ARCHITECT(3, "Kademe 3 (Kilitli Mimar)", false),
        UNRESOLVED(-1, "Çözülemedi", false),
    }

    data class HealingResult(
        val resolved: Boolean,
        val tier: Tier,
        val staged: StagedTransaction,
        val llmCallsMade: Int,
        val costUsd: Double,
        val lockedArchitectModelId: String,
        val summary: String,
    ) {
        fun timelineNotice(): String =
            "Otonom Onarım (${tier.label}): $summary"
    }

    suspend fun heal(
        errorLog: String,
        files: Map<String, String>,
        lockedArchitectModelId: String,
        symbolPackages: Map<String, List<String>> = emptyMap(),
        invariants: List<InvariantMiner.Invariant> = emptyList(),
        oscillationDetected: Boolean = false,
        verifyFix: (StagedTransaction) -> Boolean = { it.edits().isNotEmpty() },
    ): HealingResult {
        var llmCalls = 0
        var totalCost = 0.0

        fun validateTx(updatedFiles: Map<String, String>): StagedTransaction? {
            val tx = StagedTransaction()
            for ((path, after) in updatedFiles) {
                val before = files[path]
                if (before != after) tx.stage(path, before, after)
            }
            if (tx.edits().isEmpty()) return null
            if (tx.edits().flatMap { PreFlightValidator.check(it.path, it.before, it.after) }.isNotEmpty()) return null
            if (invariants.isNotEmpty() && ProofCarryingPatchGate.verify(tx, invariants).blocked) return null
            if (!verifyFix(tx)) return null
            return tx
        }

        // Tier 0A ($0): ErrorFixCache (<20 ms)
        val cached = fixCache.tryRecallAndApply(errorLog, files, verifyPostApply = { tx ->
            (invariants.isEmpty() || !ProofCarryingPatchGate.verify(tx, invariants).blocked) && verifyFix(tx)
        })
        if (cached.applied) {
            return HealingResult(true, Tier.TIER_0_DETERMINISTIC, cached.staged, 0, 0.0, lockedArchitectModelId, "Hata-imza önbelleği ile çözüldü · CI geçti")
        }

        // Tier 0B ($0): CompilerQuickFixer (<100 ms)
        val quick = CompilerQuickFixer.tryFix(errorLog, files, symbolPackages, invariants)
        if (quick.fixed && verifyFix(quick.staged)) {
            return HealingResult(true, Tier.TIER_0_DETERMINISTIC, quick.staged, 0, 0.0, lockedArchitectModelId, "${quick.rulesApplied.size} mekanik düzeltme (${quick.rulesApplied.joinToString(", ")}) · CI geçti")
        }

        val t1 = tier1FreeRepair
        val t2 = tier2PaidImplementer
        val t3 = tier3LockedArchitect

        // Tier 1 ($0): ZeroCostWaterfall / local Tier-0
        if (!oscillationDetected && t1 != null) {
            llmCalls++
            val cand = runCatching { t1.invoke(errorLog, files) }.getOrNull()
            if (cand != null) {
                validateTx(cand)?.let { tx ->
                    return HealingResult(true, Tier.TIER_1_FREE_QUOTA, tx, llmCalls, 0.0, lockedArchitectModelId, "Ücretsiz kota/yerel katman ile çözüldü")
                }
            }
        }

        // Tier 2 (low cost): Paid Implementer (skipped directly to Tier 3 on oscillation)
        if (!oscillationDetected && t2 != null) {
            llmCalls++
            val (cand, cost) = runCatching { t2.invoke(errorLog, files) }.getOrDefault(null to 0.0)
            totalCost += cost.coerceAtLeast(0.0)
            if (cand != null) {
                validateTx(cand)?.let { tx ->
                    return HealingResult(true, Tier.TIER_2_PAID_IMPLEMENTER, tx, llmCalls, totalCost, lockedArchitectModelId, "Uygulayıcı modeli ile çözüldü")
                }
            }
        }

        // Tier 3 (Final resolution): User's locked Architect model
        if (t3 != null) {
            llmCalls++
            val (cand, cost) = runCatching { t3.invoke(lockedArchitectModelId, errorLog, files) }.getOrDefault(null to 0.0)
            totalCost += cost.coerceAtLeast(0.0)
            if (cand != null) {
                validateTx(cand)?.let { tx ->
                    return HealingResult(true, Tier.TIER_3_LOCKED_ARCHITECT, tx, llmCalls, totalCost, lockedArchitectModelId, "Kilitli Mimar ($lockedArchitectModelId) ile kesin çözüldü")
                }
            }
        }

        return HealingResult(false, Tier.UNRESOLVED, StagedTransaction(), llmCalls, totalCost, lockedArchitectModelId, "4 kademede de çözülemedi.")
    }
}
