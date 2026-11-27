package com.suarez.coder.evolution

import com.suarez.coder.ci.LogFilter

/**
 * Turns a red CI log into a pending lesson (via [LogFilter]) and stores it as a [RuleSource.CI_VERIFIED]
 * scoped rule only after a later CI run on the same repo goes green. A red log alone never becomes a rule.
 */
class CiLessonLearner(private val rules: ScopedRuleStore) {
    data class Pending(val repo: String, val lesson: String, val globs: List<String>, val filtered: String)

    private var pending: Pending? = null

    /** Remembers a lesson when the filtered log still contains a real error line. */
    fun onRed(repo: String, rawLog: String): Pending? {
        if (repo.isBlank()) return null
        val filtered = LogFilter.extract(rawLog)
        if (filtered.contains("(Belirgin hata satırı bulunamadı")) {
            pending = null
            return null
        }
        val line = filtered.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("…") } ?: return null
        val lesson = ("CI tekrarını önle: " + ErrorClusterEngine.anonymize(line)).take(ScopedRuleStore.MAX_TEXT)
        if (lesson.length < 8) return null
        val held = Pending(repo, lesson, globsFor(filtered), filtered)
        pending = held
        return held
    }

    /** Stores the pending lesson after the follow-up CI is green. Returns null when nothing was waiting. */
    fun onGreen(repo: String): ScopedRule? {
        val held = pending ?: return null
        if (!held.repo.equals(repo, true)) return null
        pending = null
        return rules.add(held.repo, held.globs, held.lesson, RuleSource.CI_VERIFIED)
    }

    fun pendingLesson(): Pending? = pending

    companion object {
        fun globsFor(filtered: String): List<String> {
            val paths = Regex("""[\w./-]+\.(kt|kts|java|py|gd|yml|yaml|gradle)""").findAll(filtered).map { it.value }.distinct().take(3).toList()
            if (paths.isEmpty()) return listOf("*")
            return paths.map { p ->
                val name = p.substringAfterLast('/')
                if (name.isBlank()) "*" else "**/$name"
            }
        }
    }
}
