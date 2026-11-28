package com.suarez.coder.ci

/**
 * AutoHealer: kırmızı bir CI koşusuna bakıp "şimdi ne yapmalı" kararını verir. Saf fonksiyon (ağ, model, disk yok):
 * böylece test edilebilir ve ajan döngüsünden bağımsız çalışır. Sıra: geçici hata → yeniden çalıştır, sadece bilinen
 * flaky testler → yeniden çalıştır, ders defterinde çözüm var → bilinen çözümü dene, çok tur oldu → kullanıcıya sor,
 * aksi halde güçlü modele yükselt.
 */
object AutoHealer {
    enum class Action(val tr: String) {
        RERUN("yeniden çalıştır"), USE_KNOWN_FIX("bilinen çözümü dene"), ESCALATE("güçlü modele yükselt"), ASK("kullanıcıya sor");
    }

    data class Decision(
        val action: Action,
        val cause: CiKnowledgeBase.Cause,
        val signature: String,
        val tests: Set<String>,
        val hint: String?,
    )

    /** Sohbet zaman çizelgesine düşen tek satır. */
    fun notice(d: Decision): String =
        "CI teşhisi · ${d.cause.tr}: ${d.action.tr} — " + d.signature.lineSequence().firstOrNull().orEmpty().take(140)

    /** İlk anlamlı hata satırı (özet satırları ve kısaltma işaretleri atlanır). */
    fun signature(log: String): String =
        log.lines().map { it.trim() }.firstOrNull { l ->
            l.isNotBlank() && !l.startsWith("##") && !l.startsWith("…") && !l.startsWith("(") &&
            !l.startsWith("Commit ") && !l.startsWith("---") && !l.startsWith("Job:") && !l.startsWith("Run ")
        }.orEmpty().take(300)

    fun decide(
        log: String,
        repo: String,
        kb: CiKnowledgeBase? = null,
        knownFlaky: Set<String> = emptySet(),
        round: Int = 1,
    ): Decision {
        val sig = signature(log).ifBlank { signature(LogFilter.extract(log)) }
        val cause = CiKnowledgeBase.Cause.of(if (sig.isBlank()) log else sig)
        val tests = Flaky.failedTests(log)
        val hint = kb?.let { k -> if (sig.isBlank()) null else k.hint(k.lookup(repo, sig)) }
        val action = when {
            Flaky.isTransient(log) -> Action.RERUN
            tests.isNotEmpty() && knownFlaky.containsAll(tests) -> Action.RERUN
            hint != null && cause != CiKnowledgeBase.Cause.INFRA -> Action.USE_KNOWN_FIX
            round >= 3 -> Action.ASK
            else -> Action.ESCALATE
        }
        return Decision(action, cause, sig, tests, hint)
    }
}
