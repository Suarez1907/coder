package com.suarez.coder.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class RuleSource { REFLECTOR, CI_VERIFIED, MANUAL }

/**
 * One conditional lesson. It is never copied into the stable system prompt: [selectForTurn] only returns rules whose
 * globs match the files of the current turn, and the caller appends that block to the volatile task context.
 */
data class ScopedRule(
    val id: String,
    val repo: String,
    val scopeGlobs: List<String>,
    val ruleText: String,
    val source: RuleSource,
    val hitCount: Int = 0,
    val preventedErrorCount: Int = 0,
    val createdAt: Long = 0L,
    val lastMatchedAt: Long = 0L,
    val archived: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("repo", repo).put("globs", JSONArray(scopeGlobs)).put("text", ruleText)
        .put("source", source.name).put("hits", hitCount).put("prevented", preventedErrorCount)
        .put("created", createdAt).put("matched", lastMatchedAt).put("archived", archived)

    companion object {
        fun fromJson(o: JSONObject) = ScopedRule(
            o.getString("id"), o.optString("repo"),
            o.optJSONArray("globs")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
            o.optString("text"),
            runCatching { RuleSource.valueOf(o.optString("source")) }.getOrDefault(RuleSource.REFLECTOR),
            o.optInt("hits"), o.optInt("prevented"), o.optLong("created"), o.optLong("matched"), o.optBoolean("archived"),
        )
    }
}

/**
 * Per-repo conditional memory with a hard active cap and decay. Archived rules stay on disk so they can be restored.
 */
class ScopedRuleStore(private val file: File) {
    @Synchronized fun all(): List<ScopedRule> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { runCatching { ScopedRule.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized fun active(repo: String) = all().filter { it.repo.equals(repo, true) && !it.archived }

    /**
     * Adds a rule, archiving the weakest non-manual rule when the repo already has [MAX_ACTIVE] active ones.
     * Returns null when the text is empty or every active slot is a manual rule.
     */
    @Synchronized fun add(
        repo: String,
        scopeGlobs: List<String>,
        ruleText: String,
        source: RuleSource,
        preventedErrorCount: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
        lastMatchedAt: Long = 0L,
        hitCount: Int = 0,
    ): ScopedRule? {
        val text = ruleText.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)
        if (text.length < 8 || repo.isBlank()) return null
        val globs = scopeGlobs.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("*") }
        var rules = all().toMutableList()
        val repoActive = rules.filter { it.repo.equals(repo, true) && !it.archived }
        if (repoActive.size >= MAX_ACTIVE) {
            val victim = repoActive.filter { it.source != RuleSource.MANUAL }.minWithOrNull(WEAK) ?: return null
            rules = rules.map { if (it.id == victim.id) it.copy(archived = true) else it }.toMutableList()
        }
        val rule = ScopedRule(
            UUID.randomUUID().toString().take(8), repo, globs, text, source,
            hitCount, preventedErrorCount.coerceAtLeast(0), createdAt, lastMatchedAt, archived = false,
        )
        rules += rule
        save(rules)
        mirrorNotes(rules)
        return rule
    }

    @Synchronized fun archive(id: String): Boolean = setArchived(id, true)

    @Synchronized fun restore(id: String): Boolean {
        val rules = all()
        val rule = rules.firstOrNull { it.id == id } ?: return false
        if (!rule.archived) return true
        if (rules.count { it.repo.equals(rule.repo, true) && !it.archived } >= MAX_ACTIVE) return false
        return setArchived(id, false)
    }

    /** Non-manual rules with no match for [MAX_IDLE_MS] are archived, never deleted. */
    @Synchronized fun decay(now: Long, maxIdleMs: Long = MAX_IDLE_MS): Int {
        val rules = all()
        var n = 0
        val next = rules.map { r ->
            val seen = if (r.lastMatchedAt > 0) r.lastMatchedAt else r.createdAt
            if (!r.archived && r.source != RuleSource.MANUAL && seen > 0 && now - seen >= maxIdleMs) {
                n++; r.copy(archived = true)
            } else r
        }
        if (n > 0) { save(next); mirrorNotes(next) }
        return n
    }

    /**
     * Up to [limit] active rules whose globs match [paths], most specific first.
     * Updates hitCount and lastMatchedAt for the rules that are actually injected.
     */
    @Synchronized fun selectForTurn(repo: String, paths: List<String>, now: Long = System.currentTimeMillis(), limit: Int = MAX_PER_TURN): List<ScopedRule> {
        val clean = paths.map { it.trim().removePrefix("./") }.filter { it.isNotBlank() }
        if (repo.isBlank() || clean.isEmpty()) return emptyList()
        val rules = all()
        val ranked = rules.filter { it.repo.equals(repo, true) && !it.archived && it.scopeGlobs.any { g -> clean.any { p -> globMatches(g, p) } } }
            .sortedWith(RANK)
            .take(limit)
        if (ranked.isEmpty()) return emptyList()
        val ids = ranked.map { it.id }.toSet()
        save(rules.map { if (it.id in ids) it.copy(hitCount = it.hitCount + 1, lastMatchedAt = now) else it })
        return ranked.map { it.copy(hitCount = it.hitCount + 1, lastMatchedAt = now) }
    }

    /** Readable summary of active rules (broader / top-level globs first), capped for .coder/NOTES.md. */
    fun notesSummary(repo: String, rules: List<ScopedRule> = all()): String {
        val active = rules.filter { it.repo.equals(repo, true) && !it.archived }
            .sortedBy { it.scopeGlobs.maxOfOrNull { g -> specificity(g) } ?: 0 }
        val lines = active.map { r -> "- [${r.scopeGlobs.joinToString(", ")}] ${r.ruleText}" }
        val body = (listOf("## Proje kuralları") + lines).joinToString("\n")
        return if (body.length <= EvolutionGuard.NOTES_LIMIT) body else body.take(EvolutionGuard.NOTES_LIMIT - 1).trimEnd() + "…"
    }

    /**
     * Merges [notesSummary] into an existing NOTES.md body without dropping the user's older lessons.
     * The scoped section replaces itself when it is already present.
     */
    fun syncNotes(existing: String, repo: String, rules: List<ScopedRule> = all()): String {
        val section = "$NOTES_START\n${notesSummary(repo, rules)}\n$NOTES_END"
        val merged = if (existing.contains(NOTES_START) && existing.contains(NOTES_END)) {
            existing.replace(Regex("${Regex.escape(NOTES_START)}[\\s\\S]*?${Regex.escape(NOTES_END)}"), section)
        } else {
            val base = existing.trimEnd()
            if (base.isBlank()) section else "$base\n\n$section"
        }
        return if (merged.length <= EvolutionGuard.NOTES_LIMIT) merged else EvolutionGuard.hardPrune(merged)
    }

    fun notesMirror(repo: String): String {
        val f = mirrorFile(repo)
        return if (f.isFile) f.readText() else ""
    }

    private fun setArchived(id: String, archived: Boolean): Boolean {
        val rules = all()
        if (rules.none { it.id == id }) return false
        val next = rules.map { if (it.id == id) it.copy(archived = archived) else it }
        save(next)
        mirrorNotes(next)
        return true
    }

    private fun save(rules: List<ScopedRule>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } }.toString())
    }

    private fun mirrorNotes(rules: List<ScopedRule>) {
        rules.map { it.repo }.distinct().forEach { repo ->
            val f = mirrorFile(repo)
            f.parentFile?.mkdirs()
            f.writeText(syncNotes(if (f.isFile) f.readText() else "", repo, rules))
        }
    }

    private fun mirrorFile(repo: String) = File(file.parentFile, "notes-" + repo.lowercase().replace(Regex("[^a-z0-9]+"), "_") + ".md")

    companion object {
        const val MAX_ACTIVE = 40
        const val MAX_PER_TURN = 5
        const val MAX_TEXT = 220
        const val MAX_IDLE_MS = 30L * 24 * 60 * 60 * 1000
        const val NOTES_START = "<!-- coder-scoped-rules -->"
        const val NOTES_END = "<!-- /coder-scoped-rules -->"
        const val BLOCK_HEADER = "[PROJE KURALLARI - Eşleşen Kapsam]"

        private val WEAK = compareBy<ScopedRule> { it.preventedErrorCount }.thenBy { it.lastMatchedAt }.thenBy { it.createdAt }
        private val RANK = compareByDescending<ScopedRule> { it.scopeGlobs.maxOfOrNull { g -> specificity(g) } ?: 0 }
            .thenByDescending { if (it.source == RuleSource.CI_VERIFIED) 1 else 0 }
            .thenByDescending { it.preventedErrorCount }
            .thenByDescending { it.lastMatchedAt }

        /** Compact block appended to the turn context. Not a system prompt. */
        fun formatBlock(rules: List<ScopedRule>): String? {
            if (rules.isEmpty()) return null
            return rules.joinToString("\n", prefix = BLOCK_HEADER + "\n") { r ->
                "- (${r.scopeGlobs.joinToString(", ")}) ${r.ruleText}"
            }
        }

        fun specificity(glob: String): Int {
            val g = glob.trim()
            if (g == "*" || g == "**" || g == "**/*") return 0
            var score = 0
            var i = 0
            while (i < g.length) {
                when {
                    g.startsWith("**", i) -> { score += 1; i += 2 }
                    g[i] == '*' || g[i] == '?' -> { score += 2; i++ }
                    else -> { score += 10; i++ }
                }
            }
            return score
        }

        /** `*` matches every path. `**` crosses directories; a single `*` does not. */
        fun globMatches(glob: String, path: String): Boolean {
            val g = glob.trim().removePrefix("./")
            val p = path.trim().removePrefix("./").replace('\\', '/')
            if (g.isBlank() || p.isBlank()) return false
            if (g == "*" || g == "**" || g == "**/*") return true
            val pattern = buildString {
                append('^')
                var i = 0
                while (i < g.length) {
                    when {
                        g.startsWith("**/", i) -> { append("(?:.*/)?"); i += 3 }
                        g.startsWith("**", i) -> { append(".*"); i += 2 }
                        g[i] == '*' -> { append("[^/]*"); i++ }
                        g[i] == '?' -> { append("[^/]"); i++ }
                        else -> { append(Regex.escape(g[i].toString())); i++ }
                    }
                }
                append('$')
            }
            return runCatching { Regex(pattern).matches(p) }.getOrDefault(false)
        }
    }
}
