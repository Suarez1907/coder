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
    /** Only multi-file features and architecture go to the architect; fixes, debugging and small refactors do not. */
    val COMPLEX_WORDS = listOf(
        "yeni özellik", "özellik ekle", "yeniden yapılandır", "mimari", "birden fazla dosya", "tüm dosya", "bütün dosya",
        "seviye", "level", "entegre", "entegrasyon", "veritabanı", "database", "migration", "kimlik doğrulama", "authentication",
        "yeni ekran", "ekran ekle", "modül", "feature", "architecture", "büyük refactor", "tüm projeyi",
    )
    /** A clearly scoped fix: the executor handles it (with tools) without a plan. */
    val FIX_WORDS = listOf("düzelt", "hatayı", "hata ver", "bug", "fix", "çöküyor", "crash", "yeniden adlandır", "rename")
    /** Repository operations: a cheap model with full tools handles them; no planning needed. */
    val OPS_WORDS = listOf(
        "merge", "birleştir", "pull request", "pr aç", "pr oluştur", "release", "sürüm yayınla", "sürümü yayınla",
        "workflow", "iş akışı", "actions'ı çalıştır", "tag oluştur", "etiket oluştur", "branch oluştur", "dal oluştur",
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

    /** [learned]: (phrase, SIMPLE|QUESTION|COMPLEX) hints tuned by self-evolution, used only when the built-in rules are unsure. */
    fun classify(message: String, pinnedFiles: Int, learned: List<Pair<String, String>> = emptyList()): Verdict? {
        val raw = message.trim().lowercase()
        if (raw.isEmpty()) return null
        // Branch names and paths (feat/levels, src/level/A.kt) are names, not intent: leave them out of keyword matching.
        val t = raw.replace(Regex("\\S*[/\\\\]\\S*"), " ").replace(Regex("\\s+"), " ").trim()
        val complex = COMPLEX_WORDS.firstOrNull { t.contains(it) }
        val simple = SIMPLE_WORDS.firstOrNull { t.contains(it) }
        val change = hasChangeVerb(t)
        val question = t.endsWith("?") || QUESTION_STARTS.any { t.startsWith(it) || t.contains(" $it ") }

        if (complex != null && t.length >= 25) return Verdict(Route.COMPLEX_ARCHITECT, "karmaşık iş: \"$complex\"")
        OPS_WORDS.firstOrNull { t.contains(it) }?.let { op ->
            if (!(question && !change)) return Verdict(Route.SIMPLE_EDIT, "GitHub işlemi: \"$op\"")
        }
        if (pinnedFiles == 1 && complex == null && !(question && !change)) return Verdict(Route.SIMPLE_EDIT, "tek dosya seçili")
        // One file named in the request (path or file name with an extension) and no feature/architecture words.
        val namedFiles = Regex("[\\w./-]+\\.(kt|kts|java|js|mjs|ts|tsx|jsx|py|go|rs|swift|dart|c|cpp|h|cs|rb|php|html|css|xml|json|toml|yml|yaml|gradle|md)\\b")
            .findAll(raw).map { it.value }.toSet()
        if (namedFiles.size == 1 && complex == null && change) return Verdict(Route.SIMPLE_EDIT, "tek dosya: ${namedFiles.first()}")
        val fix = FIX_WORDS.firstOrNull { t.contains(it) }
        if (fix != null && complex == null && change && t.length < 400) return Verdict(Route.SIMPLE_EDIT, "kapsamı net düzeltme: \"$fix\"")
        if (t.length < SHORT_MESSAGE_CHARS && simple != null && complex == null && !(question && !change)) {
            return Verdict(Route.SIMPLE_EDIT, "kısa UI/metin isteği: \"$simple\"")
        }
        if (question && !change) return Verdict(Route.QUESTION_ONLY, "soru kalıbı")
        learned.firstOrNull { (p, _) -> p.isNotBlank() && t.contains(p.lowercase()) }?.let { (p, r) ->
            val route = when (r) { "SIMPLE" -> Route.SIMPLE_EDIT; "QUESTION" -> Route.QUESTION_ONLY; "COMPLEX" -> Route.COMPLEX_ARCHITECT; else -> null }
            if (route != null) return Verdict(route, "öğrenilmiş ipucu: \"$p\"")
        }
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
