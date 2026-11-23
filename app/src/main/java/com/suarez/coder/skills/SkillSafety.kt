package com.suarez.coder.skills

/**
 * A third-party skill becomes instructions for the agent, so it is checked before import for the usual prompt
 * injection and exfiltration patterns. Findings are shown to the user (and the skill is imported disabled);
 * invisible characters are removed outright.
 */
object SkillSafety {
    data class Finding(val file: String, val line: Int, val reason: String, val excerpt: String)

    private class Rule(val re: Regex, val reason: String, val negatable: Boolean = true)

    private val RULES: List<Rule> = listOf(
        Rule(Regex("(?i)\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|all|system|earlier)\\b.{0,20}\\b(instructions?|rules?|prompts?|messages?)"),
            "önceki talimatları geçersiz kılmaya çalışıyor"),
        Rule(Regex("(?i)\\b(you are now (a|an|the|in)\\b|pretend to be|new persona|jailbreak|developer mode|dan mode|do anything now)"), "rol/kimlik değiştirmeye çalışıyor"),
        Rule(Regex("(?i)\\b(do not|don'?t|never)\\b.{0,30}\\b(tell|inform|show|mention|reveal (it|this) to)\\b.{0,20}\\b(the )?user"), "kullanıcıdan bir şey gizlemesini istiyor", negatable = false),
        Rule(Regex("(?i)\\b(github_?token|api[_ -]?key|secrets?|password|private key|ssh key|access token)\\b.{0,60}\\b(send|post|upload|exfiltrat\\w*|curl|wget)\\b.{0,40}\\b(https?://|url|server|endpoint|webhook)"),
            "gizli anahtarları dışarı göndermeyi içeriyor"),
        Rule(Regex("(?i)\\b(curl|wget|iwr|Invoke-WebRequest)\\b[^\\n|]{0,200}\\|\\s*(sudo\\s+)?(sh|bash|zsh|python3?|powershell|iex)\\b"), "internetten indirileni doğrudan çalıştırıyor"),
        Rule(Regex("(?i)\\b(auto[- ]?approve|bypass (the )?(approval|confirmation|safety|guard|review|permission)s?|skip (the )?(approval|confirmation|safety check)s?|disable (the )?(safety|guard|approval)s?)\\b"),
            "onay veya güvenlik adımlarını atlatmayı öneriyor"),
        Rule(Regex("(?i)(rm\\s+-rf\\s+(/|~)(\\s|$)|\\bforce[- ]push\\b|\\bpush\\s+(--force\\b|-f\\b)|\\bdelete (the )?(repo|repository)\\b)"), "yıkıcı bir işlem içeriyor"),
        Rule(Regex("<!--(?s:.){0,400}?(instruction|assistant|claude|ignore|system)(?s:.){0,400}?-->", RegexOption.IGNORE_CASE), "HTML yorumu içine gizlenmiş talimat", negatable = false),
        Rule(Regex("[A-Za-z0-9+/]{300,}={0,2}"), "uzun base64 benzeri gizli içerik", negatable = false),
    )

    /**
     * Skills often name a bad practice to forbid it ("Never force-push") or quote an injection so the agent can spot
     * one ("e.g. 'ignore previous instructions'"). A negation or an example marker just before the match clears it.
     */
    private val NEGATION = Regex("(?i)(\\b(never|don'?t|do not|avoid|no|not|without|instead of|forbid\\w*|refuse|reject|watch (out )?for|beware|such as|e\\.g\\.|for example|like|detect\\w*|flag\\w*|injection)\\b|[\"'`“]\\s*$)")

    private fun negated(text: String, start: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val before = text.substring(maxOf(lineStart, start - 60), start)
        return NEGATION.containsMatchIn(before)
    }

    /** Zero-width and bidi-control characters (used to hide text from humans). */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")

    fun clean(text: String): String = INVISIBLE.replace(text, "")

    fun scan(files: Map<String, String>): List<Finding> {
        val out = ArrayList<Finding>()
        for ((name, raw) in files) {
            if (INVISIBLE.containsMatchIn(raw)) out += Finding(name, 0, "görünmez karakterler (temizlendi)", "")
            val text = clean(raw)
            for (rule in RULES) {
                val re = rule.re; val reason = rule.reason
                re.findAll(text).filter { m -> !rule.negatable || !negated(text, m.range.first) }.take(2).forEach { m ->
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    out += Finding(name, line, reason, m.value.replace(Regex("\\s+"), " ").take(120))
                }
            }
        }
        return out.distinctBy { Triple(it.file, it.line, it.reason) }.take(20)
    }

    /** Scans a skill's SKILL.md and bundled files. */
    fun scan(s: Skill): List<Finding> = scan(mapOf("SKILL.md" to s.toMarkdown()) + s.resources)
}
