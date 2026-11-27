package com.suarez.coder.harness

import com.suarez.coder.llm.ModelRef
import java.security.MessageDigest

/**
 * Tracks the last [capacity] failure attempts in a ring buffer. Detects consecutive repeats and A->B->A
 * oscillations, escalating at most once per task to the provided locked architect model.
 */
class OscillationBreaker(
    private val lockedArchitect: ModelRef,
    private val capacity: Int = 6
) {
    data class Attempt(val diffHash: String, val errorSignature: String, val summary: String)

    enum class Kind { NONE, CONSECUTIVE_REPEAT, A_B_A_OSCILLATION }

    enum class Action { CONTINUE, ESCALATE_TO_ARCHITECT, ABORT_CLEANLY }

    data class Verdict(
        val action: Action,
        val kind: Kind,
        val targetModel: ModelRef?,
        val internalMessage: String?,
        val userNotice: String?
    )

    private val ring = ArrayDeque<Attempt>(capacity)
    private var _escalationsCount = 0

    val escalationsCount: Int get() = _escalationsCount

    fun reset() {
        ring.clear()
        _escalationsCount = 0
    }

    fun recordFailure(diffHash: String, errorSignature: String, summary: String): Verdict {
        val attempt = Attempt(diffHash, errorSignature, summary)
        if (ring.size >= capacity) {
            ring.removeFirst()
        }
        ring.addLast(attempt)

        val size = ring.size
        if (size < 2) {
            return Verdict(Action.CONTINUE, Kind.NONE, null, null, null)
        }

        val list = ring.toList()
        val cur = list[size - 1]
        val prev1 = list[size - 2]
        val prev2 = if (size >= 3) list[size - 3] else null

        val isConsecutive = (cur.diffHash.isNotEmpty() && cur.diffHash == prev1.diffHash) ||
            (cur.errorSignature.isNotEmpty() && cur.errorSignature == prev1.errorSignature)

        val isAba = !isConsecutive && prev2 != null && (
            (cur.diffHash.isNotEmpty() && cur.diffHash == prev2.diffHash) ||
            (cur.errorSignature.isNotEmpty() && cur.errorSignature == prev2.errorSignature)
        )

        val kind = when {
            isConsecutive -> Kind.CONSECUTIVE_REPEAT
            isAba -> Kind.A_B_A_OSCILLATION
            else -> Kind.NONE
        }

        if (kind == Kind.NONE) {
            return Verdict(Action.CONTINUE, Kind.NONE, null, null, null)
        }

        if (_escalationsCount == 0) {
            _escalationsCount++
            val historySummary = list.takeLast(3).mapIndexed { idx, att ->
                "Deneme #${size - 2 + idx}: diff=${att.diffHash.take(8)}, hata=${att.errorSignature.take(60)}, özet=${att.summary}"
            }.joinToString("\n")

            val internalMsg = "Salınım tespit edildi ($kind). Aynı yaklaşım veya hata tekrarlandı. Döngü geçmişi:\n$historySummary\nTalimat: Aynı kod değişikliğini veya hatayı üreten yaklaşımı kesinlikle tekrarlama. Temel sebebi analiz et ve kökten alternatif bir çözüm uygula."

            return Verdict(
                action = Action.ESCALATE_TO_ARCHITECT,
                kind = kind,
                targetModel = lockedArchitect,
                internalMessage = internalMsg,
                userNotice = "Salınım kesici: tekrarlayan hata döngüsü yakalandı, kilitli Mimar modeline devredildi"
            )
        }

        // Already escalated once: clean abort without looping infinitely
        return Verdict(
            action = Action.ABORT_CLEANLY,
            kind = kind,
            targetModel = null,
            internalMessage = "Salınım kesici: Mimar eskalasyonu sonrasında da hata döngüsü devam etti. Görev güvenli biçimde durduruldu.",
            userNotice = "Salınım kesici: tekrarlayan hata döngüsü çözülemediği için görev temiz bir şekilde sonlandırıldı"
        )
    }

    companion object {
        fun normalizeDiffHash(rawDiffOrContent: String): String {
            val normalized = rawDiffOrContent.replace("\r\n", "\n")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            if (normalized.isBlank()) return ""
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(normalized.toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }

        fun normalizeErrorSignature(rawError: String): String {
            val lines = rawError.replace("\r\n", "\n").lines()
            val filtered = lines
                .map { line ->
                    line.replace(Regex("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[^\\s]*"), "")
                        .replace(Regex("runId=\\w+"), "")
                        .replace(Regex("\\b0x[0-9a-fA-F]+\\b"), "")
                        .trim()
                }
                .filter { it.isNotBlank() }
                .take(3)
            return filtered.joinToString(" | ")
        }
    }
}
