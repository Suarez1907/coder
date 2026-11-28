package com.suarez.coder.harness

import com.suarez.coder.proof.InvariantMiner
import com.suarez.coder.proof.ProofCarryingPatchGate

/**
 * Faz 7A — Tip-Güdümlü Delik Sentezi (`TypedHoleSynthesizer`).
 *
 * Dosya iskeletini, import satırlarını ve fonksiyon/sınıf çerçevesini `$0` deterministik olarak kurar;
 * Uygulayıcı modele yalnızca net tip sözleşmeli mikro-görev verir:
 * `/* @HOLE id=h1 signature="fun filterRules(...): List<ScopedRule>" maxLines=30 */`
 *
 * Doldurma sırasında taban girintiyi (`baseIndent`) otomatik korur, [PreFlightValidator] +
 * [ProofCarryingPatchGate] ile anında doğrular; hata durumunda 1 kez yeniden doldurma veya
 * klasik `SearchReplace v2` akışına güvenli geri dönüş sinyali verir.
 */
object TypedHoleSynthesizer {

    data class HoleSpec(
        val id: String,
        val signature: String,
        val maxLines: Int = 30,
        val anchorLine: String? = null,
    )

    data class ProposalResult(
        val path: String,
        val skeletonWithHoles: String,
        val placedHoleIds: List<String>,
    )

    data class FillResult(
        val ok: Boolean,
        val updatedSource: String,
        val error: String? = null,
        val fallbackToSearchReplace: Boolean = false,
        val outputCharsSavedEstimate: Int = 0,
    )

    /** Builds a deterministic marker string for a typed hole. */
    fun holeMarker(spec: HoleSpec): String =
        "/* @HOLE id=${spec.id} signature=\"${spec.signature}\" maxLines=${spec.maxLines} */"

    /**
     * Inserts typed hole placeholders into [source] without asking the LLM to rewrite surrounding code.
     */
    fun proposeTypedHoles(path: String, source: String, holes: List<HoleSpec>): ProposalResult {
        var current = source
        val placed = ArrayList<String>()
        for (h in holes) {
            val marker = holeMarker(h)
            val block = "${h.signature} {\n    $marker\n}"
            val anchor = h.anchorLine
            if (!anchor.isNullOrBlank() && current.contains(anchor)) {
                val idx = current.indexOf(anchor)
                val lineStart = current.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
                val indent = current.substring(lineStart, idx).takeWhile { it == ' ' || it == '\t' }
                val indentedBlock = block.lines().joinToString("\n") { if (it.isBlank()) it else "$indent$it" }
                val lineEnd = current.indexOf('\n', idx).let { if (it < 0) current.length else it }
                current = current.substring(0, lineEnd) + "\n\n" + indentedBlock + current.substring(lineEnd)
                placed += h.id
            } else {
                val lastBrace = current.lastIndexOf('}')
                if (lastBrace >= 0) {
                    val indentedBlock = block.lines().joinToString("\n") { if (it.isBlank()) it else "    $it" }
                    current = current.substring(0, lastBrace) + "\n" + indentedBlock + "\n" + current.substring(lastBrace)
                } else {
                    current = current.trimEnd() + "\n\n" + block + "\n"
                }
                placed += h.id
            }
        }
        return ProposalResult(path, current, placed)
    }

    /**
     * Surgically replaces the `@HOLE` marker with [bodyCode], preserving [baseIndent],
     * and validates immediately with [PreFlightValidator] and [ProofCarryingPatchGate].
     */
    fun fillTypedHole(
        path: String,
        sourceWithHoles: String,
        holeId: String,
        bodyCode: String,
        invariants: List<InvariantMiner.Invariant> = emptyList(),
        attempt: Int = 1,
    ): FillResult {
        val markerRe = Regex("""([ \t]*)/\*\s*@HOLE\s+id=${Regex.escape(holeId)}\b[^*]*\*/""")
        val match = markerRe.find(sourceWithHoles)
            ?: return FillResult(false, sourceWithHoles, "Delik bulunamadı: $holeId", fallbackToSearchReplace = true)

        val baseIndent = match.groupValues[1]
        val normalizedBody = bodyCode.trimIndent().lines().joinToString("\n") { line ->
            if (line.isBlank()) "" else baseIndent + line
        }
        val updated = sourceWithHoles.replaceRange(match.range, normalizedBody)

        val preflight = PreFlightValidator.check(path, sourceWithHoles, updated)
        if (preflight.isNotEmpty()) {
            return FillResult(
                ok = false,
                updatedSource = sourceWithHoles,
                error = "Delik '$holeId' PreFlightValidator hatası: ${preflight.first().detail}",
                fallbackToSearchReplace = attempt >= 2,
            )
        }
        if (invariants.isNotEmpty()) {
            val proof = ProofCarryingPatchGate.verifyChanges(listOf(Triple(path, sourceWithHoles, updated)), invariants)
            if (proof.blocked) {
                return FillResult(
                    ok = false,
                    updatedSource = sourceWithHoles,
                    error = proof.blockMessage(),
                    fallbackToSearchReplace = attempt >= 2,
                )
            }
        }
        val savedChars = (updated.length - bodyCode.length).coerceAtLeast(0)
        return FillResult(
            ok = true,
            updatedSource = updated,
            outputCharsSavedEstimate = savedChars,
        )
    }
}
