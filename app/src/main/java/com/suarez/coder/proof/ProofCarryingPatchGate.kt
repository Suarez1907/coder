package com.suarez.coder.proof

import com.suarez.coder.harness.StagedTransaction
import java.security.MessageDigest

/**
 * Faz 7A — Kanıt Taşıyan Yama Kapısı (`ProofCarryingPatchGate`).
 *
 * Her push ve Auto-Ship merge öncesinde staged değişiklikler üzerinde aktif `.coder/invariants.json`
 * kurallarının tamamını `$0` maliyetle doğrular ve [PatchProof] üretir.
 * Tek bir değişmez bile kırılırsa push/merge fiziksel olarak bloke edilir; hiçbir ajan değişmezi
 * kullanıcı onayı olmadan devre dışı bırakamaz.
 */
object ProofCarryingPatchGate {

    data class BrokenInvariant(
        val invariant: InvariantMiner.Invariant,
        val path: String,
        val reason: String,
    )

    data class PatchProof(
        val checkedCount: Int,
        val passedCount: Int,
        val brokenInvariants: List<BrokenInvariant>,
        val proofHash: String,
        val approvedOverrides: Set<String> = emptySet(),
    ) {
        val blocked: Boolean get() = brokenInvariants.any { it.invariant.id !in approvedOverrides }

        /** Compact PR description line: `Değişmez Kanıtı: 18/18 geçti (proofHash: #a4f9c2)` */
        fun prSummary(): String =
            "Değişmez Kanıtı: $passedCount/$checkedCount geçti (proofHash: #$proofHash)"

        fun blockMessage(): String = if (!blocked) "" else buildString {
            append("Değişmez Kapısı push/merge işlemini durdurdu (").append(prSummary()).append("):\n")
            for (b in brokenInvariants.filter { it.invariant.id !in approvedOverrides }) {
                append("- [").append(b.invariant.category.name).append("] ").append(b.invariant.id)
                    .append(" (").append(b.path).append("): ").append(b.reason).append('\n')
            }
            append("Kasıtlı bir kaldırma ise sohbet kartından 'Değişmez Değişikliğini Onayla' ile onaylayın.")
        }.trim()
    }

    fun verify(
        tx: StagedTransaction,
        invariants: List<InvariantMiner.Invariant>,
        userApprovedIds: Set<String> = emptySet(),
    ): PatchProof = verifyChanges(
        tx.edits().map { Triple(it.path, it.before, it.after) },
        invariants,
        userApprovedIds,
    )

    fun verifyChanges(
        changes: List<Triple<String, String?, String?>>,
        invariants: List<InvariantMiner.Invariant>,
        userApprovedIds: Set<String> = emptySet(),
    ): PatchProof {
        if (invariants.isEmpty()) {
            return PatchProof(0, 0, emptyList(), hashOf("empty"), userApprovedIds)
        }
        val broken = ArrayList<BrokenInvariant>()
        for (inv in invariants) {
            val matchingChanges = changes.filter { (path, _, _) ->
                path == inv.targetPathPattern || path.endsWith(inv.targetPathPattern) || path.contains(inv.targetPathPattern)
            }
            if (matchingChanges.isEmpty()) continue
            for ((path, _, after) in matchingChanges) {
                if (after == null) {
                    broken += BrokenInvariant(inv, path, "Değişmez tarafından korunan dosya silindi: ${inv.description}")
                    continue
                }
                if (inv.requiredToken != null && !after.contains(inv.requiredToken)) {
                    broken += BrokenInvariant(inv, path, "Zorunlu referans '${inv.requiredToken}' kaldırıldı: ${inv.description}")
                }
                if (inv.forbiddenToken != null && after.contains(inv.forbiddenToken)) {
                    broken += BrokenInvariant(inv, path, "Yasaklı desen '${inv.forbiddenToken}' tespit edildi: ${inv.description}")
                }
                if (inv.minBytes > 0 && after.length < inv.minBytes) {
                    broken += BrokenInvariant(inv, path, "Dosya boyutu minimum tabanın (${inv.minBytes}) altına düştü (${after.length}).")
                }
            }
        }
        val unapprovedBroken = broken.filter { it.invariant.id !in userApprovedIds }
        val checked = invariants.size
        val passed = (checked - unapprovedBroken.map { it.invariant.id }.distinct().size).coerceAtLeast(0)
        val digestSeed = buildString {
            append(checked).append('/').append(passed).append('|')
            changes.forEach { (p, _, a) -> append(p).append(':').append(a?.hashCode() ?: 0).append(';') }
            unapprovedBroken.forEach { append(it.invariant.id).append(',') }
        }
        return PatchProof(
            checkedCount = checked,
            passedCount = passed,
            brokenInvariants = broken,
            proofHash = hashOf(digestSeed),
            approvedOverrides = userApprovedIds,
        )
    }

    private fun hashOf(seed: String): String =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(6)
}
