package com.suarez.coder.agent

import com.suarez.coder.decision.Route

/**
 * Salt okunur / adaptif yönlendirme çakışmasını çözer: READ_ONLY bir model kimliği değil, çalışma fazıdır.
 * Yalnızca gerçek bir soru görevinde veya Mimar'ın planlama adımında (uygulayıcı devri garantiliyse) kullanılır.
 * Yazma niyetli bir görevde model "yetkim yok" derse oturum bir kez dinamik READ_WRITE'a yükseltilir.
 */
object ReadOnlyMismatchRecovery {
    /** Oturum başına en fazla bu kadar otomatik yükseltme yapılır; sonrası Uygulayıcı'ya devredilir. */
    const val MAX_UPGRADES = 1

    private val WRITE_INTENT = Regex(
        "(?i)\\b(düzelt|düzeltir|ekle|yaz|oluştur|güncelle|değiştir|kaldır|sil|commit|push|apply|fix|implement|add|update|" +
            "refactor|rename|merge|deploy|taşı|düzenle|refactor)\\b",
    )

    private val NO_ACCESS = Regex(
        "(?i)(yetkim yok|yetki yok|yetkimiz yok|salt okunur|salt-okunur|read[- ]?only|yazma iznim yok|yazma yetkim yok|" +
            "izin verilmedi|erişimim yok|not allowed to (write|edit)|cannot (write|edit|modify)|permission denied)",
    )

    enum class Action { UPGRADE, HANDOFF }

    /** Görev metni yazma niyeti taşıyor mu? (Sorular ve analiz istekleri false.) */
    fun hasWriteIntent(task: String): Boolean = WRITE_INTENT.containsMatchIn(task)

    /** READ_ONLY yalnızca kesin bir QUESTION görevinde ve yazma niyeti yoksa anlamlıdır. */
    fun readOnlyForRoute(route: Route?, task: String): Boolean =
        route == Route.QUESTION_ONLY && !hasWriteIntent(task)

    /** Modelin "yetkim yok" / salt okunur yanıtı gerçek bir yetki çakışması mı? */
    fun isMismatch(text: String, writeIntent: Boolean, readOnly: Boolean): Boolean =
        readOnly && writeIntent && NO_ACCESS.containsMatchIn(text)

    /** Sıradaki adım: bir kez yükselt, tükendiyse Uygulayıcı'ya devret. */
    fun next(upgrades: Int): Action = if (upgrades < MAX_UPGRADES) Action.UPGRADE else Action.HANDOFF

    /** Sohbet zaman çizelgesinde gösterilen tek satırlık şeffaf durum kaydı. */
    fun statusLine(action: Action): String = when (action) {
        Action.UPGRADE -> "Yetki kurtarma: yazma niyetli görev salt okunur yanıtla karşılaştı; oturum READ_WRITE'a yükseltildi."
        Action.HANDOFF -> "Yetki kurtarma: yükseltme yetersiz; görev READ_WRITE Uygulayıcı'ya devredildi."
    }

    /** Görev başında fazı şeffafça duyuran tek satır (yalnızca salt okunur fazdaysa gösterilir). */
    fun phaseLine(): String = "Yetki fazı: READ_ONLY. Görev yazma gerektirirse oturum bir kez otomatik READ_WRITE'a yükseltilir; " +
        "yetersizse görev yazma yetkili Uygulayıcı'ya devredilir."

    /** Yükseltme sonrası modele verilen sentetik (harness içi) mesaj. */
    fun upgradePrompt(): String = Agent.AUTO_NUDGE +
        "Yetki kurtarma: bu görev yazma niyetli. Oturum READ_WRITE'a yükseltildi; " +
        "github_apply_diff / github_write_files ve commit araçlarını artık kullanabilirsin. " +
        "Değişikliği uygula, commit et ve doğrula."
}
