package com.suarez.coder.repair

import com.suarez.coder.harness.PreFlightValidator
import com.suarez.coder.harness.StagedTransaction
import java.security.MessageDigest

/**
 * Faz 9 — `$0` Hata-İmza Yama Hafızası (`ErrorFixCache`).
 *
 * CI yeşilinden geçmiş küçük (`<= 15` satır) ve tekrarlanabilir hata düzeltmelerini
 * `(normalizedErrorHash -> DeterministicPatchTemplate)` olarak saklar ve `<20 ms` içinde uygular.
 * Ardışık veya toplam [MAX_FAILURES_BEFORE_DISABLE] (2) başarısız uygulamada kural otomatik pasifize edilir.
 */
class ErrorFixCache {

    companion object {
        const val MAX_PATCH_LINES = 15
        const val MAX_FAILURES_BEFORE_DISABLE = 2
    }

    enum class TemplateKind { MISSING_IMPORT, GRADLE_FLAG, ANNOTATION_FIX, API_DEPRECATION, PROGUARD_RULE }

    data class PatchTemplate(
        val errorHash: String,
        val kind: TemplateKind,
        val findLiteral: String,
        val replaceLiteral: String,
        val verifiedSuccessCount: Int = 1,
        val failureCount: Int = 0,
        val enabled: Boolean = true,
        val lastUsedAtMs: Long = System.currentTimeMillis(),
    )

    data class RecallResult(
        val hit: Boolean,
        val applied: Boolean,
        val staged: StagedTransaction,
        val elapsedMs: Long,
        val costUsd: Double = 0.0,
        val reason: String,
    )

    private val entries = LinkedHashMap<String, PatchTemplate>()

    fun normalizeErrorHash(rawError: String): String {
        val norm = rawError.trim()
            .replace(Regex("""file://[^\s:]+/"""), "")
            .replace(Regex(""":\d+(?::\d+)?"""), ":<L>")
            .replace(Regex("""\s+"""), " ")
            .lowercase()
            .take(200)
        return MessageDigest.getInstance("SHA-256").digest(norm.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Records a CI-green reusable patch template (rejects patches > 15 lines). */
    @Synchronized
    fun recordGreenFix(
        rawError: String,
        kind: TemplateKind,
        findLiteral: String,
        replaceLiteral: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PatchTemplate? {
        if (findLiteral.isBlank() || replaceLiteral.lines().size > MAX_PATCH_LINES) return null
        val hash = normalizeErrorHash(rawError)
        val existing = entries[hash]
        val next = if (existing != null && existing.findLiteral == findLiteral && existing.replaceLiteral == replaceLiteral) {
            existing.copy(verifiedSuccessCount = existing.verifiedSuccessCount + 1, enabled = true, lastUsedAtMs = nowMs)
        } else {
            PatchTemplate(hash, kind, findLiteral, replaceLiteral, verifiedSuccessCount = 1, failureCount = 0, enabled = true, lastUsedAtMs = nowMs)
        }
        entries[hash] = next
        return next
    }

    /**
     * Attempts instant `<20 ms` `$0` recall and applies template onto [files].
     * On failure, increments `failureCount` and auto-disables rule after 2 failures.
     */
    @Synchronized
    fun tryRecallAndApply(
        rawError: String,
        files: Map<String, String>,
        verifyPostApply: (StagedTransaction) -> Boolean = { true },
        nowMs: Long = System.currentTimeMillis(),
    ): RecallResult {
        val t0 = System.nanoTime()
        val hash = normalizeErrorHash(rawError)
        val tpl = entries[hash]
        if (tpl == null || !tpl.enabled || tpl.verifiedSuccessCount < 1) {
            val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
            return RecallResult(false, false, StagedTransaction(), ms, 0.0, "Aktif hata-imza kaydı yok.")
        }

        val tx = StagedTransaction()
        for ((path, content) in files) {
            if (content.contains(tpl.findLiteral)) {
                val after = content.replace(tpl.findLiteral, tpl.replaceLiteral)
                if (after != content) tx.stage(path, content, after)
            }
        }
        val preflightOk = tx.edits().isNotEmpty() &&
            tx.edits().flatMap { PreFlightValidator.check(it.path, it.before, it.after) }.isEmpty() &&
            verifyPostApply(tx)

        val ms = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        if (preflightOk) {
            entries[hash] = tpl.copy(verifiedSuccessCount = tpl.verifiedSuccessCount + 1, lastUsedAtMs = nowMs)
            return RecallResult(true, true, tx, ms, 0.0, "Hata-imza önbelleğinden $0 maliyetle uygulandı.")
        } else {
            tx.clear()
            val fails = tpl.failureCount + 1
            entries[hash] = tpl.copy(
                failureCount = fails,
                enabled = fails < MAX_FAILURES_BEFORE_DISABLE,
                lastUsedAtMs = nowMs,
            )
            return RecallResult(true, false, StagedTransaction(), ms, 0.0, "Önbellek yaması doğrulamayı geçemedi (hata: $fails/$MAX_FAILURES_BEFORE_DISABLE).")
        }
    }

    @Synchronized fun get(rawError: String): PatchTemplate? = entries[normalizeErrorHash(rawError)]
    @Synchronized fun size(): Int = entries.size
}
