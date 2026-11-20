package com.suarez.coder.decision

/**
 * Tier 1 of the zero-cost router: keyword and length rules, no API call.
 * Returns null when unsure so a cheaper-than-main-model classifier can decide.
 */
object LocalRouter {
    data class Verdict(val route: Route, val reason: String)

    const val SHORT_MESSAGE_CHARS = 120

    val SIMPLE_WORDS = listOf(
        "renk", "rengi", "metin", "yazı", "boyut", "padding", "margin", "typo", "yazım hatası", "hizala", "ikon", "label",
        "color", "colour", "text", "font", "size", "align", "icon", "başlık", "title", "kenar boşluğu", "punto",
    )
    val COMPLEX_WORDS = listOf(
        "yeni özellik", "özellik ekle", "refactor", "yeniden yapılandır", "mimari", "birden fazla", "tüm dosya", "bütün dosya",
        "seviye", "level", "entegre", "entegrasyon", "veritabanı", "database", "migration", "kimlik doğrulama", "authentication",
        "yeni ekran", "ekran ekle", "hata ayıkla", "debug", "çöküyor", "crash", "performans", "optimize", "modül", "feature",
    )
    private val QUESTION_STARTS = listOf(
        "nedir", "nasıl", "neden", "niçin", "ne işe", "ne yapar", "açıkla", "anlat", "hangi", "kaç",
        "what", "how", "why", "which", "explain", "is there", "does", "can you explain",
    )
    private val CHANGE_VERBS_TR = listOf(
        "ekle", "değiştir", "düzelt", "yap", "sil", "güncelle", "oluştur", "yaz", "kaldır", "taşı", "uygula", "çevir", "ayarla",
    )
    private val CHANGE_VERBS_EN = setOf("add", "change", "fix", "remove", "update", "create", "implement", "make", "rename", "delete")
    /** Polite requests phrased as questions ("ekler misin?") are still change requests. */
    private val REQUEST_MARKERS = listOf("misin", "mısın", "musun", "müsün", "misiniz", "mısınız", "lütfen", "please", "can you", "could you")

    /** Imperative-looking verb forms only ("ekle", "ekleyin"), not passive/progressive ones ("yapılıyor"). */
    private fun hasChangeVerb(t: String): Boolean {
        val words = Regex("[\\p{L}]+").findAll(t).map { it.value }.toList()
        return words.any { w ->
            w in CHANGE_VERBS_EN ||
                CHANGE_VERBS_TR.any { v -> w == v || (w.startsWith(v) && w.length <= v.length + 4 && !w.endsWith("yor") && !w.endsWith("miş") && !w.endsWith("mış")) }
        } || REQUEST_MARKERS.any { t.contains(it) }
    }

    fun classify(message: String, pinnedFiles: Int): Verdict? {
        val t = message.trim().lowercase()
        if (t.isEmpty()) return null
        val complex = COMPLEX_WORDS.firstOrNull { t.contains(it) }
        val simple = SIMPLE_WORDS.firstOrNull { t.contains(it) }
        val change = hasChangeVerb(t)
        val question = t.endsWith("?") || QUESTION_STARTS.any { t.startsWith(it) || t.contains(" $it ") }

        if (complex != null && t.length >= 25) return Verdict(Route.COMPLEX_ARCHITECT, "karmaşık iş: \"$complex\"")
        if (pinnedFiles == 1 && complex == null && !(question && !change)) return Verdict(Route.SIMPLE_EDIT, "tek dosya seçili")
        if (t.length < SHORT_MESSAGE_CHARS && simple != null && complex == null && !(question && !change)) {
            return Verdict(Route.SIMPLE_EDIT, "kısa UI/metin isteği: \"$simple\"")
        }
        if (question && !change) return Verdict(Route.QUESTION_ONLY, "soru kalıbı")
        return null
    }

    /** Parses the one-word answer of the tier-2 classifier. */
    fun parseOneWord(text: String): Route? = when (Regex("\\b(SIMPLE|COMPLEX|QUESTION)\\b").find(text.uppercase())?.value) {
        "SIMPLE" -> Route.SIMPLE_EDIT
        "COMPLEX" -> Route.COMPLEX_ARCHITECT
        "QUESTION" -> Route.QUESTION_ONLY
        else -> null
    }
}
