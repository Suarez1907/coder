package com.suarez.coder.evolution

import com.suarez.coder.harness.ForensicDiffEngine
import com.suarez.coder.security.ArchitecturalImmunity

/**
 * Faz 6B — REM / Rüya Modu Tekrar Analiz Motoru (`RemReplayEngine`).
 *
 * Kesin kurallar:
 * - Tamamen **READ_ONLY** analiz yapar; repoya hiçbir zaman dosya yazmaz, commit/push atmaz.
 * - Gece başına en fazla [maxTracesPerNight] (varsayılan 3) başarısız veya yüksek maliyetli/çok turlu
 *   [SessionTrace] kaydını inceler.
 * - Harcama [dailyBudgetUsd] (varsayılan 0.05 USD) tavanını asla aşmaz; yalnızca ucuz [EvolutionCompleter]
 *   (veya $0 deterministik çıkarım) kullanır.
 * - İki çıktı üretir:
 *   1) [ArchitecturalImmunity.ImmunityRule] kural adayları
 *   2) `EvolutionStore` / `CiLessonLearner` için tek satırlık kısa ders notları
 */
object RemReplayEngine {

    const val DEFAULT_MAX_TRACES_PER_NIGHT = 3
    const val DEFAULT_DAILY_BUDGET_USD = 0.05
    const val HIGH_COST_TRACE_USD = 0.15
    const val HIGH_TURN_THRESHOLD = 6

    data class Config(
        val enabled: Boolean = false,
        val dailyBudgetUsd: Double = DEFAULT_DAILY_BUDGET_USD,
        val maxTracesPerNight: Int = DEFAULT_MAX_TRACES_PER_NIGHT,
        val alreadySpentTodayUsd: Double = 0.0,
    ) {
        val remainingBudgetUsd: Double get() = (dailyBudgetUsd - alreadySpentTodayUsd).coerceAtLeast(0.0)
    }

    data class LessonNote(
        val repo: String,
        val stackTag: String,
        val text: String,
    )

    data class ReplayReport(
        val readOnly: Boolean = true,
        val tracesAnalyzed: Int,
        val immunityCandidates: List<ArchitecturalImmunity.ImmunityRule>,
        val lessons: List<LessonNote>,
        val spentUsd: Double,
        val stopReason: String,
    )

    /** Selects failed, high-cost, or multi-turn traces eligible for nightly REM replay. */
    fun selectCandidates(traces: List<SessionTrace>, limit: Int = DEFAULT_MAX_TRACES_PER_NIGHT): List<SessionTrace> =
        traces
            .filter { it.ciFailureCount > 0 || it.diffErrorCount > 0 || it.stopped || it.totalCostUsd >= HIGH_COST_TRACE_USD || it.turnCount >= HIGH_TURN_THRESHOLD }
            .sortedByDescending { (it.ciFailureCount * 3) + it.diffErrorCount + (if (it.stopped) 2 else 0) + (it.totalCostUsd * 10).toInt() }
            .take(limit.coerceIn(0, DEFAULT_MAX_TRACES_PER_NIGHT))

    /**
     * Runs strictly READ_ONLY nightly replay over selected [SessionTrace] entries.
     * Never writes to GitHub or modifies repository files.
     */
    suspend fun replay(
        traces: List<SessionTrace>,
        config: Config,
        completer: EvolutionCompleter? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ReplayReport {
        if (!config.enabled) {
            return ReplayReport(true, 0, emptyList(), emptyList(), 0.0, "REM / Rüya Modu kapalı.")
        }
        val selected = selectCandidates(traces, config.maxTracesPerNight)
        if (selected.isEmpty()) {
            return ReplayReport(true, 0, emptyList(), emptyList(), 0.0, "Analiz edilecek başarısız veya yüksek maliyetli iz yok.")
        }

        val candidates = ArrayList<ArchitecturalImmunity.ImmunityRule>()
        val lessons = ArrayList<LessonNote>()
        var spent = 0.0
        var analyzed = 0
        var stopReason = "REM analizi tamamlandı."

        for (trace in selected) {
            val remaining = (config.remainingBudgetUsd - spent).coerceAtLeast(0.0)
            val stackTag = inferStackTag(trace)

            // 1) Deterministic $0 forensic extraction from CI signatures & diff errors
            val rawSignals = (trace.ciSignatures + trace.diffErrorSamples).filter { it.isNotBlank() }
            for (sig in rawSignals.take(2)) {
                val diag = ForensicDiffEngine.diagnose(sig)
                val sym = diag.targets.firstOrNull()?.symbol
                val antiPattern = sym?.let { Regex.escape(it) } ?: Regex.escape(sig.take(60))
                val fixHint = when (diag.targets.firstOrNull()?.kind) {
                    ForensicDiffEngine.ErrorKind.UNRESOLVED_REFERENCE -> "Eksik import veya paket referansını ($sym) commit öncesi doğrula."
                    ForensicDiffEngine.ErrorKind.TYPE_MISMATCH -> "Çağrılan metodun parametre/dönüş tür imzasını hedef dosyadan doğrula."
                    ForensicDiffEngine.ErrorKind.MISSING_PARAMETER -> "Güncellenen fonksiyon çağrısında tüm zorunlu parametreleri geçir."
                    else -> "Commit öncesi diferansiyel doğrulama ve birim testlerini çalıştır."
                }
                candidates += ArchitecturalImmunity.ImmunityRule(
                    id = "rem_${trace.id}_${candidates.size}",
                    triggerPattern = Regex.escape(sig.take(80)),
                    forbiddenAntiPattern = antiPattern,
                    suggestedFix = fixHint,
                    hits = 1,
                    lastSeenMs = nowMs,
                    confidence = if (trace.ciFailureCount > 0) 0.78 else 0.65,
                    enabled = true,
                    stackTag = stackTag,
                )
            }

            // 2) Concise 1-line lesson note (uses cheap evolution model only if budget remains, else $0 deterministic)
            val deterministicLesson = buildDeterministicLesson(trace)
            var finalLesson = deterministicLesson
            if (completer != null && remaining > 1e-6) {
                val prompt = "Repo: ${trace.repo}\nİstek: ${trace.request.take(200)}\nCI Hataları: ${trace.ciSignatures.joinToString("; ")}\nDiff Hataları: ${trace.diffErrorSamples.joinToString("; ")}\nMaliyet: ${trace.totalCostUsd} USD, Tur: ${trace.turnCount}"
                val reply = runCatching {
                    completer.complete(
                        "Bu başarısız/maliyetli kodlama izinden tek satırlık (en fazla 160 karakter), genel ve uygulanabilir bir mühendislik dersi çıkar.",
                        prompt,
                        remaining,
                    )
                }.getOrNull()
                if (reply != null && reply.costUsd >= 0.0 && spent + reply.costUsd <= config.remainingBudgetUsd + 1e-9) {
                    spent += reply.costUsd
                    reply.text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(160)?.let { finalLesson = it }
                } else if (reply != null && spent + reply.costUsd > config.remainingBudgetUsd + 1e-9) {
                    stopReason = "REM günlük bütçe tavanına ulaşıldı (${config.dailyBudgetUsd} USD)."
                }
            }
            lessons += LessonNote(trace.repo, stackTag, finalLesson)
            analyzed++

            if (completer != null && spent >= config.remainingBudgetUsd - 1e-9) {
                stopReason = "REM günlük bütçe tavanına ulaşıldı (${config.dailyBudgetUsd} USD)."
                break
            }
        }

        return ReplayReport(
            readOnly = true,
            tracesAnalyzed = analyzed,
            immunityCandidates = candidates,
            lessons = lessons,
            spentUsd = spent,
            stopReason = stopReason,
        )
    }

    private fun buildDeterministicLesson(t: SessionTrace): String = when {
        t.ciFailureCount > 0 && t.ciSignatures.isNotEmpty() ->
            "CI hatası (${t.ciSignatures.first().take(70)}): commit öncesi imzaları ve importları doğrula."
        t.diffErrorCount > 0 ->
            "Tekrarlayan diff hatası (${t.diffErrorCount}): SEARCH bloğundan önce hedef dosyayı güncel oku."
        t.turnCount >= HIGH_TURN_THRESHOLD ->
            "Yüksek tur sayısı (${t.turnCount} tur): toplu dosya okuma ve iskelet görünümü ile turu azalt."
        else ->
            "Yüksek maliyetli oturum (${"%.3f".format(t.totalCostUsd)} USD): dar kapsamlı diff ve önbellek korumasını kullan."
    }.take(160)

    private fun inferStackTag(t: SessionTrace): String {
        val joined = (t.repo + " " + t.request + " " + t.ciSignatures.joinToString(" ")).lowercase()
        return when {
            "godot" in joined || ".gd" in joined || "gdscript" in joined -> "gdscript"
            "webos" in joined || "ipk" in joined || "ares-" in joined -> "webos"
            "gradle" in joined || ".kts" in joined -> "gradle"
            else -> "kotlin"
        }
    }
}
